// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.pipeline.SubtitleCue
import kotlin.math.abs

/**
 * 悬浮字幕窗。
 *
 * ## 为什么用传统 View 而不是 Compose
 * 把 Compose 挂到 WindowManager 上需要自己实现 LifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner 三件套，而且首次组合要 100~300 ms——字幕是每秒都在变的 UI，
 * 这个启动开销完全没必要。TextView 一毫秒就能 setText。
 *
 * ## 渲染节流
 * 用 CONFLATED 语义：80 ms 内的多条更新只渲染最后一条。
 * 不节流的话，快速连续的暂定字幕会导致每帧重新测量布局，明显掉帧。
 */
class SubtitleOverlay(private val context: Context) {

    companion object {
        private const val TAG = LogTags.OVERLAY

        /** 渲染节流间隔。80ms ≈ 12fps，字幕够用且不卡 */
        private const val RENDER_INTERVAL_MS = 80L

        /** 判定为拖动的位移阈值（px） */
        private const val DRAG_SLOP_PX = 12

        /**
         * 没有译文时主字幕显示的提示语。
         *
         * 不再用原文顶上：原文出现在中文字幕的位置，用户会以为"翻译只翻了英文"，
         * 根本看不出是引擎挂了。一句明确的提示比一堆英文有用。
         */
        const val NO_TRANSLATION_HINT = "翻译中，若无译文请更改翻译接口"

        /** 告警文字颜色：橙红，在黑色半透明底上醒目又不刺眼 */
        private val ALERT_COLOR = 0xFFFFB74D.toInt()

        /** 额度条底色：深橙半透明，比告警更"重"，因为它是常驻状态而不是一次性提示 */
        private val QUOTA_BG = 0xCCE65100.toInt()

        /** 瞬时提示底色：深色半透明，白字，不抢主字幕的注意力 */
        private val NOTICE_BG = 0xCC1A1A1A.toInt()

        /** 瞬时提示的默认停留时长 */
        const val NOTICE_DURATION_MS = 5_000L
    }

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var quotaView: TextView? = null
    private var noticeView: TextView? = null
    private var alertView: TextView? = null
    private var previousView: TextView? = null
    private var currentView: TextView? = null
    private var sourceView: TextView? = null

    /**
     * 瞬时提示的"代次"。
     *
     * 连续弹两条 notice 时，第一条的延迟清除任务会在第二条显示后把它抹掉。
     * 每次 show 都换个代号，延迟任务发现代号变了就不清了。
     */
    private var noticeToken: Int = 0

    private var layoutParams: WindowManager.LayoutParams? = null

    private var style: SubtitleStyle = SubtitleStyle.DEFAULT

    private var isShowing = false

    /** 待渲染的最新字幕（节流用） */
    private var pendingCue: SubtitleCue? = null
    private var renderScheduled = false

    /** 上一句，用于双行显示 */
    private var lastRenderedText: String = ""

    /**
     * 上一次渲染的字幕序号。
     * 流水线现在"先原文、后译文"分两次发同一个 seq，
     * 靠它判断两次更新是同一句话，避免把同一句挤进上一行的灰字里。
     */
    private var lastRenderedSeq: Long = -1L

    val showing: Boolean get() = isShowing

    // ---------------- 权限 ----------------

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    // ---------------- 显示 / 隐藏 ----------------

    fun show(): Boolean {
        if (isShowing) return true
        if (!canDrawOverlay()) {
            Log.w(TAG, "缺少悬浮窗权限")
            return false
        }

        return try {
            val root = buildRootView()
            val params = buildLayoutParams()

            windowManager.addView(root, params)

            rootView = root
            layoutParams = params
            isShowing = true
            Log.i(TAG, "字幕窗已显示")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "字幕窗添加失败", t)
            false
        }
    }

    fun hide() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (t: Throwable) {
            Log.w(TAG, "移除字幕窗失败", t)
        }
        rootView = null
        layoutParams = null
        isShowing = false
        pendingCue = null
        lastRenderedText = ""
        lastRenderedSeq = -1L
        Log.i(TAG, "字幕窗已隐藏")
    }

    // ---------------- 构建视图 ----------------

    private fun buildRootView(): LinearLayout {
        val density = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(14f, density), dp(8f, density),
                dp(14f, density), dp(8f, density),
            )
            background = buildBackground()
        }

        // 额度条（常驻）：某家引擎额度用尽、或已无可用引擎时挂在这里。
        // 和下面的 alertView 分开两个 view，是因为生命周期完全不同：
        // alertView 下一句译文回来就该清掉，额度问题没解决之前不能消失。
        quotaView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.8f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8f, density), dp(3f, density), dp(8f, density), dp(3f, density))
            background = GradientDrawable().apply {
                cornerRadius = density * 6f
                setColor(QUOTA_BG)
            }
            visibility = View.GONE
        }

        // 瞬时提示（5 秒自动消失）：用于"已启用预付费额度""已切换到 X"这类一次性告知
        noticeView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.8f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8f, density), dp(3f, density), dp(8f, density), dp(3f, density))
            background = GradientDrawable().apply {
                cornerRadius = density * 6f
                setColor(NOTICE_BG)
            }
            visibility = View.GONE
        }

        // 顶部告警条：翻译接口失败时显示具体是哪一家挂了。
        // 不用系统通知——荣耀会把通知折叠成一个空条目，用户根本看不到内容。
        alertView = TextView(context).apply {
            setTextColor(ALERT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.72f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            visibility = View.GONE
        }

        // 上一句：与当前句形成**两行**，但仍有主次。
        //
        // 原来的 0.42 透明度 + 0.78 字号 + 单行，说话一快就变成"飘上去的小灰字"，
        // 根本来不及看——用户原话是"中间这句飘上去变小透明了"。
        // 现在提到 0.72 透明度、0.86 字号、最多 2 行：够清楚，又不会和当前句抢注意力。
        previousView = TextView(context).apply {
            setTextColor(style.textColor)
            alpha = 0.72f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.86f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            visibility = View.GONE
        }

        // 当前句（主字幕）
        currentView = TextView(context).apply {
            setTextColor(style.textColor)
            alpha = 1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
            maxLines = style.maxLines
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            text = "字幕就绪，等待语音…"
        }

        // 原文（可选，学习用）
        sourceView = TextView(context).apply {
            setTextColor(style.textColor)
            alpha = 0.62f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sourceFontSizeSp)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            visibility = if (style.showSourceText) View.VISIBLE else View.GONE
        }

        root.addView(quotaView)
        root.addView(noticeView)
        root.addView(alertView)
        root.addView(previousView)
        // 当前句与上一句之间留 3dp，否则频繁对话时两句视觉上连成一段
        root.addView(
            currentView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3f, density) },
        )
        root.addView(sourceView)

        if (style.draggable) {
            attachDrag(root)
        }

        return root
    }

    private fun buildBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.resources.displayMetrics.density * 12f
            setColor(Color.BLACK)
            // 背景不透明度绝对值（不用 View.alpha，否则文字也一起变透明）
            alpha = (style.safeAlpha() * 255).toInt()
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(style.bottomMarginDp.toFloat(), context.resources.displayMetrics.density)
            // 留出左右边距，字幕贴边很难看
            horizontalMargin = 0.04f
        }
    }

    // ---------------- 更新内容 ----------------

    /**
     * 顶部告警条。传 null 或空串表示恢复正常、隐藏告警。
     *
     * 为什么不用系统通知：荣耀/部分 ROM 会把通知折叠成一个没有内容的空条目，
     * 用户完全看不到里面写了什么。悬浮窗本来就一直在屏幕上，
     * 直接写在这里最可靠，也不需要通知权限。
     */
    fun showAlert(message: String?) {
        mainHandler.post {
            val view = alertView ?: return@post
            if (message.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                view.text = "⚠ $message"
                view.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 额度条（**粘性**）。传 null 或空串才消失。
     *
     * 与 [showAlert] 分开的原因：额度问题没解决之前一直有效，
     * 而 `showAlert(null)` 会在下一句译文回来时被调用——如果共用一个 View，
     * 额度提示 1~2 秒就被抹掉了，用户根本来不及看。
     */
    fun showQuotaAlert(message: String?) {
        mainHandler.post {
            val view = quotaView ?: return@post
            if (message.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                view.text = message
                view.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 瞬时提示（**5 秒自动消失**）。用于一次性告知。
     *
     * 不常驻是刻意的：像"已启用预付费额度"这种信息，看一次就够了，
     * 一直挂着反而挡字幕、让人以为出了问题。
     */
    fun showNotice(message: String, durationMs: Long = NOTICE_DURATION_MS) {
        mainHandler.post {
            val view = noticeView ?: return@post
            if (message.isBlank()) {
                view.visibility = View.GONE
                return@post
            }
            noticeToken += 1
            val token = noticeToken
            view.text = message
            view.visibility = View.VISIBLE

            mainHandler.postDelayed({
                // 期间又弹了新提示 → 不要把新的清掉
                if (token == noticeToken) noticeView?.visibility = View.GONE
            }, durationMs)
        }
    }

    /** 收到新字幕。内部节流，可以高频调用。 */
    fun updateCue(cue: SubtitleCue) {
        pendingCue = cue
        if (renderScheduled) return
        renderScheduled = true
        mainHandler.postDelayed({ renderPending() }, RENDER_INTERVAL_MS)
    }

    private fun renderPending() {
        renderScheduled = false
        val cue = pendingCue ?: return
        pendingCue = null

        if (!isShowing) return

        val main = currentView ?: return

        // 没有译文就不再拿原文顶上去。原文显示在"中文字幕"的位置，
        // 用户只会以为翻译只翻了英文，看不出是引擎挂了。
        val hasTranslation = cue.translatedText.isNotBlank()
        val display = if (hasTranslation) cue.translatedText else NO_TRANSLATION_HINT

        if (display == main.text.toString() && cue.isFinal) return

        // seq 变小说明流水线重建过（内录降级外录时 cueSeq 归零），
        // 否则新会话的首句会被误判成"同一句"
        if (cue.seq < lastRenderedSeq) {
            lastRenderedSeq = -1L
        }
        val isSameLine = cue.seq == lastRenderedSeq

        // 上一句挪到灰行。同一句的"原文→译文"更新不算新句子，不能挤走上一行。
        if (!isSameLine) {
            previousView?.let { prev ->
                val last = lastRenderedText
                if (last.isNotBlank() && last != display) {
                    prev.text = last
                    prev.visibility = View.VISIBLE
                }
            }
        }

        main.text = display
        main.alpha = if (cue.isFinal) 1f else style.provisionalAlpha
        // 行数跟着文本长度走：死守 2 行时，超长句缩到 12sp 仍然塞不下，
        // 会被 ellipsize 打成省略号——用户看到的就是"后半句没了"。
        val lines = adaptiveMaxLines(display, style.maxLines)
        main.maxLines = lines
        // 提示语不参与自动缩字号，"翻译中…"本来就短
        main.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (hasTranslation) {
                adaptiveFontSize(display, style.fontSizeSp, lines)
            } else {
                style.fontSizeSp * 0.85f
            },
        )

        sourceView?.let { src ->
            // 有译文时才把原文挂在小字行；没译文时提示语在上面，原文在下面，
            // 用户至少还能对着原文看，不至于完全没信息
            if (style.showSourceText && cue.sourceText.isNotBlank()) {
                src.text = cue.sourceText
                src.visibility = View.VISIBLE
            } else {
                src.visibility = View.GONE
            }
        }

        lastRenderedText = display
        lastRenderedSeq = cue.seq
    }

    /** 长句自动缩字号，避免被截断 */
    /**
     * 按文本长度决定最多显示几行。
     *
     * 背景：原来行数写死 2 行，配合"超长就缩到 12sp"的策略。但 12sp 也有物理极限——
     * 超过约 90 个字符时，2 行怎么都放不下，TextView 会打省略号，用户以为译文被腰斩。
     * 宁可让字幕块变高，也不能让内容消失。
     *
     * 上限 4 行：再多就会遮住大半个屏幕，得不偿失。
     */
    private fun adaptiveMaxLines(text: String, base: Int): Int {
        val capacity = 22f * base
        return when {
            text.length <= capacity -> base
            text.length <= capacity * 1.8f -> base + 1
            else -> base + 2
        }.coerceAtMost(4)
    }

    private fun adaptiveFontSize(text: String, baseSp: Float, maxLines: Int): Float {
        val capacity = 22f * maxLines // 每行约 22 个全角字的经验值
        val length = text.length.toFloat()
        return when {
            length <= capacity -> baseSp
            length <= capacity * 1.4f -> baseSp * 0.9f
            length <= capacity * 1.9f -> baseSp * 0.8f
            else -> (baseSp * 0.72f).coerceAtLeast(12f)
        }
    }

    /** 运行中改样式（设置页实时预览） */
    fun applyStyle(newStyle: SubtitleStyle) {
        style = newStyle
        if (!isShowing) return

        val root = rootView ?: return
        val params = layoutParams ?: return

        root.background = buildBackground()
        quotaView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.8f)
        }
        noticeView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.8f)
        }
        alertView?.apply {
            setTextColor(ALERT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.72f)
        }
        currentView?.apply {
            setTextColor(style.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
            maxLines = style.maxLines
        }
        previousView?.apply {
            setTextColor(style.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp * 0.78f)
        }
        sourceView?.apply {
            setTextColor(style.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sourceFontSizeSp)
            visibility = if (style.showSourceText) View.VISIBLE else View.GONE
        }

        params.y = dp(style.bottomMarginDp.toFloat(), context.resources.displayMetrics.density)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (t: Throwable) {
            Log.w(TAG, "更新字幕窗布局失败", t)
        }
    }

    // ---------------- 拖动 ----------------

    private fun attachDrag(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        // gravity 是 BOTTOM，y 表示距底部的距离，所以向下拖要减
                        params.y = (startY - dy).toInt()
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // 记住用户拖到的位置
                        style = style.copy(
                            bottomMarginDp = (
                                params.y / context.resources.displayMetrics.density
                                ).toInt().coerceAtLeast(0),
                        )
                    }
                    dragging
                }

                else -> false
            }
        }
    }

    private fun dp(value: Float, density: Float): Int = (value * density).toInt()
}
