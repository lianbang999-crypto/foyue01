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
 * 2) 已登录 Looka 账号 → 服务端代理（Key 不进客户端；对话不限次，公平限速）
 * 3) 都没有 → 抛出引导性错误
 */
object AiClient {

    /** 服务端代理返回的今日剩余次数（公平使用限速，-1 = 未知） */
    var lastRemaining by mutableStateOf(-1); private set

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 上一轮的档位与回落状态。用 Compose State 而非普通 var ——
     * 否则 UI 不保证重组（A2 修复，2026-08-21）。鹿角已撤出 UI，只留内部记账。
     */
    var lastTier by mutableStateOf("standard"); private set
    var lastFellBack by mutableStateOf<String?>(null); private set

    suspend fun chat(
        ctx: Context,
        system: String,
        history: List<Pair<String, String>>,
        temperature: Double = 0.6,
        tier: String = Prefs.aiTier(ctx)
    ): String {
        val customKey = Prefs.apiKey(ctx).trim()
        if (customKey.isNotBlank()) return direct(ctx, customKey, system, history, temperature)
        if (Api.authed(ctx)) {
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in history) {
                msgs.put(JSONObject().put("role", role).put("content", content))
            }
            val resp = Api.aiChat(ctx, msgs, temperature, tier)
            lastRemaining = resp.optInt("remaining", -1)
            lastTier = resp.optString("tier", "standard")
            // 服务端在体验额度用尽或上游失败时会回落标准模型 —— 必须让用户知道。
            // 注意措辞：鹿角是内部计量（2026-08-21 决定①），对用户只说「体验次数」。
            lastFellBack = resp.optJSONObject("fell_back")?.let { fb ->
                if (fb.optInt("need", 0) > 0)
                    tr("本月高级模型体验次数已用完，已切回标准模型（开通 Pro 不限量）")
                else tr("高级模型暂时不可用，已用标准模型回答")
            }
            val content = resp.optString("content")
                .replace(Regex("(?s)<think>.*?</think>"), "").trim()
            if (content.isBlank()) throw IOException(tr("AI 返回为空，请重试"))
            return content
        }
        throw IOException(tr("请先在「更多 → 账号与同步」登录后使用小鹿 AI（对话不限次）"))
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
