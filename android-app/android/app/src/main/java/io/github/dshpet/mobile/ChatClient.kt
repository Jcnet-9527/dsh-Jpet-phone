package io.github.dshpet.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/** One turn of conversation. */
data class ChatTurn(val role: String, val content: String)

/**
 * A finished answer. [truncated] is true when the provider stopped because it
 * ran into [ChatClient.MAX_TOKENS] - the only way to know an answer is missing
 * its ending instead of guessing from the text.
 */
data class ChatReply(val text: String, val truncated: Boolean = false)

/** One currency's balance, exactly as `/user/balance` spells it out. */
internal data class BalanceRow(val currency: String, val total: Double,
                               val granted: Double, val toppedUp: Double)

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
            formatBalance(body)
        } catch (e: IOException) {
            throw ChatException("连不上接口：${e.message ?: "网络错误"}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns a `/user/balance` body into one bubble line.
     *
     * `balance_infos` is an **array** (`object[]`) - reading it as an object is
     * what made every query end with "查询成功，但接口没给出数字": `optJSONObject`
     * returns null for an array, the list came out empty, and the blank fallback
     * fired.
     */
    fun formatBalance(body: String): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ChatException("余额返回无法解析")
        if (!parsed.optBoolean("is_available", true)) return "余额已耗尽或账号受限"
        return renderBalance(balanceRows(parsed))
    }

    /**
     * Reads the currency rows. The array shape is the documented one; a plain
     * object is still accepted because some OpenAI-compatible proxies rewrite
     * the payload, and a lookup that only knows one shape is how the original
     * bug went unnoticed.
     */
    private fun balanceRows(parsed: JSONObject): List<BalanceRow> {
        val rows = ArrayList<BalanceRow>()
        fun take(entry: JSONObject?) {
            if (entry == null) return
            val total = entry.optString("total_balance", "").toDoubleOrNull() ?: return
            rows += BalanceRow(entry.optString("currency", ""), total,
                entry.optString("granted_balance", "").toDoubleOrNull() ?: 0.0,
                entry.optString("topped_up_balance", "").toDoubleOrNull() ?: 0.0)
        }
        val array = parsed.optJSONArray("balance_infos")
        if (array != null) {
            for (index in 0 until array.length()) take(array.optJSONObject(index))
        } else {
            parsed.optJSONObject("balance_infos")?.let { keyed ->
                keyed.keys().asSequence().forEach { take(keyed.optJSONObject(it)) }
            }
        }
        return rows
    }

    /** The bubble text for [rows]; pure, so the formatting is unit-tested. */
    internal fun renderBalance(rows: List<BalanceRow>): String {
        if (rows.isEmpty()) return "余额查询成功，但接口没给出数字"
        // Two currencies have no common unit: list them, and skip the split
        // (it would be twice as long as the bubble likes).
        if (rows.size > 1) {
            return "余额 " + rows.joinToString(" · ") { money(it.currency, it.total) }
        }
        val row = rows.first()
        val detail = ArrayList<String>()
        if (row.granted > 0) detail += "赠金 " + money(row.currency, row.granted)
        if (row.toppedUp > 0) detail += "充值 " + money(row.currency, row.toppedUp)
        val head = "余额 " + money(row.currency, row.total)
        return if (detail.isEmpty()) head else head + "（" + detail.joinToString(" · ") + "）"
    }

    /** A currency's own sign when we know it; the code itself otherwise. */
    private fun money(currency: String, amount: Double): String {
        val sign = when (currency.uppercase()) {
            "CNY" -> "¥"
            "USD" -> "$"
            else -> if (currency.isBlank()) "" else "$currency "
        }
        return sign + String.format(Locale.US, "%.2f", amount)
    }
}
