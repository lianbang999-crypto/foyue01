package com.looka.app.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.Hairline as HairlineColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import com.looka.app.LookaApp
import com.looka.app.R
import com.looka.app.data.Prefs
import com.looka.app.net.Api
import com.looka.app.net.SyncEngine
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.looka.app.util.tr

/**
 * 账号与同步：与 zhi.foyue.org 同一账号体系（Looka 侧注册免邀请码）。
 * 登录后：多端云同步 + 网页端 looka.foyue.org + AI 免费额度。
 */
@Composable
fun AccountScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LookaApp
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    val loggedIn = remember(refresh, vm.settingsVersion) { Api.authed(ctx) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(tr("账号与同步"), onBack = { nav.popBackStack() })
        if (!loggedIn) {
            LoginForm(vm, onDone = { refresh++ })
        } else {
            AccountPanel(vm, refresh, onChanged = { refresh++ })
        }
    }
}

/** 极简输入框：无 label、只留占位符、细边框圆角 —— 与网页端 auth-card 的 input 同款 */
@Composable
private fun MiniField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, fontSize = 14.sp, color = Color(0xFFA8ADA8)) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        visualTransformation =
            if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onDone?.invoke() }),
        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink,
            unfocusedBorderColor = HairlineColor,
            cursorColor = Ink,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LoginForm(vm: LookaViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LookaApp
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(Prefs.accountEmail(ctx)) }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var forgotDlg by remember { mutableStateOf(false) }
    // 注册闸门模式（invite 时显示邀请码输入）
    val registerMode by produceState(initialValue = "open") {
        value = runCatching { Api.config(ctx).optString("register_mode", "open") }.getOrDefault("open")
    }

    fun auth(isRegister: Boolean) {
        if (busy || account.isBlank() || password.isBlank()) return
        scope.launch {
            busy = true; err = null
            try {
                val r: JSONObject = if (isRegister) Api.registerWithInvite(ctx, account.trim(), password, invite.trim())
                else Api.login(ctx, account.trim(), password)
                Prefs.setAuthToken(ctx, r.optString("token"))
                Prefs.setAccountEmail(ctx, account.trim().lowercase())
                com.looka.app.data.PlanState.apply(ctx, r.optString("plan", "free"), r.optLong("plan_expiry", 0L))
                Prefs.setLastPullMs(ctx, 0L)
                SyncEngine.markAllDirty(app)   // 本机数据合并进账号
                vm.bumpSettings()
                toast(ctx, if (isRegister) tr("注册成功，欢迎来到 Looka 🦌") else tr("登录成功"))
                onDone()
                runCatching { SyncEngine.sync(app) }
            } catch (e: Exception) {
                err = e.message ?: tr("网络异常")
            } finally {
                busy = false
            }
        }
    }

    // 单一模式：登录 / 注册。主按钮只有一个，靠文字链切换 —— 与网页端 auth-card 同构。
    var isRegister by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        com.looka.app.ui.common.DeerBadge(64.dp)   // B3：随主题变色
        Spacer(Modifier.height(14.dp))
        Text("Looka", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(
            tr("极简生活手帐"),
            fontSize = 13.sp, color = GrayText,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
        )

        MiniField(account, { account = it }, tr("邮箱或手机号"))
        Spacer(Modifier.height(10.dp))
        MiniField(
            password, { password = it }, tr("密码"), isPassword = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            onDone = { auth(isRegister) }   // F1：密码框回车即登录（对齐网页端 authPass）
        )
        if (isRegister && registerMode == "invite") {
            Spacer(Modifier.height(10.dp))
            MiniField(invite, { invite = it }, tr("邀请码"))
        }

        err?.let {
            Text(
                it, fontSize = 12.5.sp, color = HolidayRed, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { auth(isRegister) },
            enabled = !busy && account.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, disabledContainerColor = Color(0xFFD6D9D6)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(
                color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(17.dp)
            ) else Text(
                if (isRegister) tr("注册") else tr("登录"),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                if (isRegister) tr("已有账号？") else tr("还没有账号？"),
                fontSize = 13.sp, color = GrayText
            )
            Text(
                if (isRegister) tr("登录") else tr("注册"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.plainClick { isRegister = !isRegister; err = null }
            )
        }

        if (!isRegister) {
            Text(
                tr("忘记密码？"), fontSize = 12.5.sp, color = GrayText,
                modifier = Modifier.padding(top = 12.dp).plainClick { forgotDlg = true }
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            tr("继续即代表同意《用户协议》与《隐私政策》"),
            fontSize = 11.sp, color = Color(0xFFB4B8B4), textAlign = TextAlign.Center,
            lineHeight = 17.sp, modifier = Modifier.padding(bottom = 24.dp)
        )
    }

    if (forgotDlg) {
        var fAccount by remember { mutableStateOf(account) }
        var fBusy by remember { mutableStateOf(false) }
        var fMsg by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forgotDlg = false },
            title = { Text(tr("找回密码"), fontSize = 17.sp) },
            text = {
                Column {
                    Text(tr("邮箱账号直接接收重置邮件；手机号账号需要先在登录后绑定并验证邮箱。"),
                        fontSize = 12.sp, color = GrayText, lineHeight = 19.sp)
                    OutlinedTextField(
                        value = fAccount, onValueChange = { fAccount = it },
                        label = { Text(tr("账号")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    fMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            fBusy = true
                            fMsg = try {
                                Api.forgot(ctx, fAccount.trim()).optString("message", tr("已发送"))
                            } catch (e: Exception) { e.message ?: tr("发送失败") }
                            fBusy = false
                        }
                    },
                    enabled = fAccount.isNotBlank() && !fBusy
                ) { Text(tr("发送重置邮件")) }
            },
            dismissButton = {
                TextButton(onClick = { forgotDlg = false }) { Text(tr("关闭"), color = GrayText) }
            },
            containerColor = Color.White
        )
    }
}


@Composable
private fun AccountPanel(vm: LookaViewModel, refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LookaApp
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busyRedeem by remember { mutableStateOf(false) }

    // 拉取账号信息与 AI 用量
    val me by produceState<JSONObject?>(initialValue = null, refresh) {
        value = runCatching { Api.me(ctx) }.getOrNull()
        // P2-A3（§三十九根因行）：读到真值就落盘 —— 此前只显示不保存，
        // 人一离开这个页面，其他页面读到的还是旧缓存
        value?.let { com.looka.app.data.PlanState.apply(ctx, it.optString("plan", "free"), it.optLong("plan_expiry", 0L)) }
    }
    val plan = com.looka.app.data.PlanState.plan
    val aiMonthUsed = me?.optInt("ai_month_used", -1) ?: -1
    val planExpiry = me?.optLong("plan_expiry", 0L) ?: 0L
    val boundEmail = me?.optString("bound_email") ?: ""
    val emailVerified = me?.optBoolean("email_verified") ?: false
    val accountKind = me?.optString("kind") ?: "email"
    var bindDlg by remember { mutableStateOf(false) }
    var pwDlg by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InfoRow(tr("账号"), Prefs.accountEmail(ctx))
        Hairline()
        InfoRow(
            tr("版本"),
            if (plan == "pro") "Pro" + (if (planExpiry > 0) tr(" · {0}到期", Fmt.dateCn(planExpiry / 86400000L)) else "")
            else tr("免费版")
        )
        Hairline()
        // F-8：到期前 7 天内轻提醒（小鹿语气，不吓人）；F-7：到期后会怎样，一点即看
        if (plan == "pro" && planExpiry > 0) {
            val daysLeft = ((planExpiry - System.currentTimeMillis()) / 86400000L).toInt()
            if (daysLeft in 0..7) {
                Text(
                    tr("你的 Pro 还有 {0} 天到期。到期后你做的东西都还在，只是不能再生成新的啦 🦌", daysLeft),
                    fontSize = 12.sp, color = GrayText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            var keepDlg by remember { mutableStateOf(false) }
            Text(
                tr("到期后会怎样？"), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { keepDlg = true }
            )
            if (keepDlg) KeepLoseDialog { keepDlg = false }
            Hairline()
        }
        InfoRow(
            tr("小鹿 AI"),
            when {
                plan == "pro" && aiMonthUsed >= 0 -> tr("不限次 · 本月已聊 {0} 次", aiMonthUsed)
                plan == "pro" -> tr("不限次")
                aiMonthUsed >= 0 -> tr("每天 10 次 · 本月已聊 {0} 次", aiMonthUsed)
                else -> tr("每天 10 次")
            }
        )
        Hairline()
        // 找回邮箱（手机号账号必须绑定并验证才能找回密码）
        if (accountKind == "phone" || boundEmail.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("找回邮箱"), fontSize = 15.sp)
                    Text(
                        when {
                            boundEmail.isBlank() -> tr("未绑定 · 无法找回密码，建议尽快绑定")
                            emailVerified -> tr("{0} · 已验证", boundEmail)
                            else -> tr("{0} · 待验证（去邮箱点链接）", boundEmail)
                        },
                        fontSize = 11.sp,
                        color = if (boundEmail.isBlank()) HolidayRed else GrayText
                    )
                }
                OutlinedButton(onClick = { bindDlg = true }) {
                    Text(if (boundEmail.isBlank()) tr("绑定") else tr("换绑"), fontSize = 13.sp)
                }
            }
            Hairline()
        }

        // 同步
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("云同步"), fontSize = 15.sp)
                Text(
                    SyncEngine.lastMsg.ifBlank { tr("改动会自动同步到云端") },
                    fontSize = 11.sp, color = GrayText
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            SyncEngine.sync(app)
                            toast(ctx, tr("同步完成"))
                        } catch (e: Exception) {
                            toast(ctx, e.message ?: tr("同步失败"))
                        }
                    }
                },
                enabled = !SyncEngine.syncing
            ) {
                if (SyncEngine.syncing) CircularProgressIndicator(
                    strokeWidth = 2.dp, modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.primary
                ) else Text(tr("立即同步"), fontSize = 13.sp)
            }
        }
        Hairline()

        // 兑换订阅码
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text(tr("订阅兑换码"), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busyRedeem = true
                        try {
                            val r = Api.redeem(ctx, code.trim())
                            com.looka.app.data.PlanState.apply(ctx, r.optString("plan", "pro"), r.optLong("expires_at", 0L))
                            vm.bumpSettings()
                            toast(ctx, tr("兑换成功，已升级 {0} 🎉", r.optString("plan", "pro").uppercase()))
                            code = ""
                            onChanged()
                        } catch (e: Exception) {
                            toast(ctx, e.message ?: tr("兑换失败"))
                        } finally {
                            busyRedeem = false
                        }
                    }
                },
                enabled = code.isNotBlank() && !busyRedeem
            ) { Text(tr("兑换"), fontSize = 13.sp) }
        }
        Hairline()

        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { pwDlg = true }) { Text(tr("修改密码"), fontSize = 14.sp) }
        }
        Hairline()

        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = {
                scope.launch {
                    Api.logout(ctx)
                    Prefs.setAuthToken(ctx, null)
                    com.looka.app.data.PlanState.clear(ctx)
                    vm.bumpSettings()
                    toast(ctx, tr("已退出登录（本机数据保留）"))
                    onChanged()
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp)
        ) { Text(tr("退出登录"), color = HolidayRed, fontSize = 14.sp) }
        TextButton(
            onClick = { delDlg = true },
            modifier = Modifier.padding(horizontal = 8.dp)
        ) { Text(tr("注销账号（删除云端数据）"), color = GrayText, fontSize = 12.sp) }
        Spacer(Modifier.height(40.dp))
    }

    if (bindDlg) {
        var email by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { bindDlg = false },
            title = { Text(tr("绑定找回邮箱"), fontSize = 17.sp) },
            text = {
                Column {
                    Text(tr("会向该邮箱发送验证链接，点过才算绑定成功；之后忘记密码可用它找回。"),
                        fontSize = 12.sp, color = GrayText, lineHeight = 19.sp)
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text(tr("邮箱")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    msg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            msg = try {
                                Api.bindEmail(ctx, email.trim()).optString("message", tr("已发送"))
                            } catch (e: Exception) { e.message ?: tr("发送失败") }
                            busy = false
                            onChanged()
                        }
                    },
                    enabled = email.isNotBlank() && !busy
                ) { Text(tr("发送验证邮件")) }
            },
            dismissButton = { TextButton(onClick = { bindDlg = false }) { Text(tr("关闭"), color = GrayText) } },
            containerColor = Color.White
        )
    }

    if (pwDlg) {
        var oldPw by remember { mutableStateOf("") }
        var newPw by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pwDlg = false },
            title = { Text(tr("修改密码"), fontSize = 17.sp) },
            text = {
                Column {
                    Text(tr("修改后对使用同一账号的服务同时生效，其他设备将被退出。"),
                        fontSize = 12.sp, color = GrayText)
                    OutlinedTextField(value = oldPw, onValueChange = { oldPw = it },
                        label = { Text(tr("当前密码")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = newPw, onValueChange = { newPw = it },
                        label = { Text(tr("新密码（至少 6 位）")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    msg?.let { Text(it, fontSize = 12.sp, color = HolidayRed,
                        modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = Api.changePassword(ctx, oldPw, newPw)
                                toast(ctx, r.optString("message", tr("密码已修改")))
                                pwDlg = false
                            } catch (e: Exception) {
                                msg = e.message ?: tr("修改失败")
                            } finally { busy = false }
                        }
                    },
                    enabled = oldPw.isNotBlank() && newPw.length >= 6 && !busy
                ) { Text(tr("确认修改")) }
            },
            dismissButton = { TextButton(onClick = { pwDlg = false }) { Text(tr("取消"), color = GrayText) } },
            containerColor = Color.White
        )
    }

    if (delDlg) {
        var pw by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { delDlg = false },
            title = { Text(tr("注销账号？"), fontSize = 17.sp, color = HolidayRed) },
            text = {
                Column {
                    Text(tr("将永久删除 Looka 云端全部数据（本机数据保留）。登录凭证由统一账号服务管理，将予保留。此操作不可撤销。"),
                        fontSize = 13.sp, lineHeight = 20.sp)
                    OutlinedTextField(value = pw, onValueChange = { pw = it },
                        label = { Text(tr("输入密码确认")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    msg?.let { Text(it, fontSize = 12.sp, color = HolidayRed,
                        modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = Api.deleteAccount(ctx, pw)
                                Prefs.setAuthToken(ctx, null)
                                com.looka.app.data.PlanState.clear(ctx)
                                vm.bumpSettings()
                                toast(ctx, r.optString("message", tr("已注销")))
                                delDlg = false
                                onChanged()
                            } catch (e: Exception) {
                                msg = e.message ?: tr("注销失败")
                            } finally { busy = false }
                        }
                    },
                    enabled = pw.isNotBlank() && !busy
                ) { Text(tr("确认注销"), color = HolidayRed) }
            },
            dismissButton = { TextButton(onClick = { delDlg = false }) { Text(tr("取消"), color = GrayText) } },
            containerColor = Color.White
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = GrayText)
    }
}

/** F-7 降级说明（§50 六）：「你保留了」必须在前 —— 顺序反了，用户读到的就是被剥夺 */
@Composable
private fun KeepLoseDialog(onDismiss: () -> Unit) {
    val keeps = listOf(
        tr("全部内容和数据"), tr("做过的主题，继续能用"),
        tr("日历 · 待办 · 笔记 · 日记"), tr("提醒和闹钟"),
        tr("云同步和数据导出")
    )
    val loses = listOf(
        tr("AI 不限次（改为每天 10 次）"), tr("生成新主题 / 新表情包"),
        tr("更聪明的模型"), tr("年度回顾长图")
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("知道了")) }
        },
        title = { Text(tr("Pro 到期后"), fontSize = 16.sp) },
        text = {
            Column {
                Text(tr("你保留了"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                keeps.forEach { Text("· $it", fontSize = 12.sp, color = GrayText) }
                Spacer(Modifier.height(10.dp))
                Text(tr("暂时用不了了"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                loses.forEach { Text("· $it", fontSize = 12.sp, color = GrayText) }

            }
        }
    )
}
