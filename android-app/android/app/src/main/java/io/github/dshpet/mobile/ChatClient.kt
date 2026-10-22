package io.github.dshpet.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** One turn of conversation. */
data class ChatTurn(val role: String, val content: String)

/**
 * A finished answer. [truncated] is true when the provider stopped because it
 * ran into [ChatClient.MAX_TOKENS] - the only way to know an answer is missing
 * its ending instead of guessing from the text.
 */
data class ChatReply(val text: String, val truncated: Boolean = false)

/**
 * The only network code in the app. It speaks the OpenAI-compatible chat
 * completion protocol, which DeepSeek, Moonshot, SiliconFlow, OpenRouter and
 * most local proxies all implement, plus DeepSeek's own balance endpoint.
 *
 * Everything here is blocking and meant to be called off the main thread.
 * No request is made unless the user asks for one.
 */
object ChatClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * Room for a few paragraphs. Without it the provider's own default applies,
     * which is how a long answer could arrive already cut - and nothing on this
     * side could tell.
     */
    const val MAX_TOKENS = 1200

    /** Carries a message that is safe to show on screen. */
    class ChatException(message: String) : Exception(message)

    private fun base(raw: String): String = raw.trim().trimEnd('/')

    private fun open(raw: String, path: String, apiKey: String): HttpURLConnection {
        val connection = URL(base(raw) + path).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        return connection
    }

    private fun readError(body: String, status: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val message = parsed?.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
        return message ?: "接口返回 $status"
    }

    /** POSTs to `{endpoint}/chat/completions`; [endpoint] may already end in `/v1`. */
    private fun post(endpoint: String, apiKey: String, payload: JSONObject): String {
        val connection = open(endpoint, "/chat/completions", apiKey)
        return try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ChatException(readError(body, status))
            body
        } catch (e: IOException) {
            throw ChatException("网络错误：${e.message ?: "连不上接口"}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One reply for [user]. [history] is the older part of the conversation and is
     * sent verbatim, so callers decide how much memory to keep.
     */
    fun complete(endpoint: String, apiKey: String, model: String, system: String,
                 history: List<ChatTurn>, user: String): ChatReply {
        if (endpoint.isBlank()) throw ChatException("还没有填接口地址")
        if (apiKey.isBlank()) throw ChatException("还没有填 API Key")
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("stream", false).put("temperature", 1.0).put("max_tokens", MAX_TOKENS)
        // DeepSeek serves /chat/completions; OpenAI and most proxies serve /v1/... .
        // Try the bare path first and fall back on 404 so either spelling works.
        val body = try {
            post(endpoint, apiKey, payload)
        } catch (e: ChatException) {
            if (!e.message.orEmpty().contains("404")) throw e
            post(endpoint.trimEnd('/') + "/v1", apiKey, payload)
        }
        val parsed = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ChatException("接口返回的内容无法解析")
        val choices = parsed.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val content = first?.optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) throw ChatException("接口没有返回内容")
        return ChatReply(content.trim(), first.optString("finish_reason") == "length")
    }

    /** Whether this endpoint looks like DeepSeek's, which owns /user/balance. */
    fun supportsBalance(endpoint: String): Boolean {
        val host = runCatching { URL(base(endpoint)).host }.getOrDefault("")
        return host.equals("api.deepseek.com", true) || host.endsWith(".deepseek.com")
    }

    /** The remaining balance, already formatted for a speech bubble. */
    fun balance(endpoint: String, apiKey: String): String {
        if (apiKey.isBlank()) throw ChatException("还没有填 API Key")
        if (!supportsBalance(endpoint)) throw ChatException("当前接口不支持查余额")
        val connection = (URL(base(endpoint) + "/user/balance").openConnection() as HttpURLConnection)
            .apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ChatException(readError(body, status))
            val parsed = runCatching { JSONObject(body) }.getOrNull()
                ?: throw ChatException("余额返回无法解析")
            val balances = parsed.optJSONObject("balance_infos")?.let { info ->
                info.keys().asSequence().mapNotNull { key ->
                    val entry = info.optJSONObject(key) ?: return@mapNotNull null
                    val amount = entry.optString("total_balance", "0").toDoubleOrNull() ?: 0.0
                    "$key ${"%.2f".format(amount)}"
                }.toList().sorted()
            }.orEmpty()
            val granted = parsed.optBoolean("is_available", true)
            if (!granted) "余额已耗尽或账号受限"
            else balances.joinToString(" · ").ifBlank { "余额查询成功，但接口没给出数字" }
        } catch (e: IOException) {
            throw ChatException("连不上接口：${e.message ?: "网络错误"}")
        } finally {
            connection.disconnect()
        }
    }
}
