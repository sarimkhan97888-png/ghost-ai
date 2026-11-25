package com.ghost.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GhostActionType {
    const val OPEN_APP = "open_app"
    const val TAP_TEXT = "tap_text"
    const val INPUT_TEXT = "input_text"
    const val PRESS_ENTER = "press_enter"
    const val SCROLL = "scroll"
    const val GO_BACK = "go_back"
    const val GO_HOME = "go_home"
    const val SPEAK_REPLY = "speak_reply"

    val ALL = listOf(
        OPEN_APP, TAP_TEXT, INPUT_TEXT, PRESS_ENTER, SCROLL, GO_BACK, GO_HOME, SPEAK_REPLY
    )
}

data class GhostAction(
    val type: String,
    val target: String = "",
    val text: String = "",
    val direction: String = "",
    val reply: String = ""
) {
    fun signature(): String = "$type|$target|$text|$direction"

    fun describe(): String = when (type) {
        GhostActionType.OPEN_APP -> "open_app(\"$target\")"
        GhostActionType.TAP_TEXT -> "tap_text(\"$target\")"
        GhostActionType.INPUT_TEXT ->
            if (target.isBlank()) "input_text(\"$text\")" else "input_text(\"$text\" into \"$target\")"
        GhostActionType.SCROLL -> "scroll($direction)"
        else -> type
    }
}

sealed class GeminiResult {
    data class Success(val action: GhostAction) : GeminiResult()
    data class Failure(val message: String) : GeminiResult()
}

class GeminiClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * Asks Gemini for the single next action.
     *
     * Reliability: a busy server (HTTP 503/500/429) or a network blip is retried once, then the
     * next model in [MODELS] is tried, so one overloaded model does not stop Ghost.
     *
     * @param stepsDone human-readable log of steps already performed for this command
     * @param screen compact UI tree text from the accessibility service
     */
    suspend fun nextAction(
        apiKey: String,
        command: String,
        stepsDone: List<String>,
        screen: String
    ): GeminiResult {
        var lastCode = 0
        var lastBody = ""
        var sawNetworkError = false

        for (model in MODELS) {
            for (attempt in 1..2) {
                var networkError = false
                val response: Pair<Int, String> = try {
                    callModel(model, apiKey, command, stepsDone, screen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    networkError = true
                    -1 to ""
                } catch (e: Exception) {
                    return GeminiResult.Failure("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                }

                val code = response.first
                val body = response.second
                lastCode = code
                lastBody = body
                sawNetworkError = networkError

                if (code in 200..299) return parseResponse(body)

                val transient = networkError || code == 429 || code in 500..599
                if (transient && attempt == 1) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                break
            }

            // Errors that another model cannot fix: stop here with a clear message.
            val keyOrRequestProblem = lastCode == 401 || lastCode == 403 ||
                (lastCode == 400 && !lastBody.contains("model", ignoreCase = true))
            if (keyOrRequestProblem) return GeminiResult.Failure(errorMessage(lastCode, lastBody))
            // Otherwise (busy, rate limited, model missing, network) fall through to the next model.
        }

        return if (sawNetworkError) {
            GeminiResult.Failure("Network problem. Check your internet connection.")
        } else {
            GeminiResult.Failure(errorMessage(lastCode, lastBody))
        }
    }

    /** One request to one model. Retries once without thinking if the model rejects that setting. */
    private suspend fun callModel(
        model: String,
        apiKey: String,
        command: String,
        stepsDone: List<String>,
        screen: String
    ): Pair<Int, String> {
        var result = post(model, apiKey, buildRequest(command, stepsDone, screen, true).toString())
        if (result.first == 400 && result.second.contains("think", ignoreCase = true)) {
            result = post(model, apiKey, buildRequest(command, stepsDone, screen, false).toString())
        }
        return result
    }

    private suspend fun post(model: String, apiKey: String, payload: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).await()
    }

    private fun buildRequest(
        command: String,
        stepsDone: List<String>,
        screen: String,
        withThinking: Boolean
    ): JSONObject {
        val steps = if (stepsDone.isEmpty()) {
            "(none yet)"
        } else {
            stepsDone.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        }
        val prompt = "USER COMMAND: $command\n\nSTEPS ALREADY DONE:\n$steps\n\nCURRENT SCREEN:\n$screen"

        // Gemini 3.x: keep the default temperature (1.0), and use thinkingLevel (not thinkingBudget).
        // "low" is the fastest level this model accepts ("minimal" is rejected by gemini-3.8-flash).
        val generationConfig = JSONObject()
            .put("maxOutputTokens", 2048)
            .put("responseMimeType", "application/json")
        if (withThinking) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }

        return JSONObject()
            .put(
                "systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            .put(
                "contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", generationConfig)
    }

    private fun parseResponse(raw: String): GeminiResult {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return GeminiResult.Failure("Gemini sent a response I could not read.")
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return GeminiResult.Failure(
                if (block.isNotEmpty()) "Gemini blocked this request ($block)."
                else "Gemini returned no answer."
            )
        }

        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                text.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        return parseAction(text.toString())
    }

    private fun parseAction(text: String): GeminiResult {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return GeminiResult.Failure("Gemini sent an unreadable action.")
        }
        val json = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: JSONException) {
            return GeminiResult.Failure("Gemini sent an unreadable action.")
        }

        val type = str(json, "action").lowercase()
        if (type !in GhostActionType.ALL) {
            return GeminiResult.Failure("Gemini chose an unknown action: $type")
        }
        return GeminiResult.Success(
            GhostAction(
                type = type,
                target = str(json, "target"),
                text = str(json, "text"),
                direction = str(json, "direction").lowercase(),
                reply = str(json, "reply")
            )
        )
    }

    private fun str(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key, "").trim()

    private fun errorMessage(code: Int, body: String): String {
        val apiMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        return when {
            code == 400 && apiMessage.contains("API key", ignoreCase = true) ->
                "Gemini rejected the API key. Save a valid key in the Ghost AI app."
            code == 401 || code == 403 ->
                "Gemini denied access (HTTP $code). Check that the API key is valid and enabled."
            code == 429 -> "Gemini rate limit reached on all models. Wait a minute and try again."
            code in 500..599 -> "Gemini servers are busy right now (HTTP $code). I tried backup models too. Try again in a minute."
            apiMessage.isNotEmpty() -> "Gemini error $code: ${apiMessage.take(160)}"
            else -> "Gemini error $code."
        }
    }

    private suspend fun Call.await(): Pair<Int, String>  = suspendCancellableCoroutine<String> { cont ->
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (cont.isActive) cont.resume(it.code to text)
                    }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        /**
         * Tried in order. The first is the preferred model; the others are backups used when it
         * is overloaded (503) or unavailable (404).
         */
        private val MODELS = listOf("gemini-3.8-flash", "gemini-3-flash-preview", "gemini-2.5-flash")
        private const val RETRY_DELAY_MS = 1500L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val SYSTEM_PROMPT = """
You are GHOST, a voice assistant that operates an Android phone for the user by controlling its screen through an accessibility service.
Each turn you receive the user's voice command, the steps already performed, and the current screen as a list of visible UI nodes.
Reply with exactly ONE next action as a single JSON object and nothing else, in this shape (omit fields you do not need):
{"action": "<one of the actions below>", "target": "...", "text": "...", "direction": "up|down|left|right", "reply": "..."}

Actions:
- open_app: launch an installed app. "target" = app name, for example "WhatsApp".
- tap_text: tap the visible element whose text, content description, hint or id matches "target". Use the label exactly as shown in the screen listing.
- input_text: type "text" into an editable field. Optional "target" = label or hint of the field; if empty, the focused or first visible field is used.
- press_enter: press the keyboard Enter / Search / Send action on the focused field.
- scroll: scroll the main content. "direction" = down (see content further down), up, left or right.
- go_back: press the system Back button.
- go_home: press the system Home button.
- speak_reply: say "reply" out loud to the user. This ENDS the task. Use it to answer a question, confirm that the task is done, or explain why you cannot continue.

Rules:
1. One action per turn. Never invent screen elements: only tap text that appears in the screen listing.
2. After open_app, wait for the next screen listing before choosing the next action.
3. If a step failed, try a different approach (another label, scroll, go_back). If you are still stuck after a few tries, use speak_reply to explain.
4. When the command is fully done, finish with speak_reply and a short confirmation.
5. Never make purchases or payments, transfer money, delete data, or send a message the user did not explicitly ask for. If a step needs the user's confirmation, use speak_reply to ask.
6. If the command needs no phone control (a question or chat), answer directly with speak_reply.
7. Write "reply" in the same language and script the user spoke (Hindi, English or Hinglish). Keep it short and natural, with no markdown and no emojis.
""".trimIndent()
    }
}
