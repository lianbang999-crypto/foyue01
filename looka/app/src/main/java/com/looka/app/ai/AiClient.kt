package com.looka.app.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.looka.app.data.Prefs
import com.looka.app.net.Api
import com.looka.app.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 调用三级路由：
 * 1) 用户填了自己的硅基流动 Key → 直连（走用户自己的账单）
 * 2) 已登录 Looka 账号 → 服务端代理（Key 不进客户端；按鹿角计次，见 economy.v1）
 * 3) 都没有 → 抛出引导性错误
 */
object AiClient {

    /** §55：鹿角余额（-1 = 未知）与今日到账数（G3 轻提示用，展示一次后清零） */
    var lastAntler by mutableStateOf(-1); private set
    var grantedToday by mutableStateOf(0)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 统一聊天入口（§53 M0：单模型 Qwen，档位已下线）。
     * onDelta 非空 → 走真流式（T1），每段增量回调；null → 非流式一次性返回。
     */
    suspend fun chat(
        ctx: Context,
        system: String,
        history: List<Pair<String, String>>,
        temperature: Double = 0.6,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val customKey = Prefs.apiKey(ctx).trim()
        if (customKey.isNotBlank()) return direct(ctx, customKey, system, history, temperature)
        if (Api.authed(ctx)) {
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in history) {
                msgs.put(JSONObject().put("role", role).put("content", content))
            }
            if (onDelta != null) {
                val raw = Api.aiChatStream(ctx, msgs, temperature, { total, granted ->
                    lastAntler = total; if (granted > 0) grantedToday = granted
                }, onDelta)
                val content = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                if (content.isBlank()) throw IOException(tr("AI 返回为空，请重试"))
                return content
            }
            val resp = Api.aiChat(ctx, msgs, temperature)
            lastAntler = resp.optJSONObject("antler")?.optInt("total", -1) ?: -1
            resp.optInt("granted_today", 0).let { if (it > 0) grantedToday = it }
            val content = resp.optString("content")
                .replace(Regex("(?s)<think>.*?</think>"), "").trim()
            if (content.isBlank()) throw IOException(tr("AI 返回为空，请重试"))
            return content
        }
        throw IOException(tr("请先在「更多 → 账号与同步」登录后使用小鹿 AI"))
    }

    /** 直连硅基流动（用户自己的 Key） */
    private suspend fun direct(
        ctx: Context,
        key: String,
        system: String,
        history: List<Pair<String, String>>,
        temperature: Double
    ): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        for ((role, content) in history) {
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("messages", msgs)
            .put("temperature", temperature)
            .put("max_tokens", 2048)

        val req = Request.Builder()
            .url(Prefs.baseUrl(ctx).trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errMsg(resp.code, txt))
            val content = try {
                JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            } catch (e: Exception) {
                throw IOException(tr("返回格式异常"))
            }
            content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
    }

    private fun errMsg(code: Int, body: String): String {
        val detail = try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val hint = when (code) {
            401 -> tr("API Key 无效或已过期")
            402 -> tr("账户余额不足")
            404 -> tr("模型不存在，请检查模型名")
            429 -> tr("请求过于频繁，稍后再试")
            else -> tr("请求失败 HTTP {0}", code)
        }
        return if (detail.isNullOrBlank()) hint else "$hint（$detail）"
    }
}
