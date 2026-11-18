package com.neo.codexquota.widget

import com.neo.codexquota.data.*
import org.junit.Assert.*
import org.junit.Test

class WidgetFeedbackTest {
    private val account = QuotaAccount("a", "日常", "1")
    private val state = AppState(server = "https://example.com", encryptedKey = "密文",
        defaultAccountId = "a", accounts = listOf(account))
    private val feedback = WidgetFeedback("request-1", "a", state.server, 10_000, 13_000)

    @Test fun `本轮持久结果在周期任务重新排队后仍匹配点击`() {
        val bound = feedback.copy(workId = "periodic-work")
        val result = WidgetRefreshResult("periodic-work", "a", state.server, true, 10_100)
        assertTrue(result.matches(bound))
        assertTrue(result.copy(success = false).matches(bound))
    }

    @Test fun `同一周期任务的上一轮结果不能结束新点击`() {
        val bound = feedback.copy(workId = "periodic-work")
        val result = WidgetRefreshResult("periodic-work", "a", state.server, true, 9_999)
        assertFalse(result.matches(bound))
        assertFalse(result.copy(completedAt = 10_100).matches(bound.copy(startedAt = 10_101)))
    }

    @Test fun `其他任务账号或服务器的结果不会结束本轮刷新`() {
        val bound = feedback.copy(workId = "work-1")
        val result = WidgetRefreshResult("work-1", "a", state.server, true, 10_100)
        assertFalse(result.copy(workId = "work-2").matches(bound))
        assertFalse(result.copy(accountId = "b").matches(bound))
        assertFalse(result.copy(server = "https://other.example.com").matches(bound))
        assertFalse(result.matches(bound.copy(workId = "")))
    }

    @Test fun `到期成功提示收起但失败仍保留重试入口`() {
        assertEquals(FeedbackPhase.Success, visibleFeedback(feedback.copy(phase = FeedbackPhase.Success), state, 12_999)?.phase)
        assertNull(visibleFeedback(feedback.copy(phase = FeedbackPhase.Success), state, 13_000))
        assertEquals(FeedbackPhase.Failure, visibleFeedback(feedback.copy(phase = FeedbackPhase.Failure), state, 12_999)?.phase)
        assertEquals(FeedbackPhase.Error, visibleFeedback(feedback.copy(phase = FeedbackPhase.Failure), state, 13_000)?.phase)
    }

    @Test fun `刷新状态超时或重启后不会永久遮挡额度`() {
        assertEquals(FeedbackPhase.Refreshing, visibleFeedback(feedback, state, 12_999)?.phase)
        assertEquals(FeedbackPhase.Error, visibleFeedback(feedback, state, 13_000)?.phase)
        assertNull(visibleFeedback(feedback, state, 8_000))
    }

    @Test fun `切换账号服务器或失去配置时丢弃旧请求反馈`() {
        assertNull(visibleFeedback(feedback, state.copy(defaultAccountId = "b"), 11_000))
        assertNull(visibleFeedback(feedback, state.copy(server = "https://other.example.com"), 11_000))
        assertNull(visibleFeedback(feedback, state.copy(encryptedKey = ""), 11_000))
        assertNull(visibleFeedback(feedback, state.copy(accounts = listOf(account.copy(disabled = true))), 11_000))
    }

    @Test fun `后续刷新成功才清除原有失败提示`() {
        val error = feedback.copy(phase = FeedbackPhase.Error, completedAt = 12_000)
        fun withSnapshot(fetchedAt: Long, message: String? = null) = state.copy(
            snapshots = mapOf("a" to QuotaSnapshot("a", fetchedAt = fetchedAt, error = message)))
        assertNotNull(visibleFeedback(error, withSnapshot(11_999), 14_000))
        assertNotNull(visibleFeedback(error, withSnapshot(13_000, "连接失败"), 14_000))
        assertNull(visibleFeedback(error, withSnapshot(13_000), 14_000))
        assertNotNull(visibleFeedback(error, withSnapshot(13_000).copy(globalError = "服务器异常"), 14_000))
    }
}
