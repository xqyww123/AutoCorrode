/* OpenAI Chat Completions adapter for BedrockClient.
   Converts between Anthropic and OpenAI JSON formats so the existing
   agentic loop (invokeChatWithToolsTestable) works unchanged. */

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

  private def recordUsage(openaiJson: String): Unit = {
    val root = JSON.parse(openaiJson)
    val usage = JSON.value(root, "usage").getOrElse(Map.empty[String, Any])
      .asInstanceOf[Map[String, Any]]
    val prompt = intFromJson(usage, "prompt_tokens")
    val completion = intFromJson(usage, "completion_tokens")
    val cached = JSON.value(usage, "prompt_tokens_details")
      .collect { case m: Map[String @unchecked, _] => m.asInstanceOf[Map[String, Any]] }
      .map(d => intFromJson(d, "cached_tokens"))
      .getOrElse(0)
    val _ = _totalPromptTokens.addAndGet(prompt.toLong)
    val _ = _totalCompletionTokens.addAndGet(completion.toLong)
    val _ = _totalCachedTokens.addAndGet(cached.toLong)
    val _ = _totalRequests.incrementAndGet()
  }

  def isOpenAIModel(modelId: String): Boolean =
    modelId.startsWith("openai/") || modelId.startsWith("gpt-")

  def extractOpenAIModel(modelId: String): String =
    if (modelId.startsWith("openai/")) modelId.stripPrefix("openai/") else modelId

  def makeInvoker(apiKey: String, baseUrl: String = "https://api.openai.com/v1"): InvokeModelRequest => String = {
    request => {
      val anthropicPayload = request.body().asUtf8String()
      val modelId = extractOpenAIModel(request.modelId())
      val openaiPayload = anthropicToOpenAI(anthropicPayload, modelId)
      val openaiResponse = callOpenAI(openaiPayload, apiKey, baseUrl)
      recordUsage(openaiResponse)
      openaiToAnthropic(openaiResponse)
    }
  }

  private def anthropicToOpenAI(anthropicJson: String, model: String): String = {
    val root = JSON.parse(anthropicJson)
    val maxTokens = JSON.int(root, "max_tokens").getOrElse(4096)
    val systemText = JSON.string(root, "system").getOrElse("")
    val messages = JSON.list(root, "messages", (x: JSON.T) => Some(x)).getOrElse(Nil)
    val tools = JSON.list(root, "tools", (x: JSON.T) => Some(x)).getOrElse(Nil)

    val sb = new StringBuilder
    sb.append(s"""{"model":${jsonStr(model)},"max_completion_tokens":$maxTokens,"messages":[""")

    var first = true

    if (systemText.nonEmpty) {
      sb.append(s"""{"role":"system","content":${jsonStr(systemText)}}""")
      first = false
    }

    for (msg <- messages) {
      if (!first) sb.append(",")
      first = false
      val role = JSON.string(msg, "role").getOrElse("user")
      val content = JSON.value(msg, "content")

      content match {
        case Some(list: List[_]) =>
          val blocks = list.collect { case m: Map[String @unchecked, _] => m }
          if (role == "user") {
            val converted = convertUserBlocks(blocks)
            sb.append(converted.mkString(","))
          } else if (role == "assistant") {
            sb.append(convertAssistantBlocks(blocks))
          }
        case Some(s: String) =>
          sb.append(s"""{"role":${jsonStr(role)},"content":${jsonStr(s)}}""")
        case _ =>
          sb.append(s"""{"role":${jsonStr(role)},"content":""}""")
      }
    }

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
        sb.append(s"""{"type":"function","function":{"name":${jsonStr(name)},"description":${jsonStr(desc)},"parameters":${jsonAny(schema)}}}""")
      }
      sb.append("]")
    }

    sb.append(",\"reasoning\":{\"effort\":\"high\"}")
    sb.append("}")
    sb.toString
  }

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
        val toolUseId = JSON.string(block, "tool_use_id").getOrElse("")
        val content = JSON.string(block, "content").getOrElse("")
        results += s"""{"role":"tool","tool_call_id":${jsonStr(toolUseId)},"content":${jsonStr(content)}}"""
      } else if (blockType == "text") {
        textParts += JSON.string(block, "text").getOrElse("")
      }
    }

    if (textParts.nonEmpty) {
      results += s"""{"role":"user","content":${jsonStr(textParts.mkString("\n"))}}"""
    }

    results.toList
  }

  private def convertAssistantBlocks(blocks: List[Map[String, Any]]): String = {
    val textParts = scala.collection.mutable.ListBuffer[String]()
    val toolCalls = scala.collection.mutable.ListBuffer[Map[String, Any]]()

    for (block <- blocks) {
      val blockType = JSON.string(block, "type").getOrElse("")
      if (blockType == "text") {
        textParts += JSON.string(block, "text").getOrElse("")
      } else if (blockType == "tool_use") {
        toolCalls += block
      }
    }

    val sb = new StringBuilder
    sb.append("""{"role":"assistant",""")
    if (textParts.nonEmpty) {
      sb.append(s""""content":${jsonStr(textParts.mkString("\n"))}""")
    } else {
      sb.append(""""content":null""")
    }

    if (toolCalls.nonEmpty) {
      sb.append(",\"tool_calls\":[")
      var first = true
      for (tc <- toolCalls) {
        if (!first) sb.append(",")
        first = false
        val id = JSON.string(tc, "id").getOrElse("")
        val name = JSON.string(tc, "name").getOrElse("")
        val input = JSON.value(tc, "input").getOrElse(Map.empty)
        sb.append(s"""{"id":${jsonStr(id)},"type":"function","function":{"name":${jsonStr(name)},"arguments":${jsonStr(jsonAny(input))}}}""")
      }
      sb.append("]")
    }

    sb.append("}")
    sb.toString
  }

  private def callOpenAI(payload: String, apiKey: String, baseUrl: String): String = {
    val url = URI.create(s"$baseUrl/chat/completions").toURL
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
        throw new RuntimeException(s"OpenAI API error ($status): ${sb.toString.take(500)}")
      }
    } finally {
      reader.close()
    }
  }

  private def openaiToAnthropic(openaiJson: String): String = {
    val root = JSON.parse(openaiJson)
    val choices = JSON.list(root, "choices", (x: JSON.T) => Some(x)).getOrElse(Nil)
    val choice = choices.headOption.getOrElse(Map.empty[String, Any])
    val message = JSON.value(choice, "message").getOrElse(Map.empty[String, Any])
      .asInstanceOf[Map[String, Any]]
    val finishReason = JSON.string(choice, "finish_reason").getOrElse("stop")

    val stopReason = finishReason match {
      case "tool_calls" => "tool_use"
      case "length"     => "max_tokens"
      case _            => "end_turn"
    }

    val sb = new StringBuilder
    sb.append(s"""{"stop_reason":${jsonStr(stopReason)},"content":[""")

    var first = true

    val textContent = JSON.string(message, "content")
    textContent.foreach { text =>
      if (text.nonEmpty) {
        sb.append(s"""{"type":"text","text":${jsonStr(text)}}""")
        first = false
      }
    }

    val toolCalls = JSON.list(message, "tool_calls", (x: JSON.T) => Some(x)).getOrElse(Nil)
    for (tc <- toolCalls) {
      if (!first) sb.append(",")
      first = false
      val id = JSON.string(tc, "id").getOrElse("")
      val fn = JSON.value(tc, "function").getOrElse(Map.empty[String, Any])
        .asInstanceOf[Map[String, Any]]
      val name = JSON.string(fn, "name").getOrElse("")
      val argsStr = JSON.string(fn, "arguments").getOrElse("{}")
      sb.append(s"""{"type":"tool_use","id":${jsonStr(id)},"name":${jsonStr(name)},"input":$argsStr}""")
    }

    sb.append("]}")
    sb.toString
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
