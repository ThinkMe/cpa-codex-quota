package com.neo.codexquota.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neo.codexquota.data.*
import com.neo.codexquota.widget.QuotaWidgetReceiver

@Composable
fun QuotaScreen(requestedAccount: String?, onDismissAccount: () -> Unit, model: QuotaViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val connection by model.connectionInput.collectAsStateWithLifecycle()
    var editingAccount by remember { mutableStateOf<QuotaAccount?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(requestedAccount, state.accounts) {
        if (requestedAccount != null) editingAccount = state.accounts.firstOrNull { it.id == requestedAccount }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CODEX / 用量监测", fontSize = 11.sp, letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("余量，一目了然", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showHelp = true }) { Text("使用说明") }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp)) {
                        Text(if (state.configured) "我的 Codex 账号" else "从桌面掌握可用额度",
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (state.defaultAccount != null) "1" else "0", fontSize = 48.sp, lineHeight = 52.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            Text(" 个显示中 / 共 ${state.accounts.size} 个", modifier = Modifier.padding(bottom = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(state.refreshIntervalMinutes?.let { "每 $it 分钟自动尝试更新 · 支持手动刷新" }
                            ?: "自动刷新已关闭 · 支持手动刷新", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                val manager = AppWidgetManager.getInstance(context)
                                if (manager.isRequestPinAppWidgetSupported) {
                                    val accepted = manager.requestPinAppWidget(ComponentName(context, QuotaWidgetReceiver::class.java), null, null)
                                    pinMessage = if (accepted) "请在桌面弹窗中确认添加" else "请长按桌面，在小部件列表中添加 Codex 余量"
                                } else pinMessage = "请长按桌面，在小部件列表中添加 Codex 余量"
                            }, enabled = state.configured) { Text("添加到桌面") }
                            OutlinedButton(onClick = model::refresh, enabled = state.configured && !busy) {
                                Text(if (busy) "正在更新…" else "刷新额度")
                            }
                        }
                        pinMessage?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 12.sp) }
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (message != null) item {
                StatusCard(message!!)
            }
            if (state.globalError != null) item {
                StatusCard("更新失败：${state.globalError}\n显示的额度为上次成功获取的数据。", error = true)
            }
            item {
                DesktopSettings(state, model::selectDefaultAccount, model::setRefreshInterval)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("服务连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (state.configured) TextButton(onClick = { model.setConnectionExpanded(!connection.expanded) }) {
                        Text(if (connection.expanded) "收起" else "编辑连接")
                    }
                }
                if (!state.configured || connection.expanded) {
                    ConnectionForm(state, connection, busy, model::editConnection, model::connect)
                } else {
                    Text(state.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("账号与额度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("点击账号查看详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.accounts.isEmpty()) item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(if (state.configured) "还没有 Codex 账号" else "连接后显示账号额度", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(if (state.configured) "请先在 CLIProxyAPI 管理后台添加 Codex 账号，然后刷新。"
                            else "填写现有 CLIProxyAPI 的地址与管理密钥，即可获取账号列表。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(state, account, busy) { editingAccount = account }
            }
            item {
                Text("最近尝试更新：${timeLabel(state.lastAttempt)}\n各账号显示独立更新时间。自动更新可能受 HyperOS 省电限制。",
                    modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    editingAccount?.let { account ->
        AccountDialog(state, account, busy,
            onDismiss = { editingAccount = null; onDismissAccount() },
            onSave = { selected, alias ->
                model.saveAccount(account, selected, alias)
                editingAccount = null
                onDismissAccount()
            })
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
        title = { Text("使用说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 填写 CLIProxyAPI 服务地址与管理密钥，点击“保存并连接”。管理密钥与调用模型用的 API Key 不同。")
                Text("2. 在“桌面设置”选择默认账号和自动刷新间隔，所有小部件同步显示该账号。点击“添加到桌面”即可添加。")
                Text("3. 小部件仅显示 5 小时和每周剩余额度，可长按调整大小。桌面刷新只查询默认账号，App 内刷新查询全部未禁用账号。")
                Text("4. HyperOS 中可在应用信息中允许自启动，并将省电策略设为“不限制”，以减少自动更新延迟。无需开启常驻通知。")
                Text("5. 百分比表示额度窗口的剩余比例；重置时间以手机时区显示。网络异常时保留缓存，密钥只在本机加密保存。")
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("打开应用信息") }
            }
        }, confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } })
}

@Composable
private fun ConnectionForm(
    state: AppState, input: ConnectionInput, busy: Boolean,
    onEdit: (String, String) -> Unit, onConnect: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(input.server, { onEdit(it, input.key) }, Modifier.fillMaxWidth(), enabled = !busy && input.ready,
                label = { Text("服务器地址") }, placeholder = { Text("https://proxy.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("填写手机能访问的服务地址，例如 https://proxy.example.com 或 http://服务器IP:端口。不要填 localhost。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(input.key, { onEdit(input.server, it) }, Modifier.fillMaxWidth(), enabled = !busy && input.ready,
                label = { Text(if (state.configured) "管理密钥（留空沿用已保存密钥）" else "管理密钥") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("填写登录管理后台使用的密钥，不是模型 API Key，无需加 Bearer。支持 CLIProxyAPI v8.0.69。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("输入自动保存在本机，密钥加密保存；连接失败或关闭应用后仍可继续修改。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(),
                enabled = !busy && input.ready && input.server.isNotBlank() && (state.configured || input.key.isNotBlank())) {
                Text(if (busy) "正在连接…" else "保存并连接")
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(14.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountCard(state: AppState, account: QuotaAccount, busy: Boolean, onClick: () -> Unit) {
    val snapshot = state.snapshots[account.id]
    val selected = account.id == state.defaultAccountId
    Card(onClick = onClick, enabled = !busy, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.label(account), fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text((snapshot?.plan?.ifBlank { account.plan } ?: account.plan).ifBlank { "Codex" }.uppercase(),
                        fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (account.disabled) "已禁用" else if (selected) "桌面默认账号" else "其他账号",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (snapshot?.windows.isNullOrEmpty()) {
                Text(snapshot?.error ?: "点击“刷新额度”查询账号额度",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                snapshot!!.windows.forEach { window -> WindowDetail(window) }
            }
            if (snapshot?.error != null && snapshot.windows.isNotEmpty()) {
                Text("旧数据 · ${snapshot.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (snapshot != null && snapshot.fetchedAt > 0) {
                Text("更新于 ${timeLabel(snapshot.fetchedAt)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WindowDetail(window: QuotaWindow, absolute: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(window.label, style = MaterialTheme.typography.bodySmall)
            Text("剩余 ${percentageLabel(window.remainingPercent)}", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = if ((window.remainingPercent ?: 100.0) <= 10.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { ((window.remainingPercent ?: 0.0) / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp), gapSize = 0.dp, drawStopIndicator = {})
        Text(if (absolute) "${resetLabel(window.resetAtMillis)} · ${absoluteResetLabel(window.resetAtMillis)}"
            else resetLabel(window.resetAtMillis), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountDialog(state: AppState, account: QuotaAccount, busy: Boolean,
    onDismiss: () -> Unit, onSave: (Boolean, String) -> Unit) {
    var alias by remember(account.id) { mutableStateOf(state.aliases[account.id].orEmpty()) }
    var selected by remember(account.id) { mutableStateOf(account.id == state.defaultAccountId) }
    val snapshot = state.snapshots[account.id]
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账号详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(alias, { alias = it.take(64) }, label = { Text("本地显示名称（可选）") },
                    singleLine = true, enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { selected = true }, enabled = !busy && !account.disabled)
                    Text("设为桌面默认账号")
                }
                snapshot?.windows?.forEach { WindowDetail(it, absolute = true) }
                snapshot?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("更新于 ${timeLabel(snapshot?.fetchedAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
                Text("名称仅保存在本机，不会修改管理后台。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { onSave(selected, alias) }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
