package com.neo.codexquota.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

// 写入由应用生命周期托管，按输入顺序完成，离开页面不会取消尚未完成的保存。
internal class ConnectionDraftWriter(
    private val scope: CoroutineScope,
    private val persist: suspend (String, String) -> Unit,
) {
    private var pending: Deferred<Unit>? = null

    @Synchronized
    fun save(server: String, key: String): Deferred<Unit> {
        val previous = pending
        return scope.async {
            // 上一次失败不应阻止用户修改后再次保存。
            previous?.join()
            persist(server, key)
        }.also { pending = it }
    }

    suspend fun awaitPending() {
        val latest = synchronized(this) { pending }
        latest?.await()
    }
}
