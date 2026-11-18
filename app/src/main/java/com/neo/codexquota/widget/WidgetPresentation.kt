package com.neo.codexquota.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

internal enum class QuotaTone { Red, Yellow, Green, Unknown }

internal fun quotaTone(remaining: Double?): QuotaTone = when {
    remaining == null || !remaining.isFinite() -> QuotaTone.Unknown
    remaining < 30.0 -> QuotaTone.Red
    remaining <= 70.0 -> QuotaTone.Yellow
    else -> QuotaTone.Green
}

internal fun widgetRelativeTime(timestamp: Long?, now: Long): String = when {
    timestamp == null -> "—"
    timestamp <= now -> "待刷新"
    timestamp - now < 3_600_000 -> "${ceil((timestamp - now) / 60_000.0).toLong()}分钟后"
    timestamp - now < 86_400_000 -> "${ceil((timestamp - now) / 3_600_000.0).toLong()}小时后"
    else -> "${ceil((timestamp - now) / 86_400_000.0).toLong()}天后"
}

internal fun widgetResetTime(timestamp: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestamp == null) return "—"
    val absolute = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.CHINA)
        .withZone(zone).format(Instant.ofEpochMilli(timestamp))
    return "$absolute · ${widgetRelativeTime(timestamp, now)}"
}

internal data class WidgetGroupLayout(
    val titleSp: Float,
    val percentSp: Float,
    val timeSp: Float,
    val barHeight: Float,
    val inset: Float,
    val groupGap: Float,
    val itemGap: Float,
    val abbreviated: Boolean = false,
    val showTime: Boolean = true,
    val fullTime: Boolean = false,
)

// 用真实字体测量选择最大的可用档位，并为 100% 预留空间，避免数值变化挤掉标题。
internal fun widgetGroupLayout(
    width: Float,
    height: Float,
    labels: List<String>,
    fullTimes: List<String>,
    textWidth: (String, Float, Boolean) -> Float,
    textHeight: (Float, Boolean) -> Float,
    statusIconWidth: Float = 16f,
): WidgetGroupLayout {
    val regular = WidgetGroupLayout(14f, 22f, 11f, 10f, 10f, 12f, 3f)
    val compact = WidgetGroupLayout(12f, 18f, 10f, 10f, 10f, 8f, 2f)
    val micro = WidgetGroupLayout(11f, 16f, 9f, 8f, 6f, 4f, 2f)
    val candidates = listOf(
        regular, regular.copy(groupGap = 8f, itemGap = 2f), compact, micro,
        micro.copy(abbreviated = true), micro.copy(abbreviated = true, showTime = false),
    )
    val result = candidates.firstOrNull { layout ->
        val titles = if (layout.abbreviated) listOf("5H", "周") else labels
        val headerWidth = titles.maxOf { textWidth(it, layout.titleSp, true) } +
            textWidth("100%", layout.percentSp, true) + 4f + if (!layout.showTime) statusIconWidth else 0f
        val headerHeight = maxOf(textHeight(layout.titleSp, true), textHeight(layout.percentSp, true))
        val groupHeight = headerHeight + layout.itemGap + layout.barHeight +
            if (layout.showTime) layout.itemGap + maxOf(12f, textHeight(layout.timeSp, false)) else 0f
        headerWidth <= width - layout.inset * 2 &&
            groupHeight * 2 + layout.groupGap + layout.inset * 2 <= height
    } ?: candidates.last()
    val fullTime = result.showTime && fullTimes.all {
        textWidth(it, result.timeSp, false) + 1f <= width - result.inset * 2
    }
    return result.copy(fullTime = fullTime)
}
