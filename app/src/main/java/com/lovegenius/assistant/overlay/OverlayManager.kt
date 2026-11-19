package com.lovegenius.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.lovegenius.assistant.R
import com.lovegenius.assistant.service.KbAccessibilityService
import com.lovegenius.assistant.service.OverlayManagerHolder

/**
 * 悬浮球 + 面板的全部窗口管理。
 *
 * 铁律（交接包 11.2 / 1）：任何窗口都不能抢焦点，否则搜狗键盘立刻收起。
 * 所以：
 *  - 悬浮球：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL，且只占 56x56 的小窗
 *  - 面板：  FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL，但允许它自己内部接收点击
 *           （NOT_TOUCH_MODAL 让窗口外的点击穿透给对方 App，聊天输入不受影响）
 */
object OverlayManager : OverlayManagerHolder.Manager {

    private const val TAG = "LoveGeniusOverlay"
    private const val BALL_SIZE_DP = 56
    private const val PREFS = "overlay_state"
    private const val KEY_BALL_X = "ball_x"
    /** v1.4 起存「屏幕绝对 y」；旧版存的是相对默认位的偏移，读到旧值时迁移一次 */
    private const val KEY_BALL_Y = "ball_y_abs"
    private const val KEY_BALL_Y_LEGACY = "ball_y"

    /**
     * 球底到「键盘顶」的距离里，除了输入栏本身，再多让出来的留白（dp）。
     * 原来默认位离输入栏只有 19dp，QQ 的输入栏更高、还带工具条，
     * 球压在上面就"挡界面"。这里整体往上抬，球落在输入栏上方的空白带里。
     */
    private const val BALL_LIFT_DP = 132

    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelCtrl: GeniusPanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private var a11yConnected = false
    private var keyboardVisible = false

    /** 球正在被按下 / 最近一次触摸时间：防止键盘收起时拆窗口打断点击 */
    private var ballTouching = false
    private var ballTouchAt = 0L

    /** 用户手动拖过球之后记下的绝对 y；没拖过是 MIN_VALUE（走默认位） */
    private var savedBallY = Int.MIN_VALUE
    /** 上一次用的默认位，用来判断「换了键盘/换了 App，输入栏高度变了」 */
    private var lastBase = Int.MIN_VALUE
    /** 键盘 top 抖动聚合成一次移动，避免球跟着键盘动画一跳一跳 */
    private var moveDebounce: Runnable? = null
    private var pendingKbTop = 0

    /** 前台包名（微信/QQ 输入栏高度不同，默认位要分别给），1.5 秒缓存一次 */
    private var fgPkg = ""
    private var fgPkgAt = 0L

    /** 主界面进入时绑定，给 OverlayManagerHolder 回调用 */
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
        OverlayManagerHolder.bind { this }
    }

    fun context(): Context? = appCtx
    fun windowManager(): WindowManager? = wm

    fun isBallShowing(): Boolean = ballView != null
    fun isPanelShowing(): Boolean = panelView != null
    fun hasOverlayPermission(): Boolean =
        appCtx?.let { android.provider.Settings.canDrawOverlays(it) } ?: false

    // ------------------------------------------------------------ 键盘联动

    override fun onA11yConnected() {
        a11yConnected = true
        main.post {
            loadSavedBallPos()
            refreshFgPkg(force = true)
            // 重装/重启后无障碍重新绑定，此时键盘可能本来就开着，
            // 收不到 visible=true 的跳变事件 → 球永远不出来。主动同步一次。
            keyboardVisible = KbAccessibilityService.instance?.isImeVisible() == true
            refreshBall()
            if (keyboardVisible && ballView != null) {
                main.postDelayed({ repositionBall() }, 220)
                main.postDelayed({ repositionBall() }, 700)
            }
        }
    }

    override fun onA11yDisconnected() {
        a11yConnected = false
        main.post {
            android.util.Log.w(TAG, "a11y disconnected (panel kept)")
            // 面板保留，用户还能复制/看结果；只把球收起来
            hideBall()
        }
    }

    override fun onKeyboardVisibilityChanged(visible: Boolean) {
        keyboardVisible = visible
        main.post {
            when {
                panelView != null -> Unit
                visible -> {
                    refreshFgPkg(force = true)
                    showBall()
                    main.postDelayed({ repositionBall() }, 200)
                    main.postDelayed({ repositionBall() }, 600)
                    // 搜狗键盘动画收得慢，再补一拍，避免球停在旧的偏下位置
                    main.postDelayed({ repositionBall() }, 1200)
                }
                // 球正在被点击时不拆窗口，否则 touch 事件流中断，点击丢失
                ballView != null && (ballTouching ||
                        android.os.SystemClock.uptimeMillis() - ballTouchAt < 700) -> {
                    // 过了保护期再收一次，避免键盘真的收起后球还在
                    main.postDelayed({
                        if (panelView == null && !keyboardVisible) hideBall()
                    }, 800)
                }
                else -> hideBall()
            }
        }
    }

    // ------------------------------------------------------------ 球位置

    /** 读一次记忆位置（含旧版「相对偏移」的迁移） */
    private fun loadSavedBallPos() {
        val p = sp() ?: return
        if (p.contains(KEY_BALL_Y)) {
            savedBallY = p.getInt(KEY_BALL_Y, Int.MIN_VALUE)
        } else {
            val legacy = p.getInt(KEY_BALL_Y_LEGACY, Int.MIN_VALUE)
            if (legacy != Int.MIN_VALUE) {
                val base = computeBallTop(resolvedKeyboardTop())
                savedBallY = (base + legacy).coerceAtLeast(0)
                p.edit().putInt(KEY_BALL_Y, savedBallY).apply()
            }
        }
        lastBase = if (savedBallY != Int.MIN_VALUE) computeBallTop(resolvedKeyboardTop()) else Int.MIN_VALUE
        android.util.Log.i(TAG, "ball state loaded savedY=$savedBallY lastBase=$lastBase")
    }

    private fun persistBallY(y: Int) {
        savedBallY = y
        sp()?.edit()?.putInt(KEY_BALL_Y, y)?.apply()
    }

    /** 输入栏高度随 App/键盘变，默认位带上包名差异 */
    private fun refreshFgPkg(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - fgPkgAt < 1500) return
        fgPkgAt = now
        val p = runCatching { KbAccessibilityService.instance?.focusedAppPackage() }.getOrNull()
        if (!p.isNullOrEmpty()) fgPkg = p
    }

    /** 这个 App 的输入栏更高（QQ 底部还多一条工具条）→ 默认位再多让 20dp */
    private fun liftDp(): Int =
        if (fgPkg.contains("mobileqq") || fgPkg.endsWith(".tim")) BALL_LIFT_DP + 20
        else BALL_LIFT_DP

    /**
     * 球该放在哪：
     *  - 用户拖过 → 用记下的绝对 y（换键盘/换 App 只整体平移，不重算）
     *  - 没拖过   → 默认位：键盘顶 − 输入栏 − 留白 − 球高
     */
    private fun targetBallTop(kbTop: Int): Int {
        val dm = appCtx?.resources?.displayMetrics ?: return 0
        val size = (BALL_SIZE_DP * dm.density).toInt()
        val maxY = (dm.heightPixels - size).coerceAtLeast(0)
        val base = computeBallTop(kbTop)
        if (savedBallY == Int.MIN_VALUE) {
            lastBase = base
            return base.coerceIn(0, maxY)
        }
        // 真的换了布局（差超过 8% 屏高）才平移，避免每次抖动都把球拖走
        if (lastBase != Int.MIN_VALUE && kotlin.math.abs(base - lastBase) > dm.heightPixels / 12) {
            val shifted = (savedBallY + (base - lastBase)).coerceIn(0, maxY)
            android.util.Log.i(TAG, "ball shift with layout: $savedBallY -> $shifted (base $lastBase -> $base)")
            persistBallY(shifted)
        }
        lastBase = base
        return savedBallY.coerceIn(0, maxY)
    }

    private fun repositionBall() {
        val v = ballView ?: return
        val lp = ballParams ?: return
        val kbNow = resolvedKeyboardTop()
        val want = targetBallTop(kbNow)
        // 抖动过滤：小于约 30px 的变化不动，避免键盘动画期间球来回蹦
        val thresh = (appCtx?.resources?.displayMetrics?.heightPixels ?: 2400) / 90
        if (kotlin.math.abs(lp.y - want) < thresh) return
        lp.y = want
        runCatching { wm?.updateViewLayout(v, lp) }
        android.util.Log.i(TAG, "ball repositioned to y=$want (kbTop=$kbNow)")
    }

    /** 松手吸附：贴右边（左侧是系统返回手势热区，贴过去就点不动了），记住竖直位置 */
    private fun snapBallToEdge(
        lp: WindowManager.LayoutParams,
        size: Int,
        view: View
    ) {
        val dm = appCtx?.resources?.displayMetrics ?: return
        val d = dm.density
        val edge = (12 * d).toInt()
        lp.x = dm.widthPixels - size - edge
        lp.y = lp.y.coerceIn(0, dm.heightPixels - size)
        runCatching { wm?.updateViewLayout(view, lp) }
        // 存绝对 y：以后不管键盘怎么变、进了哪个 App，球都回到用户放的那个高度
        persistBallY(lp.y)
        lastBase = computeBallTop(resolvedKeyboardTop())
        android.util.Log.i(TAG, "ball snapped x=${lp.x} y=${lp.y} (abs)")
    }

    private fun sp() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onKeyboardMoved(top: Int) {
        if (top <= 0) return
        if (ballView == null && panelView == null) return
        pendingKbTop = top
        // 键盘动画/候选词刷新会连发几十个事件，全部压成「最后一次稳定后算一次」，
        // 否则球会被一帧一帧地推着上下跳。
        moveDebounce?.let { main.removeCallbacks(it) }
        val r = Runnable {
            refreshFgPkg()
            onKbTopSettled()
        }
        moveDebounce = r
        main.postDelayed(r, 320)
    }

    private fun onKbTopSettled() {
        val kbNow = resolvedKeyboardTop()
        repositionBall()
        // 面板下沿要一直贴着键盘上沿，否则键盘高度变一次面板就悬空
        val pv = panelView
        val plp = panelParams
        if (pv != null && plp != null) {
            val dm = appCtx?.resources?.displayMetrics
            if (dm != null) {
                val margin = (10 * dm.density).toInt()
                val barTop = kbNow - (63 * dm.density).toInt()
                val bottomMargin = ((dm.heightPixels - barTop) + margin.toFloat()).toInt()
                if (plp.y != bottomMargin) {
                    plp.y = bottomMargin
                    runCatching { wm?.updateViewLayout(pv, plp) }
                }
            }
        }
    }

    private fun refreshBall() {
        // 键盘可见才显示；用户手动固定的球除外（先不做固定，保持简单）
        if (keyboardVisible && a11yConnected) showBall() else hideBall()
    }

    // ------------------------------------------------------------ 悬浮球

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showBall() {
        if (ballView != null) return
        val ctx = appCtx ?: run {
            android.util.Log.w(TAG, "showBall: no context")
            return
        }
        if (!hasOverlayPermission()) {
            android.util.Log.w(TAG, "showBall: no overlay permission")
            return
        }
        if (KbAccessibilityService.instance == null) {
            android.util.Log.w(TAG, "showBall: a11y not connected")
            return
        }

        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()

        val view = LayoutInflater.from(ctx).inflate(R.layout.ball, null)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = ctx.resources.displayMetrics
            val edge = (12 * density).toInt()
            // 只贴右边：左侧是系统返回手势热区，放过去点不动
            x = dm.widthPixels - size - edge
            refreshFgPkg(force = fgPkg.isEmpty())
            y = targetBallTop(resolvedKeyboardTop())
            android.util.Log.i(TAG, "ball pos init: x=$x y=$y pkg=$fgPkg savedY=$savedBallY")
        }

        // 拖动 + 点击
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, ev ->
            android.util.Log.i(TAG, "ball touch action=${ev.action} at ${ev.rawX},${ev.rawY}")
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    ballTouching = true
                    ballTouchAt = android.os.SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm?.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ballTouching = false
                    ballTouchAt = android.os.SystemClock.uptimeMillis()
                    if (!moved) togglePanel() else snapBallToEdge(lp, size, view)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ballTouching = false
                    ballTouchAt = android.os.SystemClock.uptimeMillis()
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm?.addView(view, lp)
            ballView = view
            ballParams = lp
            android.util.Log.i(TAG, "ball shown at y=${lp.y} kbTop=${KbAccessibilityService.keyboardTop()}")
        }.onFailure {
            android.util.Log.e(TAG, "ball addView failed", it)
            ballView = null
            ballParams = null
        }
    }

    fun hideBall() {
        val v = ballView ?: return
        runCatching { wm?.removeView(v) }
        ballView = null
    }

    private fun computeBallTop(kbTop: Int): Int {
        val d = appCtx?.resources?.displayMetrics?.density ?: 3f
        val size = (BALL_SIZE_DP * d).toInt()
        // 键盘上方依次是：输入栏（约 50dp）+ 用户要求的留白 + 采样余量。
        // 球底要明显高于输入栏，否则会压住微信的表情/加号/发送，QQ 上更明显。
        val reserved = ((82 + liftDp()) * d).toInt()
        val gap = (8 * d).toInt()
        return (kbTop - reserved - size - gap).coerceAtLeast(0)
    }

    private var lastGoodKbTop = 0

    /**
     * 键盘顶边。ColorOS 的 IME 窗口 bounds 会来回抖（1711 / 140 / 1181），
     * 规则：只有落在屏幕 50%~80% 的值才算有效，并记为 lastGoodKbTop；
     * 拿到无效值时沿用上次有效值，没有就用「屏幕 62%」兜底。
     */
    private fun resolvedKeyboardTop(): Int {
        val screenH = appCtx?.resources?.displayMetrics?.heightPixels ?: 2400
        val raw = KbAccessibilityService.keyboardTop()
        val inRange = raw in (screenH * 5 / 10) until (screenH * 8 / 10)
        if (inRange) lastGoodKbTop = convergeKbTop(lastGoodKbTop, raw, screenH)
        val top = if (lastGoodKbTop > 0) lastGoodKbTop else (screenH * 62 / 100)
        android.util.Log.d(TAG, "resolvedKeyboardTop raw=$raw -> $top (screenH=$screenH)")
        return top
    }

    /**
     * 键盘弹出是动画：中途会报一个更大的 top（键盘还没顶到位），
     * 直接采用会让球/面板偏低 100px、压住输入栏。取稳定后的较小值；
     * 偏差超过 5% 屏高说明真换了键盘布局，才认新值。
     */
    private fun convergeKbTop(prev: Int, raw: Int, screenH: Int): Int {
        if (prev <= 0) return raw
        return if (kotlin.math.abs(raw - prev) < screenH / 20) minOf(raw, prev) else raw
    }

    // ------------------------------------------------------------ 面板

    @SuppressLint("InflateParams")
    fun togglePanel() {
        android.util.Log.i(TAG, "togglePanel: panel=${panelView != null}")
        if (panelView != null) hidePanel() else showPanel()
    }

    fun showPanel() {
        if (panelView != null) return
        val ctx = appCtx ?: return
        if (!hasOverlayPermission()) {
            Toast.makeText(ctx, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        val margin = (10 * dm.density).toInt()

        val ctrl = GeniusPanelView(ctx)
        panelCtrl = ctrl
        val view = try {
            ctrl.build()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "panel build failed", t)
            panelCtrl = null
            Toast.makeText(ctx, "面板构建失败：" + (t.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }

        val kbTop = resolvedKeyboardTop()
        // 面板下沿要停在「输入栏上方」，不能压在输入栏上：
        // 否则面板会盖住微信的输入框，自动点输入栏唤键盘也会点到面板自己。
        val barTop = kbTop - (63 * dm.density).toInt()
        val bottomMargin = ((dm.heightPixels - barTop) + margin.toFloat()).toInt()
        // 面板：默认 NOT_FOCUSABLE —— 不抢输入法焦点，键盘就留在微信里不会收起；
        // NOT_TOUCH_MODAL 让面板外的触摸继续交给下面的 App。
        // 面板里的输入框被点中时，再由 GeniusPanelView 回调切回可获焦（见 setPanelImeFocus）。
        val lp = WindowManager.LayoutParams(
            dm.widthPixels - margin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = margin
            y = bottomMargin
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        ctrl.onClose = { hidePanel() }
        ctrl.onImeRequest = { need -> setPanelImeFocus(need) }

        try {
            wm?.addView(view, lp)
            panelView = view
            panelParams = lp
            hideBall()      // 面板占满屏宽，球留在上面只会挡按钮
            android.util.Log.i(TAG, "panel shown wrap bottomY=$bottomMargin x=$margin kbTop=$kbTop")
            ctrl.autoRead()
            // 键盘若被系统收起：面板会悬在半空、按钮位置也飘，主动点一下输入栏把键盘叫回来
            listOf(300L, 900L).forEach { dl ->
                main.postDelayed({
                    if (panelView != null) {
                        val svc = KbAccessibilityService.instance
                        if (svc != null && !svc.isImeVisible()) {
                            val r = svc.inputBarRectNow()
                            svc.tapAt(r.centerX(), r.centerY())
                            android.util.Log.i(TAG,
                                "panel: keyboard missing, tap input bar ${r.toShortString()}")
                        }
                    }
                }, dl)
            }
            // 若面板出现导致键盘收起，恢复一次输入法焦点（多点几次，覆盖动画时序）
            listOf(120L, 350L, 700L).forEach { delay ->
                main.postDelayed({
                    if (panelView != null) {
                        val ok = KbAccessibilityService.instance?.restoreImeFocus()
                        android.util.Log.i(TAG, "restoreImeFocus@$delay -> $ok")
                    }
                }, delay)
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "panel addView failed", t)
            panelView = null
            panelCtrl = null
            Toast.makeText(ctx, "面板打不开：" + (t.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 面板里点输入框 → 临时可获焦（键盘归面板）；点其它地方再交还给聊天 App */
    private fun setPanelImeFocus(need: Boolean) {
        val pv = panelView ?: return
        val plp = panelParams ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val want = if (need) base
        else (base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (plp.flags == want) return
        plp.flags = want
        runCatching { wm?.updateViewLayout(pv, plp) }
        android.util.Log.i(TAG, "panel ime focus=$need")
        if (need) main.postDelayed({ panelCtrl?.focusInput() }, 90)
    }

    fun hidePanel() {
        val v = panelView ?: return
        android.util.Log.i(TAG, "hidePanel from: " + Throwable().stackTrace
            .take(6).joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" })
        runCatching { wm?.removeView(v) }
        panelCtrl?.detach()
        panelView = null
        panelCtrl = null
        panelParams = null
        // 面板收起后键盘还在，就把球放回来
        if (keyboardVisible && a11yConnected) {
            showBall()
            main.postDelayed({ repositionBall() }, 200)
        }
    }

    fun destroyAll() {
        hidePanel()
        hideBall()
    }

    /** 调试/自检入口：不依赖悬浮球，直接在当前界面拉出面板 */
    fun showPanelForTest() {
        // 不清空 lastGoodKbTop：键盘可能正因为面板出现而短暂消失
        showPanel()
    }
}
