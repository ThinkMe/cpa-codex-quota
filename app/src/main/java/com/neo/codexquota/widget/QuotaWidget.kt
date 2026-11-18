package com.neo.codexquota.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neo.codexquota.MainActivity
import com.neo.codexquota.QuotaApplication
import com.neo.codexquota.R
import com.neo.codexquota.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay

class QuotaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val application = context.applicationContext as QuotaApplication
        val repository = application.repository
        val initialState = repository.states.first()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val feedbackStore = WidgetFeedbackStore(context)
        val feedbackFlow = feedbackStore.observe(widgetId)
        val activeRefresh = WorkManager.getInstance(context).getWorkInfosByTagFlow(RefreshScheduler.REFRESH_TAG)
            .map { infos -> infos.any {
                it.state == WorkInfo.State.RUNNING ||
                    (it.state == WorkInfo.State.ENQUEUED && RefreshScheduler.MANUAL_TAG in it.tags)
            } }
        provideContent {
            val state by repository.states.collectAsState(initialState)
            val busy by activeRefresh.collectAsState(false)
            val fontScale by application.widgetFontScale.collectAsState()
            val feedback by feedbackFlow.collectAsState(feedbackStore.get(widgetId))
            var feedbackClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(feedback) {
                feedbackClock = System.currentTimeMillis()
                feedback?.let {
                    delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                    feedbackClock = System.currentTimeMillis()
                }
            }
            WidgetContent(context, state, busy, fontScale,
                visibleFeedback(feedback, state, maxOf(feedbackClock, System.currentTimeMillis())))
        }
    }
}

// 固定浅色配色，确保系统深色模式下白底卡片的文字仍清晰可读。
private object WidgetColors {
    val value = ColorProvider(day = Color(0xFF333333), night = Color(0xFF333333))
    val secondary = ColorProvider(day = Color(0xFF999999), night = Color(0xFF999999))
    val error = ColorProvider(day = Color(0xFFC64D43), night = Color(0xFFC64D43))
    val blue = ColorProvider(day = Color(0xFF3578DB), night = Color(0xFF3578DB))
    val green = ColorProvider(day = Color(0xFF36864B), night = Color(0xFF36864B))

    // 百分比与进度条共用颜色资源；无数据时保留深灰占位文字。
    fun quotaValue(context: Context, remaining: Double?): androidx.glance.unit.ColorProvider {
        val color = Color(context.getColor(when (quotaTone(remaining)) {
            QuotaTone.Red -> R.color.widget_low
            QuotaTone.Yellow -> R.color.widget_yellow
            QuotaTone.Green -> R.color.widget_fill
            QuotaTone.Unknown -> R.color.widget_text
        }))
        return ColorProvider(day = color, night = color)
    }
}

@Composable
private fun WidgetContent(context: Context, state: AppState, busy: Boolean, fontScale: Float, feedback: WidgetFeedback?) {
    val size = LocalSize.current
    val account = state.defaultAccount
    val snapshot = account?.let { state.snapshots[it.id] }
    val failed = state.globalError != null || snapshot?.error != null ||
        feedback?.phase == FeedbackPhase.Error || feedback?.phase == FeedbackPhase.Failure
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    account?.let { intent.putExtra("account_id", it.id) }
    val needsSetup = !state.configured || account == null
    val phase = if (needsSetup) null else feedback?.phase ?: if (failed) FeedbackPhase.Error else null
    val sweeping = phase == FeedbackPhase.Refreshing
    val lightweightBusy = busy && feedback == null
    val action = if (needsSetup) actionStartActivity(intent)
        else actionRunCallback<RefreshAction>()
    val windows = listOf(snapshot.windowFor(18_000), snapshot.windowFor(604_800))
    val labels = listOf(context.getString(R.string.widget_five_hours), context.getString(R.string.widget_week))
    val now = System.currentTimeMillis()
    // 小部件宿主更改系统字号后，应用进程可能仍持有旧的缩放参数。
    val measurementContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        this.fontScale = fontScale
    })
    val layout = widgetGroupLayout(size.width.value, size.height.value, labels,
        windows.map { widgetResetTime(it?.resetAtMillis, now) },
        { text, sp, bold -> measureText(measurementContext, text, sp, bold) },
        { sp, bold -> measureTextHeight(measurementContext, sp, bold) },
        statusIconWidth = 16f)
    val contentWidth = (size.width.value - layout.inset * 2).coerceAtLeast(0f)
    val description = when {
        needsSetup -> "打开应用，配置连接和默认账号"
        phase == FeedbackPhase.Success -> "已更新"
        phase == FeedbackPhase.Failure -> "刷新失败，显示上次结果，点击重试"
        busy || phase == FeedbackPhase.Refreshing -> "刷新数据，刷新中"
        failed -> "刷新数据；刷新失败，显示上次结果，点击重试"
        else -> context.getString(R.string.widget_refresh)
    }
    val surface = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background))
        .cornerRadius(28.dp).semantics { contentDescription = description }
    Box(
        surface.clickable(action, rippleOverride = R.drawable.widget_press_feedback),
        contentAlignment = Alignment.Center,
    ) {
        if (phase == FeedbackPhase.Refreshing || phase == FeedbackPhase.Success) {
            // 光效位于内容后方，原生容器按真实圆角裁剪，文字始终清晰可读。
            AndroidRemoteViews(RemoteViews(context.packageName,
                if (phase == FeedbackPhase.Refreshing) R.layout.widget_scan else R.layout.widget_scan_finish).apply {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        setBoolean(R.id.widget_scan_surface, "setClipToOutline", true)
                    }
                },
                modifier = GlanceModifier.fillMaxSize())
        }
        Column(
            GlanceModifier.fillMaxSize().padding(layout.inset.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuotaGroup(measurementContext, labels[0], "5H", windows[0], lightweightBusy, sweeping, layout, contentWidth, now)
            Spacer(GlanceModifier.height(layout.groupGap.dp))
            QuotaGroup(measurementContext, labels[1], "周", windows[1], lightweightBusy, sweeping, layout, contentWidth, now,
                feedbackPhase = phase, statusSlot = true)
        }
    }
}

@Composable
private fun QuotaGroup(
    context: Context, label: String, shortLabel: String, window: QuotaWindow?, busy: Boolean, sweeping: Boolean,
    layout: WidgetGroupLayout, width: Float, now: Long, feedbackPhase: FeedbackPhase? = null, statusSlot: Boolean = false,
) {
    val remaining = window?.remainingPercent
    val fullTime = widgetResetTime(window?.resetAtMillis, now)
    Column(GlanceModifier.fillMaxWidth().semantics {
        contentDescription = "$label，${percentageLabel(remaining)}，$fullTime"
    }) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (layout.abbreviated) shortLabel else label, maxLines = 1,
                style = TextStyle(color = WidgetColors.value, fontSize = layout.titleSp.sp, fontWeight = FontWeight.Bold))
            // 极小尺寸始终预留图标位置，状态变化不会挤动标题和百分比。
            if (statusSlot && !layout.showTime) {
                Spacer(GlanceModifier.width(4.dp))
                Box(GlanceModifier.size(12.dp), contentAlignment = Alignment.Center) {
                    if (feedbackPhase != null) FeedbackIcon(context, feedbackPhase)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(percentageLabel(remaining), maxLines = 1,
                style = TextStyle(color = WidgetColors.quotaValue(context, remaining), fontSize = layout.percentSp.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(layout.itemGap.dp))
        QuotaProgress(context, remaining, busy, sweeping, width, layout.barHeight, delayed = statusSlot)
        if (layout.showTime) {
            Spacer(GlanceModifier.height(layout.itemGap.dp))
            // 时间行和状态行使用相同高度，刷新前后所有内容保持原位。
            val lineHeight = maxOf(12f, measureTextHeight(context, layout.timeSp, false))
            Row(GlanceModifier.fillMaxWidth().height(lineHeight.dp).semantics {
                contentDescription = when (feedbackPhase) {
                    FeedbackPhase.Refreshing -> "刷新中；$fullTime"
                    FeedbackPhase.Success -> "已更新；$fullTime"
                    FeedbackPhase.Failure, FeedbackPhase.Error -> "刷新失败，显示上次结果，点击重试；$fullTime"
                    null -> fullTime
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                if (feedbackPhase != null) {
                    FeedbackIcon(context, feedbackPhase)
                    Spacer(GlanceModifier.width(4.dp))
                }
                val message = when (feedbackPhase) {
                    FeedbackPhase.Refreshing -> "刷新中"
                    FeedbackPhase.Success -> "已更新"
                    FeedbackPhase.Failure, FeedbackPhase.Error ->
                        if (measureText(context, "刷新失败，点击重试", layout.timeSp) <= width - 16f)
                            "刷新失败，点击重试" else "失败，点击重试"
                    null -> if (layout.fullTime) fullTime else widgetRelativeTime(window?.resetAtMillis, now)
                }
                Text(message, GlanceModifier.defaultWeight(), maxLines = 1,
                    style = TextStyle(color = when (feedbackPhase) {
                        FeedbackPhase.Refreshing -> WidgetColors.blue
                        FeedbackPhase.Success -> WidgetColors.green
                        FeedbackPhase.Failure, FeedbackPhase.Error -> WidgetColors.error
                        null -> WidgetColors.secondary
                    }, fontSize = layout.timeSp.sp))
            }
        }
    }
}

@Composable
private fun FeedbackIcon(context: Context, phase: FeedbackPhase) {
    if (phase == FeedbackPhase.Refreshing) {
        AndroidRemoteViews(RemoteViews(context.packageName, R.layout.widget_feedback_spinner),
            modifier = GlanceModifier.size(12.dp))
    } else if (phase == FeedbackPhase.Success) {
        AndroidRemoteViews(RemoteViews(context.packageName, R.layout.widget_feedback_success_animated),
            modifier = GlanceModifier.size(12.dp).semantics { contentDescription = "已更新" })
    } else {
        Image(ImageProvider(R.drawable.widget_feedback_error), "刷新失败，点击重试", modifier = GlanceModifier.size(12.dp))
    }
}

@Composable
private fun QuotaProgress(context: Context, remaining: Double?, busy: Boolean, sweeping: Boolean, width: Float, height: Float, delayed: Boolean) {
    val tone = quotaTone(remaining)
    val (fill, animation) = when (tone) {
        QuotaTone.Red -> R.drawable.widget_progress_low to R.layout.widget_pulse_red
        QuotaTone.Yellow -> R.drawable.widget_progress_yellow to R.layout.widget_pulse_yellow
        QuotaTone.Green -> R.drawable.widget_progress_fill to R.layout.widget_pulse_green
        QuotaTone.Unknown -> R.drawable.widget_progress_stale to R.layout.widget_pulse_track
    }
    val fraction = (remaining?.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 100.0) / 100.0
    val fillWidth = (width * fraction).toFloat()
    Box(GlanceModifier.width(width.dp).height(height.dp).background(ImageProvider(R.drawable.widget_progress_track))) {
        if (fraction > 0) {
            val fillModifier = GlanceModifier.width(fillWidth.dp).height(height.dp)
            if (sweeping) {
                Box(fillModifier.background(ImageProvider(fill))) {
                    if (fillWidth >= 12f) {
                        val particles = when {
                            fillWidth < 40f && delayed -> R.layout.widget_particles_single_delayed
                            fillWidth < 40f -> R.layout.widget_particles_single
                            delayed -> R.layout.widget_particles_delayed
                            else -> R.layout.widget_particles
                        }
                        val views = RemoteViews(context.packageName, particles).apply {
                            setInt(R.id.widget_particles, "setBackgroundResource", fill)
                        }
                        AndroidRemoteViews(views, modifier = GlanceModifier.fillMaxSize())
                    }
                }
            } else if (busy) AndroidRemoteViews(RemoteViews(context.packageName, animation), modifier = fillModifier)
            else Box(fillModifier.background(ImageProvider(fill))) {}
        } else if (busy && !sweeping) {
            AndroidRemoteViews(RemoteViews(context.packageName, R.layout.widget_pulse_track), modifier = GlanceModifier.fillMaxSize())
        }
    }
}

private fun measureText(context: Context, text: String, sizeSp: Float, bold: Boolean = false): Float {
    return textPaint(context, sizeSp, bold).measureText(text) / context.resources.displayMetrics.density
}

private fun measureTextHeight(context: Context, sizeSp: Float, bold: Boolean): Float {
    val metrics = textPaint(context, sizeSp, bold).fontMetricsInt
    // TextView 默认包含字体上下留白，另留 1dp 容纳像素取整误差。
    return (metrics.bottom - metrics.top) / context.resources.displayMetrics.density + 1f
}

private fun textPaint(context: Context, sizeSp: Float, bold: Boolean): Paint {
    val metrics = context.resources.displayMetrics
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        WidgetFeedbackController.click(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshScheduler.ensurePeriodic(context)
        RefreshScheduler.refreshNow(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RefreshScheduler.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshScheduler.cancelPeriodic(context)
    }
}
