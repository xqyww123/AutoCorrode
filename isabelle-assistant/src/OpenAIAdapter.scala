/* OpenAI Responses API adapter for BedrockClient.
   Converts between Anthropic and OpenAI JSON formats so the existing
   agentic loop (invokeChatWithToolsTestable) works unchanged.
   Uses previous_response_id for multi-turn context chaining,
   preserving reasoning items server-side across turns. */

package isabelle.assistant

import isabelle._

import java.io.{BufferedReader, InputStreamReader}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets

import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

object OpenAIAdapter {

  case class Usage(promptTokens: Long, completionTokens: Long, totalTokens: Long)

  private val _totalPromptTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalCompletionTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalCachedTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalRequests = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalToolCalls = new java.util.concurrent.atomic.AtomicLong(0)

  def resetUsage(): Unit = {
    _totalPromptTokens.set(0)
    _totalCompletionTokens.set(0)
    _totalCachedTokens.set(0)
    _totalRequests.set(0)
    _totalToolCalls.set(0)
  }

  def totalPromptTokens: Long = _totalPromptTokens.get()
  def totalCompletionTokens: Long = _totalCompletionTokens.get()
  def totalCachedTokens: Long = _totalCachedTokens.get()
  def totalRequests: Long = _totalRequests.get()
  def totalToolCalls: Long = _totalToolCalls.get()
  def addToolCalls(n: Int): Unit = { val _ = _totalToolCalls.addAndGet(n.toLong) }

  def usageSummary: String =
    s"OpenAI usage: ${totalRequests} requests, ${totalPromptTokens} prompt tokens (${totalCachedTokens} cached), ${totalCompletionTokens} completion tokens"

  private def intFromJson(obj: Map[String, Any], key: String): Int =
    JSON.int(obj, key).orElse(
      JSON.value(obj, key).collect { case d: Double => d.toLong.toInt }
    ).getOrElse(0)

  private def recordUsage(responseJson: String): Unit = {
    val root = JSON.parse(responseJson)
    val usage = JSON.value(root, "usage").getOrElse(Map.empty[String, Any])
      .asInstanceOf[Map[String, Any]]
    val input = intFromJson(usage, "input_tokens")
    val output = intFromJson(usage, "output_tokens")
    val cached = JSON.value(usage, "input_tokens_details")
      .collect { case m: Map[String @unchecked, _] => m.asInstanceOf[Map[String, Any]] }
      .map(d => intFromJson(d, "cached_tokens"))
      .getOrElse(0)
    val _ = _totalPromptTokens.addAndGet(input.toLong)
    val _ = _totalCompletionTokens.addAndGet(output.toLong)
    val _ = _totalCachedTokens.addAndGet(cached.toLong)
    val _ = _totalRequests.incrementAndGet()
  }

  def isOpenAIModel(modelId: String): Boolean =
    modelId.startsWith("openai/") || modelId.startsWith("gpt-")

  def extractOpenAIModel(modelId: String): String =
    if (modelId.startsWith("openai/")) modelId.stripPrefix("openai/") else modelId

  // The closure is stateful: it tracks the last response ID for
  // previous_response_id chaining. BedrockClient's agentic loop calls the
  // invoker sequentially (single-threaded), so no synchronization is needed.
  def makeInvoker(apiKey: String, baseUrl: String = "https://api.openai.com/v1"): InvokeModelRequest => String = {
    var lastResponseId: Option[String] = None

    request => {
      val anthropicPayload = request.body().asUtf8String()
      val modelId = extractOpenAIModel(request.modelId())

      try {
        val (mode, payload) = lastResponseId match {
          case Some(prevId) =>
            ("incremental", anthropicToResponsesIncremental(anthropicPayload, modelId, prevId))
          case None =>
            ("full", anthropicToResponses(anthropicPayload, modelId))
        }
        Output.writeln(s"[Assistant] Responses API call ($mode), payload size=${payload.length}")
        val response = callResponses(payload, apiKey, baseUrl)
        recordUsage(response)
        lastResponseId = extractResponseId(response)
        Output.writeln(s"[Assistant] Responses API call succeeded (response_id=${lastResponseId.getOrElse("none")})")
        responsesToAnthropic(response)
      } catch {
        case ex: RuntimeException if lastResponseId.isDefined =>
          Output.writeln(s"[Assistant] Incremental Responses API call failed, falling back to full context: ${ex.getMessage}")
          lastResponseId = None
          val payload = anthropicToResponses(anthropicPayload, modelId)
          Output.writeln("[Assistant] Responses API fallback call (full)")
          val response = callResponses(payload, apiKey, baseUrl)
          recordUsage(response)
          lastResponseId = extractResponseId(response)
          Output.writeln(s"[Assistant] Fallback succeeded (response_id=${lastResponseId.getOrElse("none")})")
          responsesToAnthropic(response)
      }
    }
  }

  private def extractResponseId(responseJson: String): Option[String] = {
    val root = JSON.parse(responseJson)
    JSON.string(root, "id")
  }

  // Convert full Anthropic payload to Responses API format (first call)
  private def anthropicToResponses(anthropicJson: String, model: String): String = {
    val root = JSON.parse(anthropicJson)
    val maxTokens = JSON.int(root, "max_tokens").getOrElse(4096)
    val systemText = JSON.string(root, "system").getOrElse("")
    val messages = JSON.list(root, "messages", (x: JSON.T) => Some(x)).getOrElse(Nil)
    val tools = JSON.list(root, "tools", (x: JSON.T) => Some(x)).getOrElse(Nil)

    val inputItems = scala.collection.mutable.ListBuffer[String]()
    for (msg <- messages) {
      inputItems ++= convertMessageToInputItems(msg)
    }

    buildResponsesRequest(model, maxTokens, systemText, inputItems.toList, tools, None)
  }

  // Convert only the last message for incremental calls with previous_response_id
  private def anthropicToResponsesIncremental(
      anthropicJson: String, model: String, previousResponseId: String): String = {
    val root = JSON.parse(anthropicJson)
    val maxTokens = JSON.int(root, "max_tokens").getOrElse(4096)
    val systemText = JSON.string(root, "system").getOrElse("")
    val messages = JSON.list(root, "messages", (x: JSON.T) => Some(x)).getOrElse(Nil)
    val tools = JSON.list(root, "tools", (x: JSON.T) => Some(x)).getOrElse(Nil)

    val lastMsg = messages.last
    val inputItems = convertMessageToInputItems(lastMsg)

    buildResponsesRequest(model, maxTokens, systemText, inputItems, tools, Some(previousResponseId))
  }

  private def buildResponsesRequest(
      model: String, maxTokens: Int, instructions: String,
      inputItems: List[String], tools: List[JSON.T],
      previousResponseId: Option[String]): String = {
    val sb = new StringBuilder
    sb.append(s"""{"model":${jsonStr(model)},"max_output_tokens":$maxTokens""")

    if (instructions.nonEmpty) {
      sb.append(s""","instructions":${jsonStr(instructions)}""")
    }

    sb.append(""","reasoning":{"effort":"high"}""")
    sb.append(""","store":true""")
    sb.append(",\"truncation\":\"auto\"")

    previousResponseId.foreach { id =>
      sb.append(s""","previous_response_id":${jsonStr(id)}""")
    }

    sb.append(""","input":[""")
    sb.append(inputItems.mkString(","))
    sb.append("]")

    if (tools.nonEmpty) {
      sb.append(",\"tools\":[")
      var firstTool = true
      for (tool <- tools) {
        if (!firstTool) sb.append(",")
        firstTool = false
        val name = JSON.string(tool, "name").getOrElse("")
        val desc = JSON.string(tool, "description").getOrElse("")
        val schema = JSON.value(tool, "input_schema").getOrElse(Map.empty)
        sb.append(s"""{"type":"function","name":${jsonStr(name)},"description":${jsonStr(desc)},"parameters":${jsonAny(schema)},"strict":false}""")
      }
      sb.append("]")
    }

    sb.append("}")
    sb.toString
  }

  // Convert an Anthropic message to Responses API input items
  private def convertMessageToInputItems(msg: JSON.T): List[String] = {
    val role = JSON.string(msg, "role").getOrElse("user")
    val content = JSON.value(msg, "content")

    content match {
      case Some(list: List[_]) =>
        val blocks = list.collect { case m: Map[String @unchecked, _] => m }
        if (role == "user") convertUserBlocks(blocks)
        else if (role == "assistant") convertAssistantBlocks(blocks)
        else Nil
      case Some(s: String) =>
        List(s"""{"role":${jsonStr(role)},"content":${jsonStr(s)}}""")
      case _ =>
        List(s"""{"role":${jsonStr(role)},"content":""}""")
    }
  }

  // Convert user content blocks to Responses API input items
  private def convertUserBlocks(blocks: List[Map[String, Any]]): List[String] = {
    val results = scala.collection.mutable.ListBuffer[String]()
    val textParts = scala.collection.mutable.ListBuffer[String]()

    for (block <- blocks) {
      val blockType = JSON.string(block, "type").getOrElse("")
      if (blockType == "tool_result") {
        if (textParts.nonEmpty) {
          results += s"""{"role":"user","content":${jsonStr(textParts.mkString("\n"))}}"""
          textParts.clear()
        }
        val callId = JSON.string(block, "tool_use_id").getOrElse("")
        val output = JSON.string(block, "content").getOrElse("")
        results += s"""{"type":"function_call_output","call_id":${jsonStr(callId)},"output":${jsonStr(output)}}"""
      } else if (blockType == "text") {
        textParts += JSON.string(block, "text").getOrElse("")
      }
    }

    if (textParts.nonEmpty) {
      results += s"""{"role":"user","content":${jsonStr(textParts.mkString("\n"))}}"""
    }

    results.toList
  }

  // Convert assistant content blocks to separate Responses API input items
  private def convertAssistantBlocks(blocks: List[Map[String, Any]]): List[String] = {
    val results = scala.collection.mutable.ListBuffer[String]()
    val textParts = scala.collection.mutable.ListBuffer[String]()

    for (block <- blocks) {
      val blockType = JSON.string(block, "type").getOrElse("")
      if (blockType == "text") {
        textParts += JSON.string(block, "text").getOrElse("")
      } else if (blockType == "tool_use") {
        val id = JSON.string(block, "id").getOrElse("")
        val name = JSON.string(block, "name").getOrElse("")
        val input = JSON.value(block, "input").getOrElse(Map.empty)
        results += s"""{"type":"function_call","call_id":${jsonStr(id)},"name":${jsonStr(name)},"arguments":${jsonStr(jsonAny(input))}}"""
      }
    }

    if (textParts.nonEmpty) {
      results.prepend(s"""{"role":"assistant","content":${jsonStr(textParts.mkString("\n"))}}""")
    }

    results.toList
  }

  private def callResponses(payload: String, apiKey: String, baseUrl: String): String = {
    val url = URI.create(s"$baseUrl/responses").toURL
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", s"Bearer $apiKey")
    conn.setConnectTimeout(30000)
    conn.setReadTimeout(300000)
    conn.setDoOutput(true)

    val bytes = payload.getBytes(StandardCharsets.UTF_8)
    val os = conn.getOutputStream
    os.write(bytes)
    os.close()

    val status = conn.getResponseCode
    val stream =
      if (status >= 200 && status < 300) conn.getInputStream
      else Option(conn.getErrorStream).getOrElse(conn.getInputStream)
    val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
    try {
      val sb = new StringBuilder
      var line = reader.readLine()
      while (line != null) {
        sb.append(line)
        line = reader.readLine()
      }
      if (status >= 200 && status < 300) {
        sb.toString
      } else {
        val errBody = sb.toString.take(500)
        Output.writeln(s"[Assistant] OpenAI API error (HTTP $status): $errBody")
        throw new RuntimeException(s"OpenAI API error ($status): $errBody")
      }
    } finally {
      reader.close()
    }
  }

  // Convert Responses API response to Anthropic format for ResponseParser
  private def responsesToAnthropic(responseJson: String): String = {
    val root = JSON.parse(responseJson)
    val status = JSON.string(root, "status").getOrElse("completed")
    val outputItems = JSON.list(root, "output", (x: JSON.T) => Some(x)).getOrElse(Nil)

    if (status == "failed") {
      val error = JSON.value(root, "error")
        .collect { case m: Map[String @unchecked, _] => m.asInstanceOf[Map[String, Any]] }
      val errorMsg = error.flatMap(e => JSON.string(e, "message")).getOrElse("unknown error")
      Output.writeln(s"[Assistant] Responses API returned failed status: $errorMsg")
      return """{"stop_reason":"end_turn","content":[]}"""
    }

    var hasToolCalls = false
    val contentParts = scala.collection.mutable.ListBuffer[String]()

    for (item <- outputItems) {
      val itemType = JSON.string(item, "type").getOrElse("")
      itemType match {
        case "message" =>
          val msgContent = JSON.list(item, "content", (x: JSON.T) => Some(x)).getOrElse(Nil)
          for (part <- msgContent) {
            val partType = JSON.string(part, "type").getOrElse("")
            if (partType == "output_text") {
              val text = JSON.string(part, "text").getOrElse("")
              if (text.nonEmpty) {
                contentParts += s"""{"type":"text","text":${jsonStr(text)}}"""
              }
            }
          }
        case "function_call" =>
          hasToolCalls = true
          val callId = JSON.string(item, "call_id").getOrElse("")
          val name = JSON.string(item, "name").getOrElse("")
          val argsStr = JSON.string(item, "arguments").getOrElse("{}")
          contentParts += s"""{"type":"tool_use","id":${jsonStr(callId)},"name":${jsonStr(name)},"input":$argsStr}"""
        case "reasoning" =>
          // Skip — preserved server-side via previous_response_id
        case _ =>
      }
    }

    val stopReason = if (hasToolCalls) "tool_use"
      else if (status == "incomplete") {
        val details = JSON.value(root, "incomplete_details")
          .collect { case m: Map[String @unchecked, _] => m.asInstanceOf[Map[String, Any]] }
        val reason = details.flatMap(d => JSON.string(d, "reason")).getOrElse("unknown")
        Output.writeln(s"[Assistant] Responses API returned incomplete: $reason")
        "max_tokens"
      }
      else "end_turn"

    s"""{"stop_reason":${jsonStr(stopReason)},"content":[${contentParts.mkString(",")}]}"""
  }

  private[assistant] def jsonStr(s: String): String = {
    val sb = new StringBuilder("\"")
    for (c <- s) {
      c match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case _    => sb.append(c)
      }
    }
    sb.append("\"")
    sb.toString
  }

  private def jsonAny(v: Any): String = v match {
    case null          => "null"
    case s: String     => jsonStr(s)
    case n: Int        => n.toString
    case n: Long       => n.toString
    case n: Double     =>
      val i = n.toLong
      if (i.toDouble == n) i.toString else n.toString
    case b: Boolean    => b.toString
    case m: Map[_, _]  =>
      val entries = m.map { case (k, v2) => s"${jsonStr(k.toString)}:${jsonAny(v2)}" }
      s"{${entries.mkString(",")}}"
    case l: List[_]    =>
      s"[${l.map(jsonAny).mkString(",")}]"
    case other         => jsonStr(other.toString)
  }
}
