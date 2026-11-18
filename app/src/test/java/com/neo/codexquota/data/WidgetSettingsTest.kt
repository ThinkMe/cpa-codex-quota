package com.neo.codexquota.data

import org.junit.Assert.*
import org.junit.Test

class WidgetSettingsTest {
    private val legacy = """{"server":"https://example.com","encryptedKey":"密文","selectedIds":["disabled","b","a"],
        "accounts":[{"id":"disabled","disabled":true},{"id":"a"},{"id":"b"}],
        "aliases":{"a":"日常"},"snapshots":[{"accountId":"a","fetchedAt":123}]}"""

    @Test fun `旧多选迁移为账号列表中首个已选可用账号`() {
        val state = StateCodec.decode(legacy)
        assertEquals("a", state.defaultAccountId)
        assertEquals(30, state.refreshIntervalMinutes)
        assertEquals("密文", state.encryptedKey)
        assertEquals("日常", state.aliases["a"])
        assertEquals(123L, state.snapshots["a"]?.fetchedAt)
    }

    @Test fun `旧多选没有可用项时采用首个可用账号`() {
        assertEquals("a", StateCodec.decode(legacy.replace("[\"disabled\",\"b\",\"a\"]", "[]")).defaultAccountId)
    }

    @Test fun `新配置明确未选择时不重复迁移或自动换号`() {
        val state = AppState(accounts = listOf(QuotaAccount("a", "a", "a")))
        val restored = StateCodec.decode(StateCodec.encode(state))
        assertNull(restored.defaultAccountId)
        assertNull(restored.defaultAccount)
    }

    @Test fun `默认账号被删除或禁用后不显示其他账号`() {
        val account = QuotaAccount("a", "a", "a")
        val state = AppState(defaultAccountId = "a", accounts = listOf(account))
        assertEquals(account, state.defaultAccount)
        assertNull(state.copy(accounts = listOf(account.copy(disabled = true))).defaultAccount)
        assertNull(state.copy(accounts = listOf(QuotaAccount("b", "b", "b"))).defaultAccount)
    }

    @Test fun `关闭自动刷新与自定义间隔可持久化恢复`() {
        for (minutes in listOf(null, 5, 7, 10, 14, 15, 30, 47, 120)) {
            val state = AppState(defaultAccountId = "a", refreshIntervalMinutes = minutes)
            assertEquals(state, StateCodec.decode(StateCodec.encode(state)))
        }
    }

    @Test fun `非法存储间隔恢复为默认值而不是触发过快刷新`() {
        for (value in listOf("0", "4", "-1", "\"bad\"", "99999999999999")) {
            assertEquals(30, StateCodec.decode("""{"refreshIntervalMinutes":$value}""").refreshIntervalMinutes)
        }
    }

    @Test fun `自动刷新校验允许关闭且拒绝小于五分钟`() {
        validateRefreshInterval(null)
        validateRefreshInterval(5)
        validateRefreshInterval(14)
        validateRefreshInterval(15)
        validateRefreshInterval(47)
        for (value in listOf(-1, 0, 1, 4)) {
            try {
                validateRefreshInterval(value)
                fail("不能允许小于五分钟")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `额度按时长识别不依赖窗口顺序且缺失不冒用其他窗口`() {
        val week = QuotaWindow("每周", 40.0, null, 604_800)
        val fiveHours = QuotaWindow("5 小时", 75.0, null, 18_000)
        val snapshot = QuotaSnapshot("a", windows = listOf(week, fiveHours))
        assertEquals(fiveHours, snapshot.windowFor(18_000))
        assertEquals(week, snapshot.windowFor(604_800))
        assertNull(snapshot.copy(windows = listOf(week)).windowFor(18_000))
        assertNull((null as QuotaSnapshot?).windowFor(18_000))
    }
}
