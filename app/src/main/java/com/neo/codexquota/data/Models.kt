package com.neo.codexquota.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class QuotaAccount(
    val id: String,
    val name: String,
    val authIndex: String,
    val accountId: String = "",
    val email: String = "",
    val plan: String = "",
    val disabled: Boolean = false,
) {
    val displayName: String get() = email.ifBlank { name }
}

data class QuotaWindow(
    val label: String,
    val remainingPercent: Double?,
    val resetAtMillis: Long?,
    val durationSeconds: Long?,
)

data class QuotaSnapshot(
    val accountId: String,
    val windows: List<QuotaWindow> = emptyList(),
    val plan: String = "",
    val fetchedAt: Long = 0,
    val error: String? = null,
)

data class ConnectionDraft(val server: String, val encryptedKey: String)

data class AppState(
    val server: String = "",
    val encryptedKey: String = "",
    val defaultAccountId: String? = null,
    val refreshIntervalMinutes: Int? = 30,
    val aliases: Map<String, String> = emptyMap(),
    val accounts: List<QuotaAccount> = emptyList(),
    val snapshots: Map<String, QuotaSnapshot> = emptyMap(),
    val lastAttempt: Long = 0,
    val globalError: String? = null,
    val connectionDraft: ConnectionDraft? = null,
) {
    val configured: Boolean get() = server.isNotBlank() && encryptedKey.isNotBlank()
    val defaultAccount: QuotaAccount? get() = accounts.firstOrNull { it.id == defaultAccountId && !it.disabled }
    fun label(account: QuotaAccount): String = aliases[account.id]?.takeIf { it.isNotBlank() } ?: account.displayName
}

fun validateRefreshInterval(minutes: Int?) {
    require(minutes == null || minutes >= 5) { "自动刷新间隔至少为 5 分钟" }
}

fun QuotaSnapshot?.windowFor(durationSeconds: Long): QuotaWindow? =
    this?.windows?.firstOrNull { it.durationSeconds == durationSeconds }

fun percentageLabel(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "—"

fun resetLabel(timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null) return "重置时间未知"
    if (timestamp <= now) return "已到重置时间，待刷新"
    val minutes = ((timestamp - now + 59_999) / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "${minutes} 分钟后重置"
        minutes < 1_440 -> "${minutes / 60} 小时 ${minutes % 60} 分钟后重置"
        else -> "${minutes / 1_440} 天 ${(minutes % 1_440) / 60} 小时后重置"
    }
}

fun timeLabel(timestamp: Long): String = if (timestamp <= 0) "尚未更新" else
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

fun absoluteResetLabel(timestamp: Long?): String = timestamp?.let {
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(it))
} ?: "—"
