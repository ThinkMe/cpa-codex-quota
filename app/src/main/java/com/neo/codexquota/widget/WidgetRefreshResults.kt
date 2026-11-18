package com.neo.codexquota.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// 周期任务完成后 WorkManager 会清空输出，因此独立保存每轮请求的真实结果。
internal data class WidgetRefreshResult(
    val workId: String,
    val accountId: String,
    val server: String,
    val success: Boolean,
    val completedAt: Long,
) {
    fun matches(feedback: WidgetFeedback): Boolean = workId == feedback.workId &&
        accountId == feedback.accountId && server == feedback.server && completedAt >= feedback.startedAt
}

internal class WidgetRefreshResults(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("widget_refresh_results", Context.MODE_PRIVATE)

    fun get(workId: String): WidgetRefreshResult? = runCatching {
        val raw = preferences.getString(workId, null) ?: return null
        val data = Json.parseToJsonElement(raw).jsonObject
        WidgetRefreshResult(workId, data.getValue("accountId").jsonPrimitive.content,
            data.getValue("server").jsonPrimitive.content, data.getValue("success").jsonPrimitive.boolean,
            data.getValue("completedAt").jsonPrimitive.long)
    }.getOrNull()

    fun observe(workId: String) = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == workId) trySend(get(workId))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(get(workId))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun clear(workId: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(workId).commit()) { "无法清除上一轮刷新结果" }
    }

    suspend fun save(result: WidgetRefreshResult) = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        // 只保留最近的结果，避免长期运行后不断累积已结束的单次任务。
        preferences.all.keys.mapNotNull { get(it) }.filter { it.workId != result.workId }
            .sortedByDescending { it.completedAt }.drop(63).forEach { editor.remove(it.workId) }
        editor.putString(result.workId, buildJsonObject {
            put("accountId", result.accountId); put("server", result.server)
            put("success", result.success); put("completedAt", result.completedAt)
        }.toString())
        check(editor.commit()) { "无法保存本轮刷新结果" }
    }
}
