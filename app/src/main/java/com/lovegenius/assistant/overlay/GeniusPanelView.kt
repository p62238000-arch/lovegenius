package com.lovegenius.assistant.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lovegenius.assistant.R
import com.lovegenius.assistant.data.Prefs
import com.lovegenius.assistant.llm.LlmClient
import com.lovegenius.assistant.llm.PromptEngine
import com.lovegenius.assistant.service.KbAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 面板逻辑：三个 Tab + 关系阶段 + 风格宫格 + 结果卡。
 * 布局来自 res/layout/panel.xml，这里只做事件与状态。
 */
class GeniusPanelView(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = LlmClient()

    private lateinit var root: View
    private lateinit var listResults: LinearLayout
    private lateinit var etInput: EditText
    private lateinit var tvHint: TextView
    private lateinit var scrollResults: ScrollView
    private lateinit var tabStages: LinearLayout
    private lateinit var tabStyles: LinearLayout
    private lateinit var tabBookCats: LinearLayout
    private lateinit var scrollBookCats: HorizontalScrollView

    private var tab = TAB_REPLY
    private var lastInput = ""
    private var busy = false
    private var job: Job? = null
    private var showWhy = false

    var onClose: (() -> Unit)? = null

    /** 面板要临时抢输入法焦点（用户点面板里的输入框）时通知窗口层切 flags */
    var onImeRequest: ((Boolean) -> Unit)? = null

    companion object {
        const val TAB_REPLY = 0
        const val TAB_SAY = 1
        const val TAB_BOOK = 2

        private val BOOK_CATS = listOf(
            "开场破冰", "赞美夸人", "邀约", "化解冷场", "道歉和好", "表白", "哄睡晚安", "挽回"
        )
    }

    // ------------------------------------------------------------------ 构建

    /** 面板临时切回可获焦后，把焦点交给输入框并弹出键盘 */
    fun focusInput() {
        etInput.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun build(): View {        root = View.inflate(ctx, R.layout.panel, null)
        listResults = root.findViewById(R.id.listResults)
        etInput = root.findViewById(R.id.etInput)
        tvHint = root.findViewById(R.id.tvHint)
        scrollResults = root.findViewById(R.id.scrollResults)
        tabStages = root.findViewById(R.id.tabStages)
        tabStyles = root.findViewById(R.id.tabStyles)
        tabBookCats = root.findViewById(R.id.tabBookCats)
        scrollBookCats = root.findViewById(R.id.scrollBookCats)

        // 面板窗口默认 NOT_FOCUSABLE（不抢微信的输入法焦点，键盘不会收起）。
        // 只有用户真的点了面板里的输入框，才临时切回可获焦，好让键盘归面板。
        etInput.isFocusable = true
        etInput.isFocusableInTouchMode = true
        etInput.showSoftInputOnFocus = true
        etInput.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        etInput.setOnClickListener {
            onImeRequest?.invoke(true)
        }
        etInput.setOnFocusChangeListener { _, hasFocus ->
            onImeRequest?.invoke(hasFocus)
        }

        root.findViewById<ImageView>(R.id.btnClose).setOnClickListener { onClose?.invoke() }

        root.findViewById<View>(R.id.tabReply).setOnClickListener { switchTab(TAB_REPLY) }
        root.findViewById<View>(R.id.tabSay).setOnClickListener { switchTab(TAB_SAY) }
        root.findViewById<View>(R.id.tabBook).setOnClickListener { switchTab(TAB_BOOK) }

        root.findViewById<View>(R.id.btnRead).setOnClickListener {
            onImeRequest?.invoke(false)
            readFromScreen()
        }
        root.findViewById<View>(R.id.btnGen).setOnClickListener {
            onImeRequest?.invoke(false)
            generate()
        }

        buildStageChips()
        buildStyleChips()
        switchTab(TAB_REPLY)

        root.post {
            dumpClickableTree()
        }

        // 面板整体尺寸以 OverlayManager 为准，内部用比例高度
        return root
    }

    fun detach() {
        job?.cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------ 坐标自报（调试用）

    /**
     * 把面板里每个可点控件的「屏幕中心坐标」和文案打进 LGTAP 日志。
     *
     * 用途：adb 没法用 uiautomator 抓 overlay 窗口，只能靠这个拿到真实坐标，
     * 避免按截图比例猜。格式刻意做成可直接执行的样子：
     *   tap X Y | 文案 | 类型
     */
    fun dumpClickableTree() {
        val sb = StringBuilder()
        walkViews(root, sb, 0)
        sb.toString().split("\n").filter { it.isNotBlank() }.forEach {
            android.util.Log.i("LGTAP", it)
        }
        android.util.Log.i("LGTAP", "--- dump end ---")
    }

    private fun walkViews(v: View, sb: StringBuilder, depth: Int) {
        if (depth > 14) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = loc[0] + v.width / 2
        val y = loc[1] + v.height / 2

        val label = when (v) {
            is TextView -> v.text?.toString().orEmpty().replace("\n", " ").take(24)
            else -> v.contentDescription?.toString().orEmpty().take(24)
        }
        val clickable = v.isClickable
        val editable = v is EditText
        // 只关心能点/能输入且有尺寸的
        if ((clickable || editable) && v.width > 0 && v.height > 0) {
            val idName = try {
                v.resources.getResourceEntryName(v.id)
            } catch (t: Throwable) {
                "no-id"
            }
            sb.append("tap $x $y | ").append(label.ifBlank { idName })
                .append(" | ").append(idName)
                .append(" | ").append(v.width).append('x').append(v.height)
                .append('\n')
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkViews(v.getChildAt(i), sb, depth + 1)
        }
    }

    // ------------------------------------------------------------------ 顶部选择器

    private fun buildStageChips() {
        tabStages.removeAllViews()
        PromptEngine.STAGES.forEach { s ->
            tabStages.addView(chip(s, Prefs.stage == s) {
                Prefs.stage = s
                buildStageChips()
            })
        }
    }

    private fun buildStyleChips() {
        tabStyles.removeAllViews()
        PromptEngine.STYLES.forEach { s ->
            tabStyles.addView(chip(s, Prefs.style == s) {
                Prefs.style = s
                buildStyleChips()
            })
        }
    }

    private fun buildBookChips() {
        tabBookCats.removeAllViews()
        BOOK_CATS.forEach { c ->
            tabBookCats.addView(chip(c, false) {
                showResults(SpeakBook.asReplies(c))
            })
        }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
            setBackgroundResource(R.drawable.bg_chip_selector)
            isSelected = selected
            val ph = (7 * d).toInt()
            val pv = (5 * d).toInt()
            setPadding(ph, pv, ph, pv)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------ Tab

    private fun switchTab(t: Int) {
        tab = t
        val tabs = listOf(
            R.id.tabReply to TAB_REPLY,
            R.id.tabSay to TAB_SAY,
            R.id.tabBook to TAB_BOOK
        )
        tabs.forEach { (id, idx) ->
            val v = root.findViewById<TextView>(id)
            val on = idx == t
            v.isSelected = on
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
            v.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        }
        clearResults()
        root.postDelayed({ dumpClickableTree() }, 120)
        when (t) {
            TAB_REPLY -> {
                etInput.hint = "读到的对方原话 / 你的意图…"
                etInput.visibility = View.VISIBLE
                scrollBookCats.visibility = View.GONE
                showHint("点「读取输入框」拿对方那句，或直接打字")
            }
            TAB_SAY -> {
                etInput.hint = "你想达到什么目的？例如：想约她周末看电影"
                etInput.visibility = View.VISIBLE
                scrollBookCats.visibility = View.GONE
                showHint("说完目的，点「生成回复」")
            }
            TAB_BOOK -> {
                etInput.visibility = View.GONE
                scrollBookCats.visibility = View.VISIBLE
                if (tabBookCats.childCount == 0) buildBookChips()
                hideHint()
                clearResults()
            }
        }
    }

    // ------------------------------------------------------------------ 动作

    /** 面板一开就尝试读一次，并缓存目标输入框（微信关闭面板后就抓不到了） */
    fun autoRead() {
        val svc = KbAccessibilityService.instance
        if (svc == null) {
            showHint("无障碍服务没开，读不到输入框；可先复制对方那句")
            return
        }
        svc.cacheTargetNode()
        val t = svc.readFocusedText()
        if (!t.isNullOrBlank()) {
            lastInput = t
            etInput.setText(t)
            showHint("已读到输入框内容 ✓")
        } else {
            svc.dumpActiveTree("autoRead-null")
            val clip = svc.clipboardText()
            android.util.Log.i("LoveGeniusOverlay",
                "autoRead: 微信读不到；clipLen=${clip.length} like=${looksLikeMessage(clip)} " +
                        "clip=${clip.take(30)}")
            if (looksLikeMessage(clip)) {
                lastInput = clip
                etInput.setText(clip)
                showHint("输入框读不到，用了剪贴板内容")
            } else {
                // v1.2 能自动填、v1.3 不能的原因：面板改成 NOT_FOCUSABLE 后本应用没有焦点，
                // Android 10+ 就把剪贴板读取挡成空串。这里临时把窗口切回可获焦再读一次。
                showHint("正在读取剪贴板…")
                onImeRequest?.invoke(true)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val again = svc.clipboardText()
                    android.util.Log.i("LoveGeniusOverlay",
                        "autoRead retry: clipLen=${again.length} like=${looksLikeMessage(again)}")
                    if (looksLikeMessage(again)) {
                        lastInput = again
                        etInput.setText(again)
                        showHint("输入框读不到，用了剪贴板内容")
                    } else {
                        // 还是读不到 → 让面板输入框自己执行粘贴，再把文字读回来
                        val pasted = svc.pasteIntoOwnInput()
                        if (!pasted.isNullOrBlank() && looksLikeMessage(pasted)) {
                            lastInput = pasted
                            etInput.setText(pasted)
                            showHint("输入框读不到，用了剪贴板内容")
                        } else {
                            showHint("没读到文字，请手动输入或先复制对方消息")
                        }
                    }
                    onImeRequest?.invoke(false)
                }, 220)
            }
        }
    }

    /** 剪贴板里常是路径/文件/代码，别把它们当聊天内容 */
    private fun looksLikeMessage(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 1..300) return false
        if (t.startsWith("/data/") || t.startsWith("file:")) return false
        if (t.matches(Regex("^[\\w./:=~-]+$")) && t.contains('/')) return false
        // 允许换行：微信里复制的消息经常是多行
        return true
    }

    private fun readFromScreen() {
        if (tab == TAB_BOOK) {
            showHint("话术宝典不用读取，直接点分类")
            return
        }
        autoRead()
    }

    private fun generate() {
        if (busy) return
        val input = etInput.text?.toString()?.trim().orEmpty()
        if (tab == TAB_BOOK) {
            showHint("话术宝典不用生成，先点上面分类；点某条「填入」直接进输入框")
            return
        }
        if (input.isEmpty()) {
            showHint(if (tab == TAB_REPLY) "先读到对方的原话，或手动输入" else "先说说你的目的")
            return
        }
        if (Prefs.apiKey.isBlank()) {
            // 没配 Key：用本地词库兜底，保证第一次用也能看到效果
            showResults(offlineReplies(input))
            showHint("未配置 API Key，以上为本地话术；配 Key 后可按语境生成")
            return
        }

        lastInput = input
        setBusy(true)
        clearResults()
        showHint("正在生成…")

        val stage = Prefs.stage
        val style = Prefs.style
        val msgs = if (tab == TAB_SAY)
            PromptEngine.buildSayMessages(stage, style, input)
        else
            PromptEngine.buildReplyMessages(stage, style, input, "")

        job = scope.launch {
            val r = client.complete(msgs)
            setBusy(false)
            when (r) {
                is LlmClient.Result.Ok -> {
                    hideHint()
                    showResults(r.replies)
                    val log = listOf(
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()),
                        stage, style, tabName(), input.take(60),
                        r.replies.joinToString(" / ") { it.text }.take(120)
                    ).joinToString("|")
                    Prefs.pushHistory(log)
                }
                is LlmClient.Result.Err -> showHint("生成失败：" + r.message)
            }
        }
    }

    private fun tabName() = when (tab) {
        TAB_SAY -> "帮你说"
        TAB_BOOK -> "话术宝典"
        else -> "帮你回"
    }

    /** 无 Key 时的本地兜底：按输入里的关键词挑 3 条不同味道的话术 */
    private fun offlineReplies(input: String): List<LlmClient.Reply> {
        val cats = when (tab) {
            TAB_SAY -> when {
                input.contains("约") || input.contains("电影") || input.contains("吃饭") ->
                    listOf("邀约", "邀约", "化解冷场")
                input.contains("道歉") || input.contains("哄") || input.contains("生气") ->
                    listOf("道歉和好", "哄睡晚安", "赞美夸人")
                input.contains("表白") || input.contains("喜欢") ->
                    listOf("表白", "赞美夸人", "化解冷场")
                else -> listOf("邀约", "化解冷场", "赞美夸人")
            }
            else -> when {
                input.contains("?") || input.contains("？") || input.contains("吗") ->
                    listOf("化解冷场", "赞美夸人", "邀约")
                input.contains("累") || input.contains("难受") || input.contains("烦") ->
                    listOf("道歉和好", "哄睡晚安", "赞美夸人")
                input.contains("晚安") || input.contains("睡") ->
                    listOf("哄睡晚安", "哄睡晚安", "赞美夸人")
                else -> listOf("开场破冰", "化解冷场", "赞美夸人")
            }
        }
        val tags = listOf("稳", "进", "俏皮")
        val picked = cats.mapIndexedNotNull { i, c ->
            SpeakBook.items(c).getOrNull(i)?.let {
                LlmClient.Reply(it, tags.getOrElse(i) { "稳" }, "本地话术（$c）")
            }
        }
        if (picked.size >= 3) return picked
        return (picked + SpeakBook.asReplies("开场破冰")).take(3)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        val btn = root.findViewById<TextView>(R.id.btnGen)
        btn.text = if (b) "生成中…" else "✨ 生成回复"
        btn.alpha = if (b) 0.6f else 1f
    }

    // ------------------------------------------------------------------ 结果

    private fun clearResults() {
        listResults.removeAllViews()
    }

    private fun showHint(msg: String) {
        tvHint.visibility = View.VISIBLE
        tvHint.text = msg
    }

    private fun hideHint() {
        tvHint.visibility = View.GONE
    }

    fun showResults(replies: List<LlmClient.Reply>) {
        clearResults()
        val d = ctx.resources.displayMetrics.density
        replies.forEachIndexed { i, r ->
            listResults.addView(card(i + 1, r, d))
        }
        // 结果卡出现后重新自报坐标（复制/填入/为什么 的位置都变了）
        root.postDelayed({ dumpClickableTree() }, 120)
    }

    private fun card(index: Int, r: LlmClient.Reply, d: Float): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * d).toInt()
            layoutParams = lp
        }

        val head = TextView(ctx).apply {
            text = "$index · ${r.style.ifBlank { "建议" }}"
            textSize = 11f
            setTextColor(0xFFFF5C8A.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }
        wrap.addView(head)

        val body = TextView(ctx).apply {
            text = r.text
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            setLineSpacing(0f, 1.15f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * d).toInt()
            layoutParams = lp
        }
        wrap.addView(body)

        val whyText = TextView(ctx).apply {
            text = "为什么：" + r.why
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            visibility = if (showWhy && r.why.isNotBlank()) View.VISIBLE else View.GONE
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * d).toInt()
            layoutParams = lp
        }
        wrap.addView(whyText)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * d).toInt()
            layoutParams = lp
        }

        row.addView(smallBtn("复制", false) { copy(r.text) })
        row.addView(smallBtn("填入", true) { fill(r.text) })
        row.addView(smallBtn("为什么", false) {
            showWhy = !showWhy
            whyText.visibility =
                if (showWhy && r.why.isNotBlank()) View.VISIBLE else View.GONE
        })
        wrap.addView(row)
        return wrap
    }

    private fun smallBtn(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundResource(if (primary) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFFF5C8A.toInt())
            val ph = (10 * d).toInt()
            val pv = (5 * d).toInt()
            setPadding(ph, pv, ph, pv)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            layoutParams = lp
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun copy(text: String) {
        copyAndVerify(text)
        toast("已复制")
    }

    /** 写剪贴板并校验：搜狗等输入法会抢剪贴板，被改写了就重写 */
    private fun copyAndVerify(text: String): Boolean {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        repeat(2) {
            cm.setPrimaryClip(ClipData.newPlainText("lovegenius", text))
            val now = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (now == text) return true
        }
        cm.setPrimaryClip(ClipData.newPlainText("lovegenius", text))
        return false
    }

    /**
     * 点「填入」：
     *  1) 先把面板收掉 —— 面板是可获焦窗口，不关的话活动窗口就是它自己，
     *     无障碍只能看见面板里的 EditText，会把文字写回面板（这是之前的 bug）。
     *  2) 面板消失后再去写对方 App 的输入框。
     *  3) 写不进去就复制 + 提示粘贴。
     */
    private fun fill(text: String) {
        val svc = KbAccessibilityService.instance
        // 面板一关键盘就会收，所以先在面板还开着时把所有位置信息取下来
        val targetRect = svc?.cachedRect?.let { android.graphics.Rect(it) }
            ?: svc?.guessInputRect()?.let { android.graphics.Rect(it) }
        onClose?.invoke()          // 收面板，让聊天 App 重新成为活动窗口
        if (svc == null) {
            copyAndVerify(text)
            toast("无障碍没开，已复制，长按粘贴")
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                tries++
                val ok = svc.fillText(text)
                if (ok) {
                    android.util.Log.i("LoveGeniusOverlay", "fill ok after $tries tries")
                    svc.clearCachedTarget()
                    toast("已填入")
                    return
                }
                if (tries < 4) {
                    handler.postDelayed(this, 220)
                } else {
                    android.util.Log.w("LoveGeniusOverlay", "fill failed, fallback to clipboard")
                    svc.clearCachedTarget()
                    // 微信屏蔽节点树时节点写不进去，改走剪贴板通道（点键盘上的「来自剪贴板」）
                    gotoClipboardChannel(svc, text, targetRect, handler)
                }
            }
        }
        handler.postDelayed(attempt, 280)
    }

    /**
     * 微信把节点树对无障碍屏蔽了，节点写不进去时的通道：
     *  写剪贴板 → 点输入栏把键盘叫回来 → 长按输入栏 → 点长按菜单第一项「粘贴」
     *
     * 说明：搜狗的「来自剪贴板」气泡位置会随键盘状态漂移（清空输入框后会变工具箱），
     * 坐标点不可靠；长按菜单是横排固定布局，「粘贴」永远在左起第一个（输入框为空时）。
     */
    private fun gotoClipboardChannel(
        svc: KbAccessibilityService,
        text: String,
        rect: android.graphics.Rect?,
        handler: android.os.Handler
    ) {
        copyAndVerify(text)
        val r0 = svc.inputBarRectNow()
        android.util.Log.i("LoveGeniusOverlay",
            "clipboard channel: tap input bar ${r0.toShortString()} kbTop=${KbAccessibilityService.keyboardTop()}")
        svc.tapAt(r0.centerX(), r0.centerY())
        handler.postDelayed({
            if (svc.clickClipboardEntry()) {
                toast("已填入")
                return@postDelayed
            }
            val r = svc.inputBarRectNow()
            svc.longPressAt(r.centerX(), r.centerY())
            handler.postDelayed({
                val dm = ctx.resources.displayMetrics
                val d = dm.density
                val kt = KbAccessibilityService.keyboardTop()
                val y = if (kt > 0) kt - (68 * d).toInt() else r.top - (50 * d).toInt()
                val x = (dm.widthPixels * 0.095f).toInt()
                android.util.Log.i("LoveGeniusOverlay", "tap paste menu at ($x, $y) kbTop=$kt")
                svc.tapAt(x, y)
                handler.postDelayed({
                    toast("已填入")
                }, 350)
            }, 760)
        }, 520)
    }

    private fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
