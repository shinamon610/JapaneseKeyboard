package com.kazumaproject.markdownhelperkeyboard.gemini

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class GeminiProofreadClient(
    private val gson: Gson = Gson()
) {

    suspend fun proofread(
        apiKey: String,
        selectedText: String,
        glossaryEntries: List<String>,
        model: String = DEFAULT_MODEL
    ): String = withContext(Dispatchers.IO) {
        Timber.i(
            "Proofread request start model=%s selectedLength=%d glossaryCount=%d",
            model,
            selectedText.length,
            glossaryEntries.size
        )
        val connection =
            (URL("$BASE_URL/models/$model:generateContent?key=$apiKey").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(gson.toJson(createRequestBody(selectedText, glossaryEntries)))
            }

            val statusCode = connection.responseCode
            val responseBody = runCatching {
                val stream =
                    if (statusCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            Timber.i(
                "Proofread response status=%d body=%s",
                statusCode,
                responseBody.take(LOG_PREVIEW_LIMIT)
            )

            if (statusCode !in 200..299) {
                throw IOException(extractErrorMessage(responseBody).ifBlank {
                    "Gemini API request failed with status $statusCode"
                })
            }

            val corrected = extractCorrectedText(responseBody)
            if (corrected.isBlank()) {
                throw IOException("Gemini API returned an empty proofreading result.")
            }
            Timber.i(
                "Proofread parsed correctedLength=%d corrected=%s",
                corrected.length,
                corrected.take(LOG_PREVIEW_LIMIT)
            )
            corrected
        } finally {
            connection.disconnect()
        }
    }

    private fun createRequestBody(selectedText: String, glossaryEntries: List<String>): JsonObject {
        val glossaryText = if (glossaryEntries.isEmpty()) {
            "なし"
        } else {
            glossaryEntries.joinToString(separator = "\n")
        }

        val prompt = buildString {
            appendLine("あなたは音声入力テキストの補正専用アシスタントです。")
            appendLine("以下の制約を厳守してください。")
            appendLine("- 出力は校正後の本文のみ")
            appendLine("- 説明、箇条書き、前置き、引用符、コードブロックは禁止")
            appendLine("- 最優先は、音声入力で起きやすい誤りの修正")
            appendLine("- 同音異義語の誤変換、音が近い別語への誤変換、助詞の取り違えを優先して直す")
            appendLine("- 「えー」「あの」「そのー」など、意味のないフィラーは削除してよい")
            appendLine("- 意味は変えない。内容の言い換え、要約、加筆は禁止")
            appendLine("- 文章を綺麗にしすぎない。自然な範囲の最小修正にとどめる")
            appendLine("- 明らかな誤り以外は原文を維持する")
            appendLine("- ユーザー辞書にある語彙は最優先で採用する")
            appendLine()
            appendLine("ユーザー辞書:")
            appendLine(glossaryText)
            appendLine()
            appendLine("補正対象:")
            append(selectedText)
        }

        return JsonObject().apply {
            add("contents", gson.toJsonTree(listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to prompt))
                )
            )))
            add("generationConfig", gson.toJsonTree(
                mapOf(
                    "temperature" to 0.2,
                    "topP" to 0.8
                )
            ))
        }
    }

    private fun extractCorrectedText(responseBody: String): String {
        val root = gson.fromJson(responseBody, JsonObject::class.java)
        val candidates = root.getAsJsonArray("candidates") ?: return ""
        if (candidates.size() == 0) return ""
        val content = candidates[0].asJsonObject.getAsJsonObject("content") ?: return ""
        val parts = content.getAsJsonArray("parts") ?: return ""
        val text = buildString {
            for (part in parts) {
                val obj = part.asJsonObject
                if (obj.has("text")) {
                    append(obj.get("text").asString)
                }
            }
        }
        return text
            .trim()
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun extractErrorMessage(responseBody: String): String {
        return runCatching {
            val root = gson.fromJson(responseBody, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.asString.orEmpty()
        }.getOrDefault("")
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val LOG_PREVIEW_LIMIT = 400
    }
}
