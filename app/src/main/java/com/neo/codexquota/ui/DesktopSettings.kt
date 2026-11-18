package com.neo.codexquota.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neo.codexquota.data.AppState

@Composable
fun DesktopSettings(state: AppState, onAccount: (String) -> Unit, onInterval: (Int?) -> Unit) {
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    var showIntervals by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf("30") }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("桌面设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("默认账号", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { showAccounts = true }, enabled = state.configured && state.accounts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) {
                Text(state.defaultAccount?.let(state::label) ?: "选择默认账号", maxLines = 2)
            }
            if (state.configured && state.defaultAccount == null) {
                Text("请选择可用账号；原账号被移除或禁用后不会自动切换。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("自动刷新间隔", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { showIntervals = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.refreshIntervalMinutes?.let { "$it 分钟" } ?: "关闭（仅手动刷新）")
            }
            Text("所有桌面小部件使用同一默认账号。自动刷新最短 5 分钟，可能受 HyperOS 省电影响而延后。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showAccounts) AlertDialog(onDismissRequest = { showAccounts = false }, title = { Text("选择默认账号") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                state.accounts.forEach { account ->
                    Row(Modifier.fillMaxWidth().selectable(selected = account.id == state.defaultAccountId,
                        enabled = !account.disabled, role = Role.RadioButton,
                        onClick = { onAccount(account.id); showAccounts = false }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = account.id == state.defaultAccountId, onClick = null, enabled = !account.disabled)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(state.label(account), maxLines = 2)
                            if (state.label(account) != account.displayName) Text(account.displayName, style = MaterialTheme.typography.bodySmall)
                            if (account.disabled) Text("已禁用", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showAccounts = false }) { Text("关闭") } })
    if (showIntervals) AlertDialog(onDismissRequest = { showIntervals = false }, title = { Text("自动刷新间隔") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf<Int?>(5, 10, 15, 30, 60, 120, null).forEach { minutes ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.refreshIntervalMinutes == minutes,
                        role = Role.RadioButton, onClick = { onInterval(minutes); showIntervals = false })
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.refreshIntervalMinutes == minutes, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(minutes?.let { "$it 分钟" } ?: "关闭自动刷新")
                    }
                }
                TextButton(onClick = {
                    custom = state.refreshIntervalMinutes?.toString() ?: "30"
                    showIntervals = false
                    showCustom = true
                }) { Text("自定义分钟数") }
            }
        }, confirmButton = { TextButton(onClick = { showIntervals = false }) { Text("取消") } })
    if (showCustom) {
        val minutes = custom.toIntOrNull()?.takeIf { it >= 5 }
        AlertDialog(onDismissRequest = { showCustom = false }, title = { Text("自定义刷新间隔") },
            text = {
                OutlinedTextField(custom, { custom = it }, label = { Text("分钟数") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = minutes == null, supportingText = { Text("请输入至少 5 的整数") })
            }, confirmButton = {
                TextButton(onClick = { onInterval(minutes); showCustom = false }, enabled = minutes != null) { Text("保存") }
            }, dismissButton = { TextButton(onClick = { showCustom = false }) { Text("取消") } })
    }
}
