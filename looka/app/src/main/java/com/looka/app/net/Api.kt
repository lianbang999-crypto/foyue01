package com.looka.app.net

import android.content.Context
import com.looka.app.data.Prefs
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
import com.looka.app.util.tr

/** Looka 服务端（looka.foyue.org）API 客户端 */
object Api {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // X5（§58）：流式首字应在几秒内到达；等两分钟只是把故障拖成"卡死"体验
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun authed(c: Context) = !Prefs.authToken(c).isNullOrBlank()

    private suspend fun call(
        c: Context,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(Prefs.serverUrl(c).trimEnd('/') + path)
        if (auth) Prefs.authToken(c)?.let { b.header("Authorization", "Bearer $it") }
        if (body != null) {
            b.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        client.newCall(b.build()).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            val o = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            if (!resp.isSuccessful) {
                if (resp.code == 401 && auth) Prefs.setAuthToken(c, null)  // 会话过期
                throw IOException(o.optString("error").ifBlank { tr("请求失败（HTTP {0}）", resp.code) })
            }
            o
        }
    }

    /** 注册（与 zhi.foyue.org 同一账号体系；内测期需邀请码） */
    suspend fun register(c: Context, account: String, password: String, ref: String = ""): JSONObject =
        call(c, "/api/auth/register", JSONObject().put("account", account).put("password", password)
            .also { if (ref.isNotBlank()) it.put("ref", ref) }, auth = false)

    suspend fun registerWithInvite(c: Context, account: String, password: String, invite: String, ref: String = ""): JSONObject =
        call(c, "/api/auth/register", JSONObject()
            .put("account", account).put("password", password).put("invite", invite)
            .also { if (ref.isNotBlank()) it.put("ref", ref) }, auth = false)

    suspend fun login(c: Context, account: String, password: String): JSONObject =
        call(c, "/api/auth/login", JSONObject().put("account", account).put("password", password), auth = false)

    suspend fun logout(c: Context) {
        runCatching { call(c, "/api/auth/logout", JSONObject()) }
    }

    /** 账号信息 + AI 用量 */
    suspend fun me(c: Context): JSONObject = call(c, "/api/me")

    /** 改昵称（空串 = 清除，回到用账号显示） */
    suspend fun setNickname(c: Context, nickname: String): JSONObject =
        call(c, "/api/me/nickname", JSONObject().put("nickname", nickname))

    /** 兑换订阅码 */
    suspend fun redeem(c: Context, code: String): JSONObject =
        call(c, "/api/redeem", JSONObject().put("code", code))

    /** 支付意图：拿 LK 短码 + 已带备注的爱发电付款链接（付款自动归属到本账号） */
    suspend fun payIntent(c: Context, plan: String = "month"): JSONObject =
        call(c, "/api/pay/intent", JSONObject().put("plan", plan))

    /** 自助认领：粘贴爱发电订单号，服务端反查确认后直接开通 */
    suspend fun payClaim(c: Context, orderNo: String): JSONObject =
        call(c, "/api/pay/claim", JSONObject().put("order_no", orderNo))

    /** 双向同步：push 本地脏记录，拉回 since 之后的服务端记录 */
    suspend fun sync(c: Context, push: JSONArray, since: Long): JSONObject =
        call(c, "/api/sync", JSONObject().put("push", push).put("since", since))

    /**
     * AI 代理（服务端持 Key）。
     * tier: standard 标准模型不限次不计费 / premium 高级模型(Pro 免费·免费用户 1 鹿角) /
     *       flagship 旗舰模型 5 鹿角。余额不足服务端自动回落标准模型并回传 fell_back。
     */
    suspend fun aiChat(c: Context, messages: JSONArray, temperature: Double): JSONObject =
        call(c, "/api/ai/chat", JSONObject()
            .put("messages", messages).put("temperature", temperature))

    /**
     * T1（§53）：流式聊天。服务端 SSE 透传上游（OpenAI 兼容格式）。
     * onDelta 每收到一段增量文本回调一次；返回值 = 完整原文。
     * onAntler(total, grantedToday)：开流前从响应头拿到账面（G3/G4）。
     */
    suspend fun aiChatStream(
        c: Context,
        messages: JSONArray,
        temperature: Double,
        onAntler: (Int, Int) -> Unit,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("messages", messages).put("temperature", temperature).put("stream", true)
        val b = Request.Builder().url(Prefs.serverUrl(c).trimEnd('/') + "/api/ai/chat")
        Prefs.authToken(c)?.let { b.header("Authorization", "Bearer $it") }
        b.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val txt = resp.body?.string().orEmpty()
                val o = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
                if (resp.code == 401) Prefs.setAuthToken(c, null)
                throw IOException(o.optString("error").ifBlank { tr("请求失败（HTTP {0}）", resp.code) })
            }
            onAntler(
                resp.header("X-Antler-Total")?.toIntOrNull() ?: -1,
                resp.header("X-Antler-Granted-Today")?.toIntOrNull() ?: 0
            )
            val src = resp.body?.source() ?: throw IOException(tr("AI 返回为空，请重试"))
            val sb = StringBuilder()
            while (true) {
                val line = try { src.readUtf8Line() } catch (_: Exception) { null } ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val delta = try {
                    JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content").orEmpty()
                } catch (_: Exception) { "" }
                if (delta.isNotEmpty()) { sb.append(delta); onDelta(delta) }
            }
            sb.toString()
        }
    }

    /** 鹿角余额与流水 */
    suspend fun antler(c: Context): JSONObject = call(c, "/api/antler")

    /** 启动配置：注册闸门模式等（无需登录） */
    suspend fun config(c: Context): JSONObject = call(c, "/api/config", auth = false)

    /** 忘记密码：发送重置邮件（统一话术防枚举） */
    suspend fun forgot(c: Context, account: String): JSONObject =
        call(c, "/api/auth/forgot", JSONObject().put("account", account), auth = false)

    /** 绑定找回邮箱（发验证信；验证通过才可用于找回） */
    suspend fun bindEmail(c: Context, email: String): JSONObject =
        call(c, "/api/account/bind-email", JSONObject().put("email", email))

    /** 修改密码（同步影响自知录；其他设备会被退出） */
    suspend fun changePassword(c: Context, old: String, new: String): JSONObject =
        call(c, "/api/account/password", JSONObject().put("old", old).put("password", new))

    /** 注销账号：删除 Looka 云端全部数据 */
    suspend fun deleteAccount(c: Context, password: String): JSONObject =
        call(c, "/api/account/delete", JSONObject().put("password", password))

    /** 崩溃上报（无需登录） */
    suspend fun crash(c: Context, ver: String, model: String, stack: String): JSONObject =
        call(c, "/api/crash", JSONObject().put("ver", ver).put("model", model).put("stack", stack), auth = false)
}
