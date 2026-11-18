package com.neo.codexquota.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class QuotaRepository(
    private val store: StateStore,
    private val vault: KeyVault,
    private val api: ProxyApi,
    persistenceScope: CoroutineScope,
) {
    val states = store.states
    private val mutex = Mutex()
    private val draftWriter = ConnectionDraftWriter(persistenceScope) { server, key ->
        val encrypted = withContext(Dispatchers.IO) { if (key.isEmpty()) "" else vault.encrypt(key) }
        store.update { it.copy(connectionDraft = ConnectionDraft(server, encrypted)) }
    }

    fun saveConnectionDraft(server: String, key: String) = draftWriter.save(server, key)

    suspend fun awaitConnectionDraftWrites() = draftWriter.awaitPending()

    suspend fun restoreDraftKey(draft: ConnectionDraft): String = withContext(Dispatchers.IO) {
        try {
            if (draft.encryptedKey.isEmpty()) "" else vault.decrypt(draft.encryptedKey)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw ApiException("连接草稿密钥无法解密，地址已保留，请重新填写管理密钥")
        }
    }

    suspend fun connect(address: String, keyInput: String): Int = mutex.withLock {
        val server = ProxyApi.normalizeServer(address)
        val previous = states.first()
        val key = keyInput.trim().ifBlank {
            if (previous.server != server) throw ApiException("更换服务器时请填写对应管理密钥")
            decrypt(previous)
        }
        if (key.isBlank()) throw ApiException("请填写管理密钥")
        if (key.any { it.code < 32 || it.code > 126 }) throw ApiException("管理密钥包含不支持的字符")
        val accounts = api.accounts(server, key)
        val encrypted = vault.encrypt(key)
        store.update { current ->
            val same = current.server == server
            current.copy(
                server = server, encryptedKey = encrypted, accounts = accounts,
                connectionDraft = null,
                defaultAccountId = if (same) current.defaultAccountId else accounts.firstOrNull { !it.disabled }?.id,
                aliases = if (same) current.aliases else emptyMap(),
                snapshots = if (same) current.snapshots.filterKeys { id -> accounts.any { it.id == id } } else emptyMap(),
                globalError = null,
                lastAttempt = if (same) current.lastAttempt else 0,
            )
        }
        accounts.size
    }

    suspend fun setDefaultAccount(id: String) {
        store.update { current ->
            require(current.accounts.any { it.id == id && !it.disabled }) { "该账号已移除或禁用，请重新选择" }
            current.copy(defaultAccountId = id)
        }
    }

    suspend fun setRefreshInterval(minutes: Int?) {
        validateRefreshInterval(minutes)
        store.update { it.copy(refreshIntervalMinutes = minutes) }
    }

    suspend fun setAccountAlias(id: String, alias: String) {
        store.update { current ->
            current.copy(
                aliases = current.aliases + (id to alias.trim().take(64)),
            )
        }
    }

    suspend fun refresh(allAccounts: Boolean = false): Boolean = mutex.withLock {
        val initial = states.first()
        if (!initial.configured) return@withLock true
        store.update { it.copy(lastAttempt = System.currentTimeMillis()) }
        val key: String
        val accounts: List<QuotaAccount>
        try {
            key = decrypt(initial)
            accounts = api.accounts(initial.server, key)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            store.update { it.copy(globalError = friendlyError(error)) }
            return@withLock false
        }
        val knownIds = accounts.map { it.id }.toSet()
        store.update { current -> current.copy(
            accounts = accounts,
            snapshots = current.snapshots.filterKeys { it in knownIds }, globalError = null,
        ) }
        val selected = states.first().defaultAccountId
        val gate = Semaphore(3)
        val snapshots = coroutineScope {
            accounts.filter { !it.disabled && (allAccounts || it.id == selected) }.map { account -> async {
                gate.withPermit {
                    try {
                        api.quota(initial.server, key, account)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // 查询失败时保留上次成功数据及其时间，避免把旧额度显示成最新额度。
                        initial.snapshots[account.id]?.copy(error = friendlyError(error))
                            ?: QuotaSnapshot(account.id, error = friendlyError(error))
                    }
                }
            } }.awaitAll()
        }
        store.update { current -> current.copy(snapshots = current.snapshots + snapshots.associateBy { it.accountId }) }
        snapshots.none { it.error != null }
    }

    private fun decrypt(state: AppState): String = try {
        if (state.encryptedKey.isBlank()) throw ApiException("请填写管理密钥")
        vault.decrypt(state.encryptedKey)
    } catch (error: ApiException) { throw error }
    catch (error: Exception) { throw ApiException("本地密钥无法解密，请重新填写管理密钥") }
}

fun friendlyError(error: Throwable): String = when (error) {
    is ApiException -> error.message ?: "请求失败"
    is SocketTimeoutException -> "连接超时，请稍后刷新"
    is UnknownHostException -> "无法解析服务器地址，请检查网络或域名"
    is SSLException -> "HTTPS 证书验证失败，请检查服务器证书"
    is java.io.IOException -> "无法连接服务，请检查网络和服务器地址"
    else -> "操作失败，请重试或重新配置连接"
}
