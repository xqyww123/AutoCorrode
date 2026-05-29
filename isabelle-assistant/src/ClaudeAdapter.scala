/* Direct Anthropic Messages API adapter for BedrockClient.
   The payload format is identical to Bedrock's Anthropic format, so no
   conversion is needed — only transport (HTTP + auth headers) differs.
   The sole transformation is stripping the Bedrock-specific
   "anthropic_version" field from the JSON body. */

package isabelle.assistant

import isabelle._

import java.io.{BufferedReader, InputStreamReader}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets

import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import software.amazon.awssdk.thirdparty.jackson.core.{JsonFactory, JsonToken}

object ClaudeAdapter {

  private val _totalPromptTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalCompletionTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalCachedTokens = new java.util.concurrent.atomic.AtomicLong(0)
  private val _totalRequests = new java.util.concurrent.atomic.AtomicLong(0)

  def resetUsage(): Unit = {
    _totalPromptTokens.set(0)
    _totalCompletionTokens.set(0)
    _totalCachedTokens.set(0)
    _totalRequests.set(0)
  }

  def totalPromptTokens: Long = _totalPromptTokens.get()
  def totalCompletionTokens: Long = _totalCompletionTokens.get()
  def totalCachedTokens: Long = _totalCachedTokens.get()
  def totalRequests: Long = _totalRequests.get()

  def usageSummary: String =
    s"Claude API usage: ${totalRequests} requests, ${totalPromptTokens} prompt tokens (${totalCachedTokens} cached), ${totalCompletionTokens} completion tokens"

  def isClaudeDirectModel(modelId: String): Boolean =
    modelId.startsWith("claude-")

  def makeInvoker(apiKey: String, baseUrl: String = "https://api.anthropic.com"): InvokeModelRequest => String = {
    request => {
      val rawPayload = request.body().asUtf8String()
      val payload = stripBedrockVersion(rawPayload)
      val modelId = request.modelId()

      Output.writeln(s"[Assistant] Claude API call, model=$modelId, payload size=${payload.length}")
      val response = callMessages(payload, apiKey, baseUrl)
      recordUsage(response)
      Output.writeln(s"[Assistant] Claude API call succeeded")
      response
    }
  }

  private val jsonFactory = new JsonFactory()

  private[assistant] def stripBedrockVersion(json: String): String = {
    val parser = jsonFactory.createParser(json)
    val sw = new java.io.StringWriter()
    val gen = jsonFactory.createGenerator(sw)
    try {
      var depth = 0
      var first = true
      var token = parser.nextToken()
      while (token != null) {
        token match {
          case JsonToken.START_OBJECT =>
            gen.writeStartObject()
            depth += 1
            if (depth == 1) first = true
          case JsonToken.END_OBJECT =>
            gen.writeEndObject()
            depth -= 1
          case JsonToken.START_ARRAY =>
            gen.writeStartArray()
          case JsonToken.END_ARRAY =>
            gen.writeEndArray()
          case JsonToken.FIELD_NAME =>
            val name = parser.currentName()
            if (depth == 1 && name == "anthropic_version") {
              parser.nextToken()
              val _ = parser.getValueAsString()
            } else {
              gen.writeFieldName(name)
            }
          case JsonToken.VALUE_STRING =>
            gen.writeString(parser.getValueAsString)
          case JsonToken.VALUE_NUMBER_INT =>
            gen.writeNumber(parser.getLongValue)
          case JsonToken.VALUE_NUMBER_FLOAT =>
            gen.writeNumber(parser.getDoubleValue)
          case JsonToken.VALUE_TRUE =>
            gen.writeBoolean(true)
          case JsonToken.VALUE_FALSE =>
            gen.writeBoolean(false)
          case JsonToken.VALUE_NULL =>
            gen.writeNull()
          case _ =>
        }
        token = parser.nextToken()
      }
      gen.close()
      sw.toString
    } finally {
      parser.close()
    }
  }

  private def callMessages(payload: String, apiKey: String, baseUrl: String): String = {
    val url = URI.create(s"$baseUrl/v1/messages").toURL
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("x-api-key", apiKey)
    conn.setRequestProperty("anthropic-version", "2023-06-01")
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
        Output.writeln(s"[Assistant] Claude API error (HTTP $status): $errBody")
        throw new RuntimeException(s"Claude API error ($status): $errBody")
      }
    } finally {
      reader.close()
    }
  }

  private def recordUsage(responseJson: String): Unit = {
    val root = JSON.parse(responseJson)
    val usage = JSON.value(root, "usage").getOrElse(Map.empty[String, Any])
      .asInstanceOf[Map[String, Any]]
    val input = intFromJson(usage, "input_tokens")
    val output = intFromJson(usage, "output_tokens")
    val cached = intFromJson(usage, "cache_read_input_tokens")
    val _ = _totalPromptTokens.addAndGet(input.toLong)
    val _ = _totalCompletionTokens.addAndGet(output.toLong)
    val _ = _totalCachedTokens.addAndGet(cached.toLong)
    val _ = _totalRequests.incrementAndGet()
  }

  private def intFromJson(obj: Map[String, Any], key: String): Int =
    JSON.int(obj, key).orElse(
      JSON.value(obj, key).collect { case d: Double => d.toLong.toInt }
    ).getOrElse(0)
}
