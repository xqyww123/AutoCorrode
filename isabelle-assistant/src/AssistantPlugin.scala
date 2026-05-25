/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
   SPDX-License-Identifier: MIT */

package isabelle.assistant

import isabelle._
import org.gjt.sp.jedit.{EBMessage, EBPlugin}
import org.gjt.sp.jedit.msg.ViewUpdate

/** jEdit plugin lifecycle: starts/stops Assistant services and cleans up resources. */
class AssistantPlugin extends EBPlugin {
  @volatile private var batchTriggered = false

  override def start(): Unit = {
    Output.writeln("Isabelle Assistant starting…")
    IQAvailable.startHeartbeat()
  }

  override def stop(): Unit = {
    Output.writeln("Isabelle Assistant stopping…")
    IQAvailable.stopHeartbeat()
    AssistantDockable.shutdown()
    ToolPermissions.clearSession()
    BedrockClient.cleanup()
    IQMcpClient.closePool()
    ErrorHandler.cleanupAll()
    VerificationCache.clear()
    PromptLoader.clearCache()
    LLMResponseCache.clear()
  }

  override def handleMessage(message: EBMessage): Unit = {
    message match {
      case vu: ViewUpdate if vu.getWhat == ViewUpdate.CREATED =>
        if (!batchTriggered) {
          val batchPrompt = System.getenv("ASSISTANT_BATCH_PROMPT")
          if (batchPrompt != null && batchPrompt.nonEmpty) {
            batchTriggered = true
            val view = vu.getView
            val resultFile = System.getenv("ASSISTANT_BATCH_RESULT_FILE")
            val _ = Isabelle_Thread.fork(name = "assistant-batch") {
              runBatch(view, batchPrompt, resultFile)
            }
          }
        }
      case _ =>
    }
  }

  private def runBatch(view: org.gjt.sp.jedit.View, prompt: String, resultFile: String): Unit = {
    var exitCode = 1
    try {
      val deadlineMs = System.currentTimeMillis() + 600000L // 10 min max

      // Phase 1: wait for I/Q server
      Output.writeln("[Batch] Waiting for I/Q server...")
      while (!IQAvailable.isAvailable) {
        if (System.currentTimeMillis() > deadlineMs) {
          val msg = "TIMEOUT: I/Q server not available after 10 minutes"
          Output.error_message(s"[Batch] $msg")
          writeResult(resultFile, usageJson("error", OpenAIAdapter.jsonStr(msg), 600000L))
          return
        }
        Thread.sleep(2000)
      }
      Output.writeln("[Batch] I/Q is available")

      // Phase 2: wait for theory file to appear in PIDE document model
      Output.writeln("[Batch] Waiting for theory file to load in PIDE...")
      var theoryReady = false
      while (!theoryReady) {
        if (System.currentTimeMillis() > deadlineMs) {
          val msg = "TIMEOUT: No theory file loaded in PIDE after 10 minutes"
          Output.error_message(s"[Batch] $msg")
          writeResult(resultFile, usageJson("error", OpenAIAdapter.jsonStr(msg), 600000L))
          return
        }
        IQMcpClient.callListFiles(
          filterOpen = Some(true),
          filterTheory = Some(true)
        ) match {
          case Right(result) if result.files.nonEmpty =>
            theoryReady = true
            val names = result.files.map(f => f.path.split("/").last).mkString(", ")
            Output.writeln(s"[Batch] Theory files ready: $names")
          case _ =>
            Thread.sleep(3000)
        }
      }

      Output.writeln(s"[Batch] Starting agent with prompt: ${prompt.take(80)}...")
      OpenAIAdapter.resetUsage()
      val startTime = System.currentTimeMillis()
      try {
        BedrockClient.setCurrentView(view)
        AssistantDockable.resetCancel()
        val messages = List(("user", prompt))
        val response = BedrockClient.invokeChat("", messages)
        val elapsed = System.currentTimeMillis() - startTime
        Output.writeln(s"[Batch] Agent finished. Response length: ${response.length}")
        Output.writeln(s"[Batch] ${OpenAIAdapter.usageSummary}")
        Output.writeln(s"[Batch] Elapsed: ${elapsed}ms")
        val resultJson = usageJson("completed", OpenAIAdapter.jsonStr(response), elapsed)
        writeResult(resultFile, resultJson)
        exitCode = 0
      } catch {
        case ex: Exception =>
          val elapsed = System.currentTimeMillis() - startTime
          Output.error_message(s"[Batch] Error: ${ex.getMessage}")
          Output.writeln(s"[Batch] ${OpenAIAdapter.usageSummary}")
          val resultJson = usageJson("error", OpenAIAdapter.jsonStr(Option(ex.getMessage).getOrElse("unknown")), elapsed)
          writeResult(resultFile, resultJson)
      }
    } catch {
      case ex: Throwable =>
        Output.error_message(s"[Batch] Fatal error: ${ex.getClass.getName}: ${ex.getMessage}")
        try {
          val msg = s"${ex.getClass.getName}: ${Option(ex.getMessage).getOrElse("unknown")}"
          writeResult(resultFile, usageJson("fatal_error", OpenAIAdapter.jsonStr(msg), 0L))
        } catch { case _: Throwable => }
    } finally {
      Output.writeln(s"[Batch] Exiting with code $exitCode")
      System.exit(exitCode)
    }
  }

  private def usageJson(status: String, contentJson: String, elapsedMs: Long): String = {
    val uncached = OpenAIAdapter.totalPromptTokens - OpenAIAdapter.totalCachedTokens
    s"""{
      |"status":${OpenAIAdapter.jsonStr(status)},
      |"${if (status == "error") "error" else "response"}":$contentJson,
      |"elapsed_ms":$elapsedMs,
      |"prompt_tokens":${OpenAIAdapter.totalPromptTokens},
      |"cached_tokens":${OpenAIAdapter.totalCachedTokens},
      |"uncached_prompt_tokens":$uncached,
      |"completion_tokens":${OpenAIAdapter.totalCompletionTokens},
      |"api_requests":${OpenAIAdapter.totalRequests}
      |}""".stripMargin
  }

  private def writeResult(path: String, content: String): Unit = {
    if (path != null && path.nonEmpty) {
      val writer = new java.io.FileWriter(new java.io.File(path))
      try writer.write(content)
      finally writer.close()
      Output.writeln(s"[Batch] Result written to $path")
    }
  }
}
