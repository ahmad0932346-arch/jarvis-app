package com.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Gemini REST client with function calling. Call from a background thread. */
object GeminiClient {
    class Call(val name: String, val args: JSONObject)

    fun step(
        apiKey: String,
        model: String,
        system: String,
        userText: String,
        screenshotB64: String?,
        tools: JSONArray
    ): Call {
        val parts = JSONArray().put(JSONObject().put("text", userText))
        if (screenshotB64 != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", screenshotB64)
                )
            )
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("tools", tools)
            .put(
                "tool_config",
                JSONObject().put("function_calling_config", JSONObject().put("mode", "ANY"))
            )
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        for (attempt in 0 until 4) {
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.connectTimeout = 20_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                conn.outputStream.use { it.write(payload) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code in 200..299) return parse(text)
                if (code == 429 || code >= 500) {
                    Thread.sleep(2000L shl attempt)
                    continue
                }
                throw IOException("Gemini error $code: ${text.take(300)}")
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Gemini is busy or rate-limited. Try again later.")
    }

    private fun parse(text: String): Call {
        val parts = JSONObject(text).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val fc = parts.getJSONObject(i).optJSONObject("functionCall")
                if (fc != null) return Call(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
            }
        }
        throw IOException("Gemini returned no action: ${text.take(200)}")
    }
}
