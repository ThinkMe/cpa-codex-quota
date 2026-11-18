package com.neo.codexquota.data

import kotlinx.serialization.json.*
import java.time.Instant

internal val quotaJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.bool(key: String): Boolean = text(key).equals("true", true)
internal fun JsonObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull {
    text(it).toDoubleOrNull()?.takeIf(Double::isFinite)
}

object QuotaParser {
    fun accounts(body: String): List<QuotaAccount> {
        val root = quotaJson.parseToJsonElement(body) as? JsonObject ?: error("账号列表格式不正确")
        val files = root["files"] as? JsonArray ?: error("账号列表缺少 files 字段")
        return files.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            if (!(entry.text("provider").ifBlank { entry.text("type") }).equals("codex", true)) return@mapNotNull null
            val name = entry.text("name").ifBlank { entry.text("id") }
            val index = entry.text("auth_index")
            if (name.isBlank()) return@mapNotNull null
            val claims = entry.obj("id_token")
            QuotaAccount(
                id = "$name::$index",
                name = name,
                authIndex = index,
                accountId = entry.text("chatgpt_account_id").ifBlank { claims?.text("chatgpt_account_id").orEmpty() },
                email = entry.text("email"),
                plan = entry.text("plan_type").ifBlank { claims?.text("plan_type").orEmpty() },
                disabled = entry.bool("disabled"),
            )
        }.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }
    }

    fun usage(accountId: String, body: String, fetchedAt: Long): QuotaSnapshot {
        val root = quotaJson.parseToJsonElement(body) as? JsonObject ?: error("额度响应格式不正确")
        val rate = root.obj("rate_limit") ?: root.obj("rateLimit")
        val primary = rate?.obj("primary_window") ?: rate?.obj("primaryWindow")
        val secondary = rate?.obj("secondary_window") ?: rate?.obj("secondaryWindow")
        val windows = listOfNotNull(
            primary?.let { window(it, "主额度", fetchedAt) },
            secondary?.let { window(it, "次额度", fetchedAt) },
        )
        return QuotaSnapshot(
            accountId = accountId,
            windows = windows,
            plan = root.text("plan_type").ifBlank { root.text("planType") },
            fetchedAt = fetchedAt,
            error = if (windows.isEmpty()) "接口未提供主额度窗口" else null,
        )
    }

    private fun window(source: JsonObject, fallback: String, fetchedAt: Long): QuotaWindow {
        val duration = source.number("limit_window_seconds", "limitWindowSeconds")?.toLong()?.takeIf { it > 0 }
        val used = source.number("used_percent", "usedPercent")
        val remaining = used?.let { (100 - it).coerceIn(0.0, 100.0) }
        val rawReset = source.text("reset_at").ifBlank { source.text("resetAt") }
        val numericReset = rawReset.toDoubleOrNull()?.takeIf(Double::isFinite)?.takeIf { it > 0 }
        val reset = numericReset?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
            ?: runCatching { Instant.parse(rawReset).toEpochMilli() }.getOrNull()
            ?: source.number("reset_after_seconds", "resetAfterSeconds")?.takeIf { it >= 0 }
                ?.let { fetchedAt + (it * 1000).toLong() }
        val label = when (duration) {
            18_000L -> "5 小时"
            604_800L -> "每周"
            86_400L -> "每天"
            null -> fallback
            else -> if (duration % 3600 == 0L) "${duration / 3600} 小时" else "${duration / 60} 分钟"
        }
        return QuotaWindow(label, remaining, reset, duration)
    }
}
