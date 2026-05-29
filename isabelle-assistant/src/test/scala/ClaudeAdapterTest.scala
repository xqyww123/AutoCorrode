package isabelle.assistant

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ClaudeAdapterTest extends AnyFunSuite with Matchers {

  test("isClaudeDirectModel accepts direct API model IDs") {
    ClaudeAdapter.isClaudeDirectModel("claude-sonnet-4-6") shouldBe true
    ClaudeAdapter.isClaudeDirectModel("claude-opus-4-7") shouldBe true
    ClaudeAdapter.isClaudeDirectModel("claude-opus-4-1-20250805") shouldBe true
    ClaudeAdapter.isClaudeDirectModel("claude-haiku-4-5-20251001") shouldBe true
    ClaudeAdapter.isClaudeDirectModel("claude-3-5-haiku-20241022") shouldBe true
  }

  test("isClaudeDirectModel rejects Bedrock model IDs") {
    ClaudeAdapter.isClaudeDirectModel("anthropic.claude-3-5-sonnet-20241022-v2:0") shouldBe false
    ClaudeAdapter.isClaudeDirectModel("us.anthropic.claude-3-7-sonnet-20250219-v1:0") shouldBe false
  }

  test("isClaudeDirectModel rejects OpenAI model IDs") {
    ClaudeAdapter.isClaudeDirectModel("gpt-4o") shouldBe false
    ClaudeAdapter.isClaudeDirectModel("openai/gpt-4o") shouldBe false
  }

  test("isClaudeDirectModel rejects empty string") {
    ClaudeAdapter.isClaudeDirectModel("") shouldBe false
  }

  test("no model ID is claimed by multiple providers") {
    val ids = List(
      "claude-sonnet-4-6", "claude-opus-4-7",
      "anthropic.claude-3-5-sonnet-20241022-v2:0",
      "gpt-4o", "openai/gpt-4o"
    )
    for (id <- ids) {
      val claims = List(
        ClaudeAdapter.isClaudeDirectModel(id),
        OpenAIAdapter.isOpenAIModel(id),
        BedrockModels.isAnthropicModelId(id)
      ).count(identity)
      claims should be <= 1
    }
  }

  test("stripBedrockVersion removes anthropic_version field") {
    val input = """{"anthropic_version":"bedrock-2023-05-31","max_tokens":4096,"system":"hello","messages":[]}"""
    val result = ClaudeAdapter.stripBedrockVersion(input)
    result should not include "anthropic_version"
    result should not include "bedrock-2023-05-31"
    result should include("max_tokens")
    result should include("system")
    result should include("messages")
  }

  test("stripBedrockVersion preserves nested objects unchanged") {
    val input = """{"anthropic_version":"bedrock-2023-05-31","tools":[{"name":"find","input_schema":{"type":"object"}}]}"""
    val result = ClaudeAdapter.stripBedrockVersion(input)
    result should not include "anthropic_version"
    result should include("find")
    result should include("input_schema")
  }

  test("stripBedrockVersion handles payload without anthropic_version") {
    val input = """{"max_tokens":4096,"messages":[]}"""
    val result = ClaudeAdapter.stripBedrockVersion(input)
    result should include("max_tokens")
    result should include("messages")
  }
}
