package com.neo.codexquota.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.neo.codexquota.QuotaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            WidgetFeedbackController.beginWork(applicationContext, id)
            val repository = (applicationContext as QuotaApplication).repository
            val before = repository.states.first()
            val startedAt = System.currentTimeMillis()
            // 周期任务及 App 内刷新也唤醒小部件会话，及时显示宿主呼吸动画。
            withContext(Dispatchers.Main) { QuotaWidget().updateAll(applicationContext) }
            repository.refresh(allAccounts = inputData.getBoolean(RefreshScheduler.ALL_ACCOUNTS, false))
            val after = repository.states.first()
            val snapshot = after.defaultAccount?.let { after.snapshots[it.id] }
            // 调度成功不等于接口成功，浮层只确认本次默认账号实际取到的新结果。
            val quotaSuccess = after.server == before.server && after.defaultAccount?.id == before.defaultAccount?.id &&
                after.globalError == null && snapshot != null && snapshot.error == null && snapshot.fetchedAt >= startedAt
            WidgetFeedbackController.reportResult(applicationContext, id, before.defaultAccount?.id.orEmpty(),
                before.server, quotaSuccess)
            withContext(Dispatchers.Main) { QuotaWidget().updateAll(applicationContext) }
            if (inputData.getBoolean(RefreshScheduler.SHORT_CYCLE, false)) {
                RefreshScheduler.continueShortCycle(applicationContext) { isStopped }
            }
            // 接口错误已保存到缓存，仍按用户间隔继续刷新。
            Result.success(workDataOf(
                RefreshScheduler.QUOTA_SUCCESS to quotaSuccess,
                RefreshScheduler.RESULT_ACCOUNT to before.defaultAccount?.id.orEmpty(),
                RefreshScheduler.RESULT_SERVER to before.server,
                RefreshScheduler.COMPLETED_AT to System.currentTimeMillis(),
            ))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 调度或重绘异常时重试当前任务，避免连续单次任务意外中断。
            Result.retry()
        }
    }
}

object RefreshScheduler {
    const val REFRESH_TAG = "quota_refresh"
    const val MANUAL_TAG = "quota_manual_refresh"
    const val ALL_ACCOUNTS = "all_accounts"
    const val SHORT_CYCLE = "short_cycle"
    const val QUOTA_SUCCESS = "quota_success"
    const val RESULT_ACCOUNT = "result_account"
    const val RESULT_SERVER = "result_server"
    const val COMPLETED_AT = "completed_at"
    private const val PERIODIC = "quota_periodic"
    private const val SHORT = "quota_short_cycle"
    private const val MANUAL = "quota_manual"
    private val scheduleMutex = Mutex()

    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, QuotaWidgetReceiver::class.java)).isNotEmpty()

    fun ensurePeriodic(context: Context, restart: Boolean = false) {
        val application = context.applicationContext as QuotaApplication
        application.applicationScope.launch {
            scheduleMutex.withLock {
                val minutes = application.repository.states.first().refreshIntervalMinutes
                val manager = WorkManager.getInstance(application)
                if (minutes == null || !hasWidgets(application)) {
                    manager.cancelUniqueWork(PERIODIC).await()
                    manager.cancelUniqueWork(SHORT).await()
                    return@withLock
                }
                if (minutes < 15) {
                    manager.cancelUniqueWork(PERIODIC).await()
                    // 单次任务不受周期任务的十五分钟下限限制；恢复时保留原有计时。
                    manager.enqueueUniqueWork(SHORT,
                        if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                        shortRequest(minutes)).await()
                    return@withLock
                }
                manager.cancelUniqueWork(SHORT).await()
                val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
                    .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(REFRESH_TAG).build()
                // 普通恢复只更新任务配置，保留原计时；用户修改间隔时才重新开始计时。
                val policy = if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
                manager.enqueueUniquePeriodicWork(PERIODIC, policy, request).await()
            }
        }
    }

    private fun shortRequest(minutes: Int) = OneTimeWorkRequestBuilder<RefreshWorker>()
        .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
        .setInputData(workDataOf(SHORT_CYCLE to true))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .addTag(REFRESH_TAG).build()

    internal suspend fun continueShortCycle(context: Context, stopped: () -> Boolean) {
        val application = context.applicationContext as QuotaApplication
        scheduleMutex.withLock {
            val minutes = application.repository.states.first().refreshIntervalMinutes
            if (stopped() || minutes == null || minutes >= 15 || !hasWidgets(application)) return@withLock
            // 后继任务依赖本次完成，避免重叠；与修改间隔、关闭刷新共用锁。
            WorkManager.getInstance(application).enqueueUniqueWork(
                SHORT, ExistingWorkPolicy.APPEND_OR_REPLACE, shortRequest(minutes)).await()
        }
    }

    fun refreshNow(context: Context, allAccounts: Boolean = false, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(workDataOf(ALL_ACCOUNTS to allAccounts))
            .addTag(REFRESH_TAG).addTag(MANUAL_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    internal suspend fun refreshForWidget(context: Context): java.util.UUID {
        val manager = WorkManager.getInstance(context)
        // 复用正在运行的后台刷新，或已排队的手动刷新，不复用尚未到期的周期任务。
        val existing = manager.getWorkInfosByTagFlow(REFRESH_TAG).first().firstOrNull {
            (it.state == WorkInfo.State.RUNNING ||
                (it.state == WorkInfo.State.ENQUEUED && MANUAL_TAG in it.tags)) &&
                WidgetRefreshResults(context).get(it.id.toString()) == null
        }
        if (existing != null) return existing.id
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .addTag(REFRESH_TAG).addTag(MANUAL_TAG).build()
        // 已发布结果但尚未退出的旧任务不能吞掉新点击；必要时追加到它之后运行。
        manager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
        val scheduled = manager.getWorkInfosForUniqueWorkFlow(MANUAL).first()
        return (scheduled.firstOrNull { it.id == request.id } ?: scheduled.firstOrNull { !it.state.isFinished }
            ?: scheduled.maxByOrNull { it.outputData.getLong(COMPLETED_AT, 0) })?.id ?: request.id
    }

    fun cancelPeriodic(context: Context) {
        ensurePeriodic(context)
    }
}
