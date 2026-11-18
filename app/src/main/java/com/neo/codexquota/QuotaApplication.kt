package com.neo.codexquota

import android.app.Application
import android.content.res.Configuration
import androidx.glance.appwidget.updateAll
import com.neo.codexquota.data.KeyVault
import com.neo.codexquota.data.ProxyApi
import com.neo.codexquota.data.QuotaRepository
import com.neo.codexquota.data.StateStore
import com.neo.codexquota.widget.QuotaWidget
import com.neo.codexquota.widget.WidgetFeedbackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class QuotaApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val widgetFontScaleState by lazy { MutableStateFlow(resources.configuration.fontScale) }
    internal val widgetFontScale: StateFlow<Float> get() = widgetFontScaleState
    val repository: QuotaRepository by lazy { QuotaRepository(StateStore(this), KeyVault(), ProxyApi(), applicationScope) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                WidgetFeedbackController.recover(this@QuotaApplication)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("WidgetFeedback", "恢复小部件反馈任务失败", error)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 显式通知仍活跃的 Glance 组合重新选字号档位，避免只放大旧布局而发生截断。
        widgetFontScaleState.value = newConfig.fontScale
        // 较旧系统不会为小部件重新计算文本颜色，配置切换后主动重绘。
        applicationScope.launch { QuotaWidget().updateAll(this@QuotaApplication) }
    }
}
