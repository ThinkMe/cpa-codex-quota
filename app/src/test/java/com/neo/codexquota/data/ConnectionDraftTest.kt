package com.neo.codexquota.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionDraftTest {
    @Test fun `草稿恢复不改变正式连接和额度缓存`() {
        val original = AppState(server = "https://working.example.com", encryptedKey = "正式密文",
            snapshots = mapOf("a" to QuotaSnapshot("a", fetchedAt = 123)),
            connectionDraft = ConnectionDraft("http://wrong.example.com/", "草稿密文"))
        assertEquals(original, StateCodec.decode(StateCodec.encode(original)))
        assertTrue(StateCodec.decode(StateCodec.encode(original)).configured)
    }

    @Test fun `旧数据没有草稿时仍正常恢复`() {
        val restored = StateCodec.decode("""{"server":"https://example.com","encryptedKey":"旧密文"}""")
        assertTrue(restored.configured)
        assertNull(restored.connectionDraft)
    }

    @Test fun `空输入草稿与没有草稿不同且不会成为正式配置`() {
        val original = AppState(connectionDraft = ConnectionDraft("", ""))
        val restored = StateCodec.decode(StateCodec.encode(original))
        assertEquals(original, restored)
        assertFalse(restored.configured)
        assertNotNull(restored.connectionDraft)
    }

    @Test fun `成功清除草稿不影响正式密钥`() {
        val saved = AppState(server = "https://example.com", encryptedKey = "正式密文",
            connectionDraft = ConnectionDraft("错误地址", "草稿密文"))
        val restored = StateCodec.decode(StateCodec.encode(saved.copy(connectionDraft = null)))
        assertNull(restored.connectionDraft)
        assertEquals("正式密文", restored.encryptedKey)
    }

    @Test fun `快速编辑串行保存最后一次输入并允许等待全部落盘`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val writes = mutableListOf<Pair<String, String>>()
            val writer = ConnectionDraftWriter(scope) { server, key ->
                if (server == "first") gate.await()
                writes += server to key
            }
            writer.save("first", "a")
            val last = writer.save("last", "b")
            runCurrent()
            assertTrue(writes.isEmpty())
            assertFalse(last.isCompleted)
            gate.complete(Unit)
            writer.awaitPending()
            assertEquals(listOf("first" to "a", "last" to "b"), writes)
        } finally { scope.cancel() }
    }

    @Test fun `前一次写入失败后修改仍能保存`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val writes = mutableListOf<String>()
            val writer = ConnectionDraftWriter(scope) { server, _ ->
                if (server == "failed") throw java.io.IOException("模拟存储失败")
                writes += server
            }
            val failed = writer.save("failed", "a")
            writer.save("recovered", "b")
            writer.awaitPending()
            assertTrue(failed.isCancelled)
            assertEquals(listOf("recovered"), writes)
        } finally { scope.cancel() }
    }
}
