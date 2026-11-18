package com.neo.codexquota.widget

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun `剩余颜色使用三个连续区间并保留未知状态`() {
        for (value in listOf(0.0, 20.0, 29.999)) assertEquals(QuotaTone.Red, quotaTone(value))
        for (value in listOf(30.0, 50.0, 69.999, 70.0)) assertEquals(QuotaTone.Yellow, quotaTone(value))
        for (value in listOf(70.001, 75.0, 100.0)) assertEquals(QuotaTone.Green, quotaTone(value))
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(QuotaTone.Unknown, quotaTone(value))
        }
    }

    @Test fun `倒计时向上取整并在小时与天边界切换`() {
        val now = 1_000L
        assertEquals("1分钟后", widgetRelativeTime(now + 1, now))
        assertEquals("60分钟后", widgetRelativeTime(now + 3_599_999, now))
        assertEquals("1小时后", widgetRelativeTime(now + 3_600_000, now))
        assertEquals("2小时后", widgetRelativeTime(now + 3_600_001, now))
        assertEquals("24小时后", widgetRelativeTime(now + 86_399_999, now))
        assertEquals("1天后", widgetRelativeTime(now + 86_400_000, now))
        assertEquals("2天后", widgetRelativeTime(now + 86_400_001, now))
        assertEquals("—", widgetRelativeTime(null, now))
        assertEquals("待刷新", widgetRelativeTime(now, now))
        assertEquals("待刷新", widgetRelativeTime(now - 1, now))
    }

    @Test fun `完整时间按指定时区显示且不改变缓存日期`() {
        val timestamp = Instant.parse("2026-11-17T07:34:00Z").toEpochMilli()
        assertEquals("11/17 15:34 · 2小时后", widgetResetTime(timestamp, timestamp - 7_200_000, ZoneId.of("Asia/Shanghai")))
        assertEquals("11/17 07:34 · 待刷新", widgetResetTime(timestamp, timestamp, ZoneId.of("UTC")))
        assertEquals("—", widgetResetTime(null, timestamp))
    }

    private fun layout(width: Float, height: Float, scale: Float = 1f): WidgetGroupLayout =
        widgetGroupLayout(width, height, listOf("5H限额", "周限额"),
            listOf("11/17 15:34 · 60分钟后", "11/17 17:15 · 4小时后"),
            { text, sp, _ -> text.sumOf { if (it.code > 127) 1.0 else 0.56 }.toFloat() * sp * scale },
            { sp, _ -> sp * scale * 1.3f + 1f })

    @Test fun `真机尺寸优先大字号和全宽加粗轨道`() {
        val result = layout(151f, 167f)
        assertEquals(14f, result.titleSp)
        assertEquals(22f, result.percentSp)
        assertEquals(11f, result.timeSp)
        assertEquals(10f, result.barHeight)
        assertEquals(131f, 151f - result.inset * 2)
        assertFalse(result.abbreviated)
        assertTrue(result.showTime)
        assertFalse(result.fullTime)
    }

    @Test fun `大字体使用紧凑档保住完整标题百分比和时间`() {
        val result = layout(151f, 167f, 1.3f)
        assertEquals(12f, result.titleSp)
        assertEquals(18f, result.percentSp)
        assertFalse(result.abbreviated)
        assertTrue(result.showTime)
    }

    @Test fun `最小尺寸和极大字体逐步简写标题并隐藏时间`() {
        val minimum = layout(110f, 110f)
        assertEquals(11f, minimum.titleSp)
        assertEquals(8f, minimum.barHeight)
        assertTrue(minimum.showTime)
        val scaled = layout(110f, 110f, 1.3f)
        assertTrue(scaled.abbreviated)
        assertFalse(scaled.showTime)
    }

    @Test fun `完整时间是否展示以两组中最长文字为准`() {
        assertTrue(layout(320f, 180f).fullTime)
        val measured = widgetGroupLayout(151f, 167f, listOf("5H限额", "周限额"),
            listOf("短时间", "很长的完整时间"),
            { text, _, _ -> if (text == "很长的完整时间") 132f else 25f }, { _, _ -> 20f })
        assertFalse(measured.fullTime)
    }

    @Test fun `横向充足但高度较低时也会切换紧凑档`() {
        val result = layout(320f, 110f)
        assertEquals(11f, result.titleSp)
        assertEquals(8f, result.barHeight)
    }
}
