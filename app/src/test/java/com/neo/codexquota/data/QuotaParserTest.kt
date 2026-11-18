package com.neo.codexquota.data

import org.junit.Assert.*
import org.junit.Test

class QuotaParserTest {
    @Test fun `账号列表只提取 Codex 并保留账户标识`() {
        val accounts = QuotaParser.accounts("""{"files":[
          {"name":"a.json","provider":"codex","auth_index":"01","email":"a@example.com",
           "id_token":{"chatgpt_account_id":"account-1","plan_type":"plus"}},
          {"name":"b.json","type":"codex","auth_index":"02","disabled":true},
          {"name":"c.json","provider":"claude"}
        ]}""")
        assertEquals(2, accounts.size)
        assertEquals("account-1", accounts[0].accountId)
        assertEquals("plus", accounts[0].plan)
        assertTrue(accounts[1].disabled)
    }

    @Test fun `已用额度转换为剩余百分比并识别窗口`() {
        val snapshot = QuotaParser.usage("a", """{"plan_type":"plus","rate_limit":{
          "primary_window":{"used_percent":25.5,"limit_window_seconds":18000,"reset_at":1800000000},
          "secondary_window":{"used_percent":"90","limit_window_seconds":604800,"reset_after_seconds":3600}
        }}""", 1_000_000)
        assertEquals(74.5, snapshot.windows[0].remainingPercent!!, 0.01)
        assertEquals(10.0, snapshot.windows[1].remainingPercent!!, 0.01)
        assertEquals("5 小时", snapshot.windows[0].label)
        assertEquals("每周", snapshot.windows[1].label)
        assertEquals(1_800_000_000_000L, snapshot.windows[0].resetAtMillis)
        assertEquals(4_600_000L, snapshot.windows[1].resetAtMillis)
    }

    @Test fun `缺失百分比保持未知并限制异常数值`() {
        val snapshot = QuotaParser.usage("a", """{"rate_limit":{
          "primary_window":{"reset_at":1800000000000},
          "secondary_window":{"used_percent":150}
        }}""", 100)
        assertNull(snapshot.windows[0].remainingPercent)
        assertEquals("—", percentageLabel(snapshot.windows[0].remainingPercent))
        assertEquals(0.0, snapshot.windows[1].remainingPercent!!, 0.0)
        assertEquals(1_800_000_000_000L, snapshot.windows[0].resetAtMillis)
    }

    @Test fun `负已用值限制为百分之百剩余`() {
        val snapshot = QuotaParser.usage("a", """{"rate_limit":{"primary_window":{"used_percent":-5}}}""", 0)
        assertEquals(100.0, snapshot.windows[0].remainingPercent!!, 0.0)
    }

    @Test fun `无主窗口返回未提供而不是满额`() {
        val snapshot = QuotaParser.usage("a", """{"plan_type":"free","rate_limit":null}""", 100)
        assertTrue(snapshot.windows.isEmpty())
        assertNotNull(snapshot.error)
    }

    @Test fun `兼容驼峰字段和 ISO 时间`() {
        val snapshot = QuotaParser.usage("a", """{"rateLimit":{"primaryWindow":{
          "usedPercent":"50","limitWindowSeconds":86400,"resetAt":"2026-11-16T00:00:00Z"
        }}}""", 0)
        assertEquals("每天", snapshot.windows[0].label)
        assertNotNull(snapshot.windows[0].resetAtMillis)
        assertEquals(50.0, snapshot.windows[0].remainingPercent!!, 0.0)
    }

    @Test fun `非有限百分比保持未知`() {
        val snapshot = QuotaParser.usage("a", """{"rate_limit":{"primary_window":{"used_percent":"NaN"}}}""", 0)
        assertNull(snapshot.windows[0].remainingPercent)
    }

    @Test fun `重置时间已过提示刷新而不是自动恢复满额`() {
        assertEquals("已到重置时间，待刷新", resetLabel(100, 200))
        assertEquals("重置时间未知", resetLabel(null, 200))
        assertEquals("1 分钟后重置", resetLabel(60_100, 200))
    }

    @Test fun `无账号返回空列表`() {
        assertTrue(QuotaParser.accounts("""{"files":[]}""").isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `错误账号响应不能当作空账号列表`() {
        QuotaParser.accounts("""{"error":"bad request"}""")
    }

    @Test fun `缓存恢复保留上次更新时间和错误`() {
        val account = QuotaAccount("a::1", "a", "1")
        val state = AppState(server = "https://example.com", encryptedKey = "加密密文", defaultAccountId = account.id,
            accounts = listOf(account), aliases = mapOf(account.id to "日常账号"),
            snapshots = mapOf(account.id to QuotaSnapshot(account.id,
                windows = listOf(QuotaWindow("5 小时", 70.0, 20_000, 18_000)), fetchedAt = 10_000,
                error = "网络不可用")), globalError = "连接超时")
        val encoded = StateCodec.encode(state)
        assertEquals(state, StateCodec.decode(encoded))
        assertFalse(encoded.contains("access_token"))
    }

    @Test fun `损坏缓存提示重新配置`() {
        val state = StateCodec.decode("{broken")
        assertFalse(state.configured)
        assertNotNull(state.globalError)
    }
}
