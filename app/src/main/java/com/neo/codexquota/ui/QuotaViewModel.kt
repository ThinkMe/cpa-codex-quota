package com.neo.codexquota.ui

import android.app.Application
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neo.codexquota.QuotaApplication
import com.neo.codexquota.data.*
import com.neo.codexquota.widget.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConnectionInput(
    val server: String = "",
    val key: String = "",
    val ready: Boolean = false,
    val expanded: Boolean = false,
)

class QuotaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as QuotaApplication).repository
    val state = repository.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())
    private val connecting = MutableStateFlow(false)
    private val workBusy = WorkManager.getInstance(application).getWorkInfosByTagFlow(RefreshScheduler.REFRESH_TAG)
        .map { infos -> infos.any {
            // 周期任务在下次运行前始终为 ENQUEUED，不能因此一直禁用操作。
            it.state == WorkInfo.State.RUNNING ||
                (it.state == WorkInfo.State.ENQUEUED && RefreshScheduler.MANUAL_TAG in it.tags)
        } }
    val busy = combine(connecting, workBusy) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val message = MutableStateFlow<String?>(null)
    private val _connectionInput = MutableStateFlow(ConnectionInput())
    val connectionInput = _connectionInput.asStateFlow()

    init {
        RefreshScheduler.ensurePeriodic(application)
        viewModelScope.launch {
            try {
                repository.awaitConnectionDraftWrites()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = "连接草稿保存失败，请检查输入后重试"
            }
            val saved = repository.states.first()
            val draft = saved.connectionDraft
            val key = try {
                draft?.let { repository.restoreDraftKey(it) }.orEmpty()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = friendlyError(error)
                ""
            }
            _connectionInput.value = ConnectionInput(
                server = draft?.server ?: saved.server, key = key,
                ready = true, expanded = draft != null,
            )
        }
    }

    fun setConnectionExpanded(expanded: Boolean) {
        _connectionInput.update { it.copy(expanded = expanded) }
    }

    fun editConnection(server: String, key: String) {
        if (!_connectionInput.value.ready || connecting.value) return
        _connectionInput.update { it.copy(server = server, key = key) }
        val save = repository.saveConnectionDraft(server, key)
        viewModelScope.launch {
            try {
                save.await()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = "连接草稿保存失败，当前输入仍保留，请重试"
            }
        }
    }

    fun connect() {
        if (busy.value || connecting.value || !_connectionInput.value.ready) return
        val input = _connectionInput.value
        connecting.value = true
        message.value = null
        viewModelScope.launch {
            try {
                // 提交时再次保存并等待队列完成，成功清除草稿后不会被旧写入覆盖。
                repository.saveConnectionDraft(input.server, input.key).await()
                val count = repository.connect(input.server, input.key)
                val saved = repository.states.first()
                _connectionInput.value = ConnectionInput(server = saved.server, ready = true)
                QuotaWidget().updateAll(getApplication())
                RefreshScheduler.ensurePeriodic(getApplication())
                RefreshScheduler.refreshNow(getApplication(), allAccounts = true)
                message.value = if (count == 0) "连接成功，服务中没有 Codex 账号" else "连接成功，已发现 $count 个 Codex 账号"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = friendlyError(error)
            } finally { connecting.value = false }
        }
    }

    fun refresh() {
        if (busy.value) return
        RefreshScheduler.refreshNow(getApplication(), allAccounts = true)
    }

    fun selectDefaultAccount(id: String) {
        viewModelScope.launch {
            try {
                repository.setDefaultAccount(id)
                QuotaWidget().updateAll(getApplication())
                RefreshScheduler.refreshNow(getApplication(), replace = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "默认账号保存失败，请重试"
            }
        }
    }

    fun setRefreshInterval(minutes: Int?) {
        viewModelScope.launch {
            try {
                if (repository.states.first().refreshIntervalMinutes == minutes) return@launch
                repository.setRefreshInterval(minutes)
                RefreshScheduler.ensurePeriodic(getApplication(), restart = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "刷新间隔保存失败，请重试"
            }
        }
    }

    fun saveAccount(account: QuotaAccount, makeDefault: Boolean, alias: String) {
        if (busy.value) return
        viewModelScope.launch {
            try {
                repository.setAccountAlias(account.id, alias)
                if (makeDefault && repository.states.first().defaultAccountId != account.id) {
                    repository.setDefaultAccount(account.id)
                    RefreshScheduler.refreshNow(getApplication(), replace = true)
                }
                QuotaWidget().updateAll(getApplication())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "账号设置保存失败，请重试"
            }
        }
    }
}
