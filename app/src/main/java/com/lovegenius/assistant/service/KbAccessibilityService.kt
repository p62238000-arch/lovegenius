package com.lovegenius.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 唯一和「别的 App」打交道的服务：
 *  1) 判断输入法键盘是否在屏幕上、键盘顶边在哪（给悬浮球定位用）
 *  2) 读当前可编辑输入框的文字
 *  3) 把建议文字写回输入框（失败自动降级为剪贴板）
 *
 * 注意：无障碍服务自己不能持有界面，所以 UI 都在 OverlayManager 里，
 * 这里只做状态广播 + 提供静态方法给悬浮层调用。
 */
class KbAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LoveGeniusA11y"

        @Volatile
        var instance: KbAccessibilityService? = null
            private set

        /** 键盘可见性 + 键盘顶边（屏幕坐标，px） */
        fun isKeyboardVisible(): Boolean = instance?.imeRect() != null

        fun keyboardTop(): Int = instance?.imeRect()?.top ?: -1

        fun keyboardBounds(): Rect? = instance?.imeRect()

        fun isRunning(): Boolean = instance != null
    }

    private var lastImeVisible = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected")
        // 无障碍进程可能被系统单独拉起：这里主动初始化悬浮层
        com.lovegenius.assistant.data.Prefs.init(this)
        com.lovegenius.assistant.overlay.OverlayManager.attach(this)
        OverlayManagerHolder.get()?.onA11yConnected()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        cachedTarget?.recycle()
        cachedTarget = null
        OverlayManagerHolder.get()?.onA11yDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        OverlayManagerHolder.get()?.onA11yDisconnected()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 事件很密，这里只做一个轻量判断，真正的窗口遍历放到 60ms 后
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastCheckMs < 80) return
                lastCheckMs = now
                syncKeyboardState()
            }
            else -> Unit
        }
    }

    private var lastCheckMs = 0L

    private fun syncKeyboardState() {
        val visible = findImeWindow() != null
        val top = keyboardTop()
        val screenH = resources.displayMetrics.heightPixels
        // 记下有效值，供 guessInputRect() 用（原始值会抖成状态栏高度）
        if (top in (screenH * 5 / 10) until (screenH * 8 / 10)) {
            if (lastGoodKbTop <= 0) lastGoodKbTop = top
            else if (kotlin.math.abs(top - lastGoodKbTop) < screenH / 20) {
                lastGoodKbTop = minOf(top, lastGoodKbTop)
            } else {
                lastGoodKbTop = top
            }
        }

        if (visible != lastImeVisible) {
            lastImeVisible = visible
            Log.i(TAG, "keyboard visible=$visible top=$top")
            OverlayManagerHolder.get()?.onKeyboardVisibilityChanged(visible)
        } else if (visible) {
            OverlayManagerHolder.get()?.onKeyboardMoved(top)
        }
    }

    /** 找输入法窗口：TYPE_INPUT_METHOD，或标题/包名里带 sogouoem 的窗口 */
    private fun findImeWindow(): AccessibilityWindowInfo? {
        val wins = try {
            windows
        } catch (t: Throwable) {
            null
        } ?: return null

        var fallback: AccessibilityWindowInfo? = null
        for (w in wins) {
            if (w == null) continue
            try {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return w
                val root = w.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.contains("sogou", ignoreCase = true) && !pkg.contains("lovegenius")) {
                    fallback = w
                }
            } catch (t: Throwable) {
                // 窗口被回收，跳过
            }
        }
        return fallback
    }

    /**
     * 键盘顶边：优先用 AccessibilityWindowInfo.bounds（API 34+），
     * 老系统退回「遍历 IME 窗口根节点算 bounds」。
     */
    fun imeRect(): Rect? {
        val w = findImeWindow() ?: return null
        return windowBounds(w)
    }

    private fun windowBounds(w: AccessibilityWindowInfo): Rect? {
        val frame = Rect()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                @Suppress("NewApi")
                w.getBoundsInScreen(frame)
            } catch (t: Throwable) {
                frame.setEmpty()
            }
        }

        // ColorOS 上 IME 窗口 frame 常给整屏，真正的键盘区域要靠
        // 「IME 窗口里最靠上的可见子节点」来定位顶边。
        val root = try { w.root } catch (t: Throwable) { null }
        val childTop = root?.let { minVisibleTop(it) } ?: -1
        try { root?.recycle() } catch (t: Throwable) { }

        val screenH = resources.displayMetrics.heightPixels
        if (childTop in 1 until screenH) {
            val bottom = if (!frame.isEmpty) frame.bottom else screenH
            return Rect(0, childTop, resources.displayMetrics.widthPixels, bottom)
        }
        if (!frame.isEmpty && frame.height() < screenH * 7 / 10) return frame
        return null
    }

    /** 递归找 IME 窗口内「可见节点」的最小 top（即键盘上沿） */
    private fun minVisibleTop(root: AccessibilityNodeInfo): Int {
        var minTop = Int.MAX_VALUE
        val screenH = resources.displayMetrics.heightPixels
        // 键盘顶一定在屏幕下半部分。搜狗内部有些元素报 top≈1181（屏幕 43%），
        // 那是隐藏层，必须排除，否则球会飘到聊天区中间。
        val floor = screenH * 5 / 10
        val ceil = screenH * 8 / 10

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        val r = Rect()
        while (queue.isNotEmpty() && guard++ < 3000) {
            val n = queue.removeFirst()
            val visible = try { n.isVisibleToUser } catch (t: Throwable) { false }
            if (visible) {
                try {
                    n.getBoundsInScreen(r)
                    if (r.height() > 0 && r.top in floor until ceil && r.top < minTop) {
                        minTop = r.top
                    }
                } catch (t: Throwable) {
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (t: Throwable) { null }
                if (c != null) queue.add(c)
            }
            if (n !== root) try { n.recycle() } catch (t: Throwable) { }
        }
        return if (minTop == Int.MAX_VALUE) -1 else minTop
    }

    // ---------------------------------------------------------------- 读输入框

    /**
     * 优先从「当前活动窗口」里找可编辑节点；找不到就遍历所有窗口。
     * 返回 null 表示没拿到（调用方降级到剪贴板）。
     */
    fun readFocusedText(): String? {
        val node = findEditableNode()
        if (node == null) {
            dumpWindows("read-null")
            dumpActiveTree("read-null")
            return null
        }
        return try {
            var text = node.text?.toString()
            if (text.isNullOrEmpty()) {
                // ACTION_FOCUS + 再读一次：部分 App（含搜狗自绘输入框）焦点没落上时 text 为空
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                text = node.text?.toString()
            }
            node.recycle()
            text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 面板弹出后如果键盘被系统收起，用它把焦点抢回来让键盘重新弹出。
     * 只动可编辑节点，不触碰别的东西。
     */
    fun restoreImeFocus(): Boolean {
        val node = findEditableNode() ?: return false
        val ok = try {
            // 只做 FOCUS：ACTION_CLICK 会让微信重排布局甚至把焦点弄丢
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (t: Throwable) {
            false
        } finally {
            try { node.recycle() } catch (t: Throwable) { }
        }
        Log.i(TAG, "restoreImeFocus ok=$ok")
        return ok
    }

    /**
     * 调试用：把当前活动窗口的节点树（类名/文案/editable/bounds）打到 logcat。
     * 排查微信这类自绘输入框时非常有用。
     */
    fun dumpActiveTree(tag: String = "active") {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "[$tag] rootInActiveWindow = null; windows=${windows?.size}")
            return
        }
        val sb = StringBuilder()
        walk(root, sb, 0, 0)
        root.recycle()
        // 独立 tag，方便 adb logcat -s LGTREE 过滤
        val lines = sb.toString().split("\n")
        Log.i("LGTREE", "[$tag] nodes=${lines.size} windows=${windows?.size}")
        lines.take(200).forEach { Log.i("LGTREE", it) }
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, counter: Int): Int {
        if (depth > 12) return counter
        val r = Rect()
        n.getBoundsInScreen(r)
        val txt = n.text?.toString()?.replace("\n", "⏎") ?: ""
        val desc = n.contentDescription?.toString() ?: ""
        sb.append("  ".repeat(depth))
            .append(n.className).append(" id=").append(n.viewIdResourceName)
            .append(" ed=").append(n.isEditable)
            .append(" foc=").append(n.isFocused)
            .append(" txt=").append(txt.take(40))
            .append(" desc=").append(desc.take(20))
            .append(" b=").append(r.toShortString())
            .append('\n')
        var c = counter + 1
        if (c > 500) return c
        for (i in 0 until n.childCount) {
            val ch = try { n.getChild(i) } catch (t: Throwable) { null } ?: continue
            c = walk(ch, sb, depth + 1, c)
            ch.recycle()
            if (c > 500) break
        }
        return c
    }

    /**
     * 找「对方 App」的可编辑输入框。
     *
     * 关键：必须跳过我们自己的窗口。面板是可获焦窗口，打开时它就是活动窗口，
     * 不排除的话会把文字写进面板自己的 EditText（踩过这个坑）。
     */
    private fun findEditableNode(): AccessibilityNodeInfo? {
        val self = packageName

        // 1) 先看当前活动窗口（多数情况就是聊天 App）
        val root = rootInActiveWindow
        if (root != null) {
            val rootPkg = root.packageName?.toString().orEmpty()
            if (rootPkg != self) {
                val n = findEditableIn(root)
                root.recycle()
                if (n != null) return n
            } else {
                root.recycle()
            }
        }

        // 2) 遍历所有窗口（跳过 IME 和自己），按层级从上往下找
        val wins = try { windows } catch (t: Throwable) { null } ?: return null
        val candidates = wins.filterNotNull()
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .sortedByDescending { it.layer }
        for (w in candidates) {
            val r = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = r.packageName?.toString().orEmpty()
            if (pkg == self) {
                r.recycle()
                continue
            }
            val n = findEditableIn(r)
            r.recycle()
            if (n != null) return n
        }
        return null
    }

    /** 把所有窗口的 root 包名/类名/子节点数打到 LGTREE，排查"读不到"用 */
    fun dumpWindows(tag: String = "windows") {
        val wins = try { windows } catch (t: Throwable) { null }
        Log.i("LGTREE", "[$tag] windows=${wins?.size}")
        wins?.forEach { w ->
            if (w == null) return@forEach
            val r = try { w.root } catch (t: Throwable) { null }
            Log.i("LGTREE", "win type=${w.type} layer=${w.layer} active=${w.isActive} " +
                    "focused=${w.isFocused} pkg=${r?.packageName} cls=${r?.className} " +
                    "children=${r?.childCount}")
            r?.recycle()
        }
    }

    /** 深度优先找 isEditable 且有尺寸的节点（可见性放宽，微信自定义输入框常报 false） */
    private fun findEditableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000) {
            val n = queue.removeFirst()
            val editable = try { n.isEditable } catch (t: Throwable) { false }
            if (editable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    return AccessibilityNodeInfo.obtain(n)
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (t: Throwable) { null }
                if (c != null) queue.add(c)
            }
            if (n !== root) n.recycle()
        }
        return null
    }

    // ---------------------------------------------------------------- 回填

    /**
     * 面板打开期间缓存「对方 App」的输入框节点。
     *
     * 微信的行为很特别：输入框拿到焦点后会把整棵 view 树屏蔽（root=null, children=0），
     * 但面板弹出、焦点被抢走的那一刻，微信窗口反而是可访问的。
     * 所以必须在那个时间点抓住节点，之后再用它写。
     */
    private var cachedTarget: AccessibilityNodeInfo? = null
    private var cachedAt = 0L
    /** 缓存节点时的屏幕坐标，写入失败时长按这里弹出粘贴菜单 */
    var cachedRect: android.graphics.Rect? = null
        private set

    fun cacheTargetNode(): Boolean {
        val n = findEditableNode() ?: return false
        if (n.packageName?.toString() == packageName) {
            n.recycle()
            return false
        }
        cachedTarget?.recycle()
        cachedTarget = n
        cachedAt = android.os.SystemClock.uptimeMillis()
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        cachedRect = r
        Log.i(TAG, "cacheTargetNode: pkg=${n.packageName} cls=${n.className} " +
                "rect=${r.toShortString()} " +
                "actions=[${n.actionList.joinToString(",") { it.id.toString() }}]")
        return true
    }

    fun clearCachedTarget() {
        cachedTarget?.recycle()
        cachedTarget = null
        cachedAt = 0
        cachedRect = null
    }

    /**
     * 把文字写进刚才那个输入框。
     * 顺序：
     *  1) ACTION_SET_TEXT（普通 App 直接成功）
     *  2) ACTION_PASTE（微信/QQ 多数吃这条）
     * 返回 true 表示写进去了；false 时调用方提示「已复制，长按粘贴」。
     */
    fun fillText(text: String): Boolean {
        // 优先用面板打开时缓存下来的节点（微信在输入框获焦后会屏蔽节点树）
        val fromCache = cachedTarget != null
        val node = cachedTarget ?: findEditableNode() ?: run {
            Log.w(TAG, "fillText: 没有找到可编辑节点")
            dumpWindows("fill-null")
            return false
        }

        // 诊断：这个输入框到底支持哪些动作
        val actions = node.actionList.joinToString(",") { it.id.toString() }
        Log.i(TAG, "fillText: cache=$fromCache node=${node.className} pkg=${node.packageName} " +
                "editable=${node.isEditable} focused=${node.isFocused} actions=[$actions]")

        // 1) 直接 SET_TEXT
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            if (!fromCache) node.recycle()
            Log.i(TAG, "fillText: SET_TEXT 成功")
            return true
        }

        // 2) 写剪贴板 + 在同一个节点上粘贴（微信的输入框 SET_TEXT 不给写，但吃粘贴）
        val pasted = pasteInto(node, text)
        if (!fromCache) node.recycle()
        Log.i(TAG, "fillText: SET_TEXT 失败, PASTE=$pasted")
        return pasted
    }

    /** 先写剪贴板（校验真的写进去了），再在指定节点上执行粘贴 */
    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!writeClipboardVerified(text)) return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            false
        }
    }

    /** 写剪贴板并确认没被输入法抢掉 */
    private fun writeClipboardVerified(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        repeat(3) {
            cm.setPrimaryClip(ClipData.newPlainText("lovegenius", text))
            val now = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (now == text) return true
            Thread.sleep(30)
        }
        return false
    }

    /**
     * 猜测当前聊天输入框的屏幕坐标。
     *
     * 微信把整棵节点树对无障碍屏蔽（pkg=null），拿不到 EditText 的 bounds。
     * 但聊天输入框位置很固定：就在键盘正上方、高度约 140px 的那一条。
     * 所以用「键盘上沿往上推」来估，供长按弹粘贴菜单使用。
     */
    fun guessInputRect(): android.graphics.Rect? {
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels
        // 原始 keyboardTop() 会抖成 140（状态栏高度），必须用「最近一次有效值」
        val raw = keyboardTop()
        if (raw in (screenH * 5 / 10) until (screenH * 8 / 10)) {
            if (lastGoodKbTop <= 0) lastGoodKbTop = raw
            else if (kotlin.math.abs(raw - lastGoodKbTop) < screenH / 20) {
                lastGoodKbTop = minOf(raw, lastGoodKbTop)
            } else {
                lastGoodKbTop = raw
            }
        }
        val kbTop = if (lastGoodKbTop > 0) lastGoodKbTop else return null

        val barH = (50 * dm.density).toInt()          // 输入栏高度
        val top = kbTop - barH - (10 * dm.density).toInt()
        return android.graphics.Rect(0, top, dm.widthPixels, kbTop)
    }

    /** 最近一次落在合理区间的键盘顶边 */
    @Volatile
    var lastGoodKbTop: Int = 0
        private set

    /**
     * 当前聊天输入栏的矩形。
     * 键盘在，就用键盘顶往上推；键盘收起了，输入栏会落回屏幕底部，按底部算。
     * 微信把节点树屏蔽时，这是唯一能拿到「输入框在哪」的办法。
     */
    fun inputBarRectNow(): android.graphics.Rect {
        val dm = resources.displayMetrics
        val d = dm.density
        val barH = (50 * d).toInt()
        val raw = keyboardTop()
        val valid = raw in (dm.heightPixels * 5 / 10) until (dm.heightPixels * 8 / 10)
        val bottom = if (valid) raw else dm.heightPixels - (30 * d).toInt()
        return android.graphics.Rect(0, bottom - barH, dm.widthPixels, bottom)
    }

    /**
     * 绕过 Android 10+ 的「后台应用不能读剪贴板」限制。
     *
     * 面板所在的进程在后台，直接读剪贴板会拿到空串（clipLen=0）。
     * 办法：让面板自己的输入框执行 ACTION_PASTE —— 粘贴动作发生在有焦点的窗口里，
     * 系统才放行，然后把它贴进去的文字读回来。
     */
    fun pasteIntoOwnInput(): String? {
        val wins = try { windows } catch (t: Throwable) { null } ?: return null
        for (w in wins.filterNotNull()) {
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = try { root.packageName?.toString().orEmpty() } catch (t: Throwable) { "" }
            if (pkg != packageName) {
                try { root.recycle() } catch (t: Throwable) { }
                continue
            }
            val node = findEditableIn(root)
            try { root.recycle() } catch (t: Throwable) { }
            if (node == null) continue
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Thread.sleep(150)
                val txt = node.text?.toString().orEmpty()
                Log.i(TAG, "pasteIntoOwnInput ok=$ok textLen=${txt.length} text=${txt.take(30)}")
                if (txt.isNotBlank()) return txt
            } catch (t: Throwable) {
                Log.w(TAG, "pasteIntoOwnInput failed: ${t.message}")
            } finally {
                try { node.recycle() } catch (t: Throwable) { }
            }
        }
        return null
    }

    /**
     * 点一下聊天输入栏，把焦点和键盘叫回来（微信树读不到时只能靠坐标）。
     */
    fun tapInputBar(): Boolean {
        val r = cachedRect ?: guessInputRect() ?: return false
        return tapAt(r.centerX(), (r.top + r.bottom) / 2)
    }

    /**
     * 在输入法窗口里找「来自剪贴板 / 剪贴板 / 粘贴」这类条目并点它。
     *
     * 为什么点键盘而不是点微信：微信在输入框获焦后把整棵树对无障碍屏蔽
     * （pkg=null children=0），但搜狗键盘自己的窗口完全可读，它会在输入栏下面
     * 显示「来自剪贴板」气泡。点这个气泡 = 把剪贴板内容上屏，绕开微信树。
     */
    fun clickClipboardEntry(): Boolean {
        val wins = try { windows } catch (t: Throwable) { null } ?: return false
        // IME 窗口优先，其次扫其它窗口（系统/微信的「粘贴」菜单是独立 popup）
        val ordered = wins.filterNotNull().sortedByDescending { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) 2 else 1
        }
        for (w in ordered) {
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = try { root.packageName?.toString().orEmpty() } catch (t: Throwable) { "" }
            val hit = findClickableByKeyword(root)
            try { root.recycle() } catch (t: Throwable) { }
            if (hit != null) {
                val r = Rect()
                try { hit.getBoundsInScreen(r) } catch (t: Throwable) { r.setEmpty() }
                val label = (hit.text?.toString().orEmpty() + "|" +
                        hit.contentDescription?.toString().orEmpty())
                var ok = false
                try { ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (t: Throwable) { }
                if (!ok && !r.isEmpty) ok = tapAt(r.centerX(), r.centerY())
                Log.i("LGTAP", "clipboard entry pkg=$pkg label=$label " +
                        "rect=${r.toShortString()} clicked=$ok")
                try { hit.recycle() } catch (t: Throwable) { }
                if (ok) return true
            }
        }
        Log.w("LGTAP", "clipboard entry: 没找到剪贴板/粘贴候选")
        dumpImeTreeThrottled()
        return false
    }

    // ===================== 长按菜单：按文案定位（v1.3.3） =====================
    //
    // 微信把聊天页节点树屏蔽了，但长按输入框弹出的 FloatingToolbar 是独立 popup 窗口，
    // 它的文本无障碍能读到。所以「粘贴 / 全选」按文案点，比写死 (width*0.095, kbTop-68dp) 稳：
    // 坐标随键盘高度、字号、系统版本漂，文案不会。坐标只留作最后的兜底。

    private class MenuHit(
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val label: String,
        val winType: Int
    )

    /** 长按菜单项文案 */
    private val PASTE_WORDS = listOf("粘贴", "粘貼", "Paste")
    private val SELECT_ALL_WORDS = listOf("全选", "全選", "Select all")

    /**
     * 找长按菜单里的某一项并点它。
     *
     * @param keywords  命中其一即可（忽略大小写、包含匹配）
     * @param anchorY   长按点的 y。菜单一定出现在这附近，用它过滤掉聊天记录里的同名文字
     * @param maxWaitMs 轮询等菜单弹出（菜单有 200~600ms 的动画延迟）
     * @return 是否点中
     */
    fun tapMenuEntry(
        keywords: List<String>,
        anchorY: Int = -1,
        maxWaitMs: Long = 1100
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + maxWaitMs
        var lastLog = 0L
        while (android.os.SystemClock.uptimeMillis() <= deadline) {
            val hit = findMenuNode(keywords, anchorY)
            if (hit != null) {
                var ok = false
                try {
                    ok = hit.node.isClickable &&
                            hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (t: Throwable) {
                    ok = false
                }
                if (!ok) ok = tapAt(hit.rect.centerX(), hit.rect.centerY())
                Log.i("LGTAP", "menu tap label='${hit.label}' win=${hit.winType} " +
                        "rect=${hit.rect.toShortString()} clicked=$ok")
                try { hit.node.recycle() } catch (t: Throwable) { }
                if (ok) return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLog > 600) {
                lastLog = now
                dumpMenuCandidates(keywords, anchorY)
            }
            Thread.sleep(120)
        }
        Log.w("LGTAP", "menu tap: 菜单里没有 ${keywords.joinToString("/")}")
        dumpMenuCandidates(keywords, anchorY)
        return false
    }

    /** 依次尝试「全选 → 粘贴」，把输入框里已有的旧文字整段替换掉 */
    fun pasteReplacing(anchorY: Int): Boolean {
        val selected = tapMenuEntry(SELECT_ALL_WORDS, anchorY, 700)
        if (selected) {
            Thread.sleep(180)
            Log.i("LGTAP", "已全选旧文字，接着粘贴覆盖")
        }
        return tapMenuEntry(PASTE_WORDS, anchorY, 900)
    }

    /**
     * 在所有窗口里找菜单项。
     * 过滤规则：
     *  - 跳过自己的窗口
     *  - 尺寸得像一个按钮（高度 18px ~ 屏高/6）
     *  - 优先 TYPE_SYSTEM / TYPE_INPUT_METHOD 窗口；普通应用窗口里的命中
     *    必须紧贴长按位置（±170dp），否则就是聊天记录里的同名文字
     */
    private fun findMenuNode(keywords: List<String>, anchorY: Int): MenuHit? {
        val self = packageName
        val wins = try { windows } catch (t: Throwable) { null } ?: return null
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels
        val nearPx = (170 * dm.density).toInt()
        val r = Rect()
        for (w in wins.filterNotNull()) {
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = try { root.packageName?.toString().orEmpty() } catch (t: Throwable) { "" }
            if (pkg == self) {
                try { root.recycle() } catch (t: Throwable) { }
                continue
            }
            val systemLike = w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 2500) {
                val n = queue.removeFirst()
                val label = ((n.text?.toString().orEmpty()) + " " +
                        (n.contentDescription?.toString().orEmpty())).trim()
                if (label.isNotEmpty() &&
                    keywords.any { label.equals(it, ignoreCase = true) || label.contains(it, ignoreCase = true) }
                ) {
                    try { n.getBoundsInScreen(r) } catch (t: Throwable) { r.setEmpty() }
                    val sizeOk = !r.isEmpty && r.height() in 18..(screenH / 6) && r.width() > 0
                    val nearOk = systemLike || anchorY < 0 ||
                            kotlin.math.abs(r.centerY() - anchorY) <= nearPx
                    if (sizeOk && nearOk) {
                        return MenuHit(AccessibilityNodeInfo.obtain(n), Rect(r), label, w.type)
                    }
                }
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (t: Throwable) { null }
                    if (c != null) queue.add(c)
                }
                if (n !== root) try { n.recycle() } catch (t: Throwable) { }
            }
            try { root.recycle() } catch (t: Throwable) { }
        }
        return null
    }

    /** 调试：把当前所有窗口里跟菜单关键词沾边的节点打出来（tag LGMENU） */
    fun dumpMenuCandidates(keywords: List<String>, anchorY: Int) {
        val self = packageName
        val wins = try { windows } catch (t: Throwable) { null } ?: return
        val r = Rect()
        for (w in wins.filterNotNull()) {
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = try { root.packageName?.toString().orEmpty() } catch (t: Throwable) { "" }
            if (pkg == self) {
                try { root.recycle() } catch (t: Throwable) { }
                continue
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            var printed = 0
            while (queue.isNotEmpty() && guard++ < 1200 && printed < 24) {
                val n = queue.removeFirst()
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                if (t.isNotEmpty() || d.isNotEmpty()) {
                    try { n.getBoundsInScreen(r) } catch (e: Throwable) { r.setEmpty() }
                    Log.i("LGMENU", "win=${w.type} pkg=$pkg cls=${n.className} " +
                            "t='$t' d='$d' click=${n.isClickable} rect=${r.toShortString()}")
                    printed++
                }
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (e: Throwable) { null }
                    if (c != null) queue.add(c)
                }
                if (n !== root) try { n.recycle() } catch (e: Throwable) { }
            }
            try { root.recycle() } catch (t: Throwable) { }
        }
        Log.i("LGMENU", "---- candidates above, anchorY=$anchorY want=${keywords.joinToString("/")}")
    }

    /** 当前前台应用的包名（判断是不是微信，决定要不要省掉节点写入那几次重试） */
    fun focusedAppPackage(): String {
        val self = packageName
        val wins = try { windows } catch (t: Throwable) { null } ?: return ""
        for (w in wins.filterNotNull()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (!w.isFocused && !w.isActive) continue
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            val pkg = try { root.packageName?.toString().orEmpty() } catch (t: Throwable) { "" }
            try { root.recycle() } catch (t: Throwable) { }
            if (pkg != self && pkg.isNotEmpty()) return pkg
        }
        return ""
    }

    /** 键盘现在是不是真的在屏幕上 */
    fun isImeVisible(): Boolean = findImeWindow() != null

    private var lastImeDumpMs = 0L

    /** 调试：搜狗键盘树到底读不读得到、节点文案是什么（每 8 秒最多一次） */
    private fun dumpImeTreeThrottled() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastImeDumpMs < 8000) return
        lastImeDumpMs = now
        val wins = try { windows } catch (t: Throwable) { null } ?: return
        for (w in wins.filterNotNull()) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = try { w.root } catch (t: Throwable) { null } ?: continue
            Log.i("LGTREE", "ime nodes=${root.childCount} pkg=${root.packageName} cls=${root.className}")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            val r = Rect()
            while (queue.isNotEmpty() && guard++ < 120) {
                val n = queue.removeFirst()
                try { n.getBoundsInScreen(r) } catch (e: Throwable) { r.setEmpty() }
                val t = n.text?.toString().orEmpty()
                val dsc = n.contentDescription?.toString().orEmpty()
                if (t.isNotEmpty() || dsc.isNotEmpty()) {
                    Log.i("LGTREE", "  cls=${n.className} t=$t d=$dsc " +
                            "rect=${r.toShortString()} click=${n.isClickable}")
                }
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (e: Throwable) { null }
                    if (c != null) queue.add(c)
                }
                if (n !== root) try { n.recycle() } catch (e: Throwable) { }
            }
            try { root.recycle() } catch (e: Throwable) { }
            return
        }
        Log.w("LGTREE", "ime dump: 没有 TYPE_INPUT_METHOD 窗口")
    }

    /** 只找输入法气泡（不含「粘贴」：粘贴是长按菜单里的，混在一起会误点聊天记录） */
    private val CLIP_WORDS = listOf("来自剪贴板", "剪贴板", "Clipboard")

    /** 广度优先找第一个带剪贴板/粘贴字样、有可见尺寸的节点 */
    private fun findClickableByKeyword(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        val r = Rect()
        val screenH = resources.displayMetrics.heightPixels
        while (queue.isNotEmpty() && guard++ < 3000) {
            val n = queue.removeFirst()
            val label = (n.text?.toString().orEmpty() + " " +
                    n.contentDescription?.toString().orEmpty())
            val matched = CLIP_WORDS.any { label.contains(it, ignoreCase = true) }
            if (matched) {
                try { n.getBoundsInScreen(r) } catch (e: Throwable) { r.setEmpty() }
                if (!r.isEmpty && r.height() in 20..(screenH / 2)) {
                    return AccessibilityNodeInfo.obtain(n)
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Throwable) { null }
                if (c != null) queue.add(c)
            }
            if (n !== root) try { n.recycle() } catch (e: Throwable) { }
        }
        return null
    }

    /**
     * 在指定坐标点一下（用于点搜狗输入法弹出的「来自剪贴板」候选条）。
     */
    fun tapAt(x: Int, y: Int): Boolean {
        val path = android.graphics.Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 60
                )
            )
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "tapAt failed: ${t.message}")
            false
        }
    }

    /** 仅把文字放剪贴板（面板降级用），不再尝试粘贴 */
    fun copyToClipboard(text: String): Boolean = writeClipboardVerified(text)

    /**
     * 没有可访问节点时的兜底：直接对屏幕坐标做长按，
     * 弹出系统的「粘贴」菜单。返回 true 表示已尝试。
     */
    fun longPressAt(x: Int, y: Int): Boolean {
        val path = android.graphics.Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 650
                )
            )
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "longPressAt failed: ${t.message}")
            false
        }
    }

    fun clipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
    }
}

/**
 * 打破「无障碍服务 ↔ 悬浮层」的循环依赖，延迟取实例。
 */
object OverlayManagerHolder {
    @Volatile
    private var provider: (() -> Manager)? = null

    interface Manager {
        fun onA11yConnected()
        fun onA11yDisconnected()
        fun onKeyboardVisibilityChanged(visible: Boolean)
        fun onKeyboardMoved(top: Int)
    }

    fun bind(p: (() -> Manager)?) {
        provider = p
    }

    fun get(): Manager? = provider?.invoke()
}
