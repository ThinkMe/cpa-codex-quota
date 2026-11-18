package com.neo.codexquota.widget

import android.content.Context
import android.content.SharedPreferences
import android.appwidget.AppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.*
import com.neo.codexquota.QuotaApplication
import com.neo.codexquota.data.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.UUID

internal enum class FeedbackPhase { Refreshing, Success, Failure, Error }

internal data class WidgetFeedback(
    val requestId: String,
    val accountId: String,
    val server: String,
    val startedAt: Long,
    val expiresAt: Long,
    val phase: FeedbackPhase = FeedbackPhase.Refreshing,
    val workId: String = "",
    val completedAt: Long = 0,
)

// 显示时再次检查截止时间，宿主重新显示或进程重启后也不会留下过期刷新状态。
internal fun visibleFeedback(feedback: WidgetFeedback?, state: AppState, now: Long): WidgetFeedback? {
    feedback ?: return null
    if (feedback.accountId != state.defaultAccount?.id || feedback.server != state.server ||
        !state.configured || now < feedback.startedAt - 1_000) return null
    val snapshot = state.snapshots[feedback.accountId]
    if (feedback.phase != FeedbackPhase.Refreshing && snapshot != null && snapshot.error == null &&
        state.globalError == null && snapshot.fetchedAt > feedback.completedAt) return null
    return if (now < feedback.expiresAt) feedback else when (feedback.phase) {
        FeedbackPhase.Success -> null
        else -> feedback.copy(phase = FeedbackPhase.Error)
    }
}

internal class WidgetFeedbackStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("widget_feedback", Context.MODE_PRIVATE)

    fun get(widgetId: Int): WidgetFeedback? = runCatching {
        val value = preferences.getString(widgetId.toString(), null) ?: return null
        val data = Json.parseToJsonElement(value).jsonObject
        WidgetFeedback(
            data.getValue("requestId").jsonPrimitive.content,
            data.getValue("accountId").jsonPrimitive.content,
            data.getValue("server").jsonPrimitive.content,
            data.getValue("startedAt").jsonPrimitive.long,
            data.getValue("expiresAt").jsonPrimitive.long,
            FeedbackPhase.valueOf(data.getValue("phase").jsonPrimitive.content),
            data.getValue("workId").jsonPrimitive.content,
            data.getValue("completedAt").jsonPrimitive.long,
        )
    }.getOrNull()

    fun observe(widgetId: Int) = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == widgetId.toString()) trySend(get(widgetId))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(get(widgetId))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun save(widgetId: Int, value: WidgetFeedback?, expectedRequest: String? = null) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (expectedRequest != null && get(widgetId)?.requestId != expectedRequest) return@synchronized
            val editor = preferences.edit()
            if (value == null) editor.remove(widgetId.toString()) else editor.putString(widgetId.toString(), buildJsonObject {
                put("requestId", value.requestId); put("accountId", value.accountId); put("server", value.server)
                put("startedAt", value.startedAt); put("expiresAt", value.expiresAt)
                put("phase", value.phase.name); put("workId", value.workId); put("completedAt", value.completedAt)
            }.toString())
            check(editor.commit()) { "无法保存小部件刷新状态" }
        }
    }

    companion object { private val lock = Any() }
}

internal object WidgetFeedbackController {
    private val clickMutex = Mutex()
    const val MAX_WAIT = 180_000L
    const val MIN_BUSY = 700L

    // 与点击绑定共用互斥锁，避免快速请求的结果先于绑定写入而丢失。
    suspend fun beginWork(context: Context, workId: UUID) = clickMutex.withLock {
        WidgetRefreshResults(context).clear(workId.toString())
    }

    suspend fun reportResult(context: Context, workId: UUID, accountId: String, server: String, success: Boolean) =
        clickMutex.withLock {
            WidgetRefreshResults(context).save(WidgetRefreshResult(workId.toString(), accountId, server,
                success, System.currentTimeMillis()))
        }

    private suspend fun enqueueWatcher(context: Context, widgetId: Int, requestId: String) {
        val watcher = OneTimeWorkRequestBuilder<WidgetFeedbackWorker>()
            .setInputData(workDataOf("widget_id" to widgetId, "request_id" to requestId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("widget_feedback_${widgetId}_$requestId",
            ExistingWorkPolicy.KEEP, watcher).await()
    }

    suspend fun recover(context: Context) = clickMutex.withLock {
        val store = WidgetFeedbackStore(context)
        val state = (context.applicationContext as QuotaApplication).repository.states.first()
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
            android.content.ComponentName(context, QuotaWidgetReceiver::class.java))
        for (id in ids) {
            val feedback = store.get(id) ?: continue
            if (visibleFeedback(feedback, state, System.currentTimeMillis()) == null) {
                store.save(id, null, feedback.requestId)
                redrawFeedbackWidget(context, id)
                continue
            }
            // 点击先显示反馈，若恰在任务绑定前退出进程，恢复时补齐调度而非直接判为失败。
            if (feedback.phase == FeedbackPhase.Refreshing && feedback.workId.isBlank() &&
                System.currentTimeMillis() < feedback.expiresAt) {
                val workId = RefreshScheduler.refreshForWidget(context)
                store.save(id, feedback.copy(workId = workId.toString()), feedback.requestId)
            }
            if (feedback.phase != FeedbackPhase.Error) enqueueWatcher(context, id, feedback.requestId)
        }
    }

    suspend fun finish(context: Context, widgetId: Int, requestId: String, success: Boolean) = clickMutex.withLock {
        val store = WidgetFeedbackStore(context)
        val current = store.get(widgetId)?.takeIf {
            it.requestId == requestId && it.phase == FeedbackPhase.Refreshing
        } ?: return@withLock
        val completedAt = System.currentTimeMillis()
        store.save(widgetId, current.copy(phase = if (success) FeedbackPhase.Success else FeedbackPhase.Failure,
            completedAt = completedAt, expiresAt = completedAt + if (success) 1_000 else 2_000), requestId)
        redrawFeedbackWidget(context, widgetId)
    }

    suspend fun clearResult(context: Context, widgetId: Int, requestId: String) = clickMutex.withLock {
        val store = WidgetFeedbackStore(context)
        val current = store.get(widgetId)?.takeIf { it.requestId == requestId } ?: return@withLock
        if (current.phase == FeedbackPhase.Refreshing || System.currentTimeMillis() < current.expiresAt) return@withLock
        store.save(widgetId, if (current.phase == FeedbackPhase.Success) null else current.copy(phase = FeedbackPhase.Error), requestId)
        redrawFeedbackWidget(context, widgetId)
    }

    suspend fun click(context: Context, widgetId: Int) = clickMutex.withLock {
        val store = WidgetFeedbackStore(context)
        val state = (context.applicationContext as QuotaApplication).repository.states.first()
        val account = state.defaultAccount ?: return@withLock
        if (!state.configured) return@withLock
        val previous = visibleFeedback(store.get(widgetId), state, System.currentTimeMillis())
        if (previous?.phase == FeedbackPhase.Refreshing) return@withLock
        if (previous?.phase == FeedbackPhase.Success) {
            store.save(widgetId, null, previous.requestId)
            redrawFeedbackWidget(context, widgetId)
            return@withLock
        }
        val now = System.currentTimeMillis()
        val feedback = WidgetFeedback(UUID.randomUUID().toString(), account.id, state.server, now, now + MAX_WAIT)
        store.save(widgetId, feedback)
        // 先发布等待状态，再查询或合并 WorkManager 任务。
        redrawFeedbackWidget(context, widgetId)
        try {
            val workId = RefreshScheduler.refreshForWidget(context)
            store.save(widgetId, feedback.copy(workId = workId.toString()), feedback.requestId)
            enqueueWatcher(context, widgetId, feedback.requestId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            store.save(widgetId, feedback.copy(phase = FeedbackPhase.Failure,
                completedAt = System.currentTimeMillis(), expiresAt = System.currentTimeMillis() + 2_000), feedback.requestId)
            redrawFeedbackWidget(context, widgetId)
        }
    }
}

// 独立任务跟踪网络任务的实际结果，进程退出后仍能恢复并收起提示。
class WidgetFeedbackWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt("widget_id", -1)
        val requestId = inputData.getString("request_id") ?: return Result.success()
        val store = WidgetFeedbackStore(applicationContext)
        val feedback = store.get(widgetId)?.takeIf { it.requestId == requestId } ?: return Result.success()
        if (feedback.phase == FeedbackPhase.Refreshing) {
            val workId = runCatching { UUID.fromString(feedback.workId) }.getOrNull()
            val results = WidgetRefreshResults(applicationContext)
            val completion = workId?.let {
                withTimeoutOrNull((feedback.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)) {
                    combine(results.observe(it.toString()),
                        WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(it), store.observe(widgetId)) {
                            result, work, current -> Triple(result, work, current)
                        }.first { (result, work, current) ->
                            current?.requestId != requestId || current.phase != FeedbackPhase.Refreshing ||
                                result?.matches(feedback) == true || work == null || work.state.isFinished ||
                                (work.periodicityInfo != null && work.state == WorkInfo.State.ENQUEUED)
                        }
                }
            }
            val info = completion?.second
            val persisted = completion?.first?.takeIf { it.matches(feedback) }
            val state = (applicationContext as QuotaApplication).repository.states.first()
            // 兼容升级前已卡住的周期刷新，以及数据保存后进程中断、尚未来得及发布结果的情况。
            val recoveredSuccess = persisted == null && state.server == feedback.server &&
                state.defaultAccount?.id == feedback.accountId && state.globalError == null &&
                state.snapshots[feedback.accountId]?.let { it.error == null && it.fetchedAt >= feedback.startedAt } == true
            val success = persisted?.success ?: (recoveredSuccess || info?.outputData?.let {
                it.getBoolean(RefreshScheduler.QUOTA_SUCCESS, false) &&
                    it.getString(RefreshScheduler.RESULT_ACCOUNT) == feedback.accountId &&
                    it.getString(RefreshScheduler.RESULT_SERVER) == feedback.server &&
                    it.getLong(RefreshScheduler.COMPLETED_AT, 0) >= feedback.startedAt
            } == true)
            delay((feedback.startedAt + WidgetFeedbackController.MIN_BUSY - System.currentTimeMillis()).coerceAtLeast(0))
            WidgetFeedbackController.finish(applicationContext, widgetId, requestId, success)
        }
        val result = store.get(widgetId)?.takeIf { it.requestId == requestId } ?: return Result.success()
        delay((result.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        WidgetFeedbackController.clearResult(applicationContext, widgetId, requestId)
        return Result.success()
    }
}

// 小部件删除后忽略未完成的重绘，避免访问已失效的宿主标识。
private suspend fun redrawFeedbackWidget(context: Context, widgetId: Int) {
    if (AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) != null) {
        QuotaWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId))
    }
}
