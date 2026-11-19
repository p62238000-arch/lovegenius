package com.lovegenius.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lovegenius.assistant.data.Prefs
import com.lovegenius.assistant.overlay.OverlayManager
import com.lovegenius.assistant.service.KbAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.lovegenius.assistant.llm.LlmClient
import com.lovegenius.assistant.llm.Providers

/**
 * 引导页：无障碍 / 悬浮窗 / API Key 三项授权 + 自检。
 * UI 全部代码搭，不额外维护布局文件，省得资源对不上。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = LlmClient()

    private lateinit var tvA11y: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvVerify: TextView
    private lateinit var btnA11y: Button
    private lateinit var btnOverlay: Button
    private lateinit var etKey: EditText
    private lateinit var etBase: EditText
    private lateinit var etModel: EditText
    private lateinit var btnTest: Button

    private var providerChips: List<TextView> = emptyList()
    private var providerHost: LinearLayout? = null
    private var tvProviderNote: TextView? = null
    private var tvModelLabel: TextView? = null
    private var modelChipRow: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayManager.attach(this)
        setContentView(buildUi())
        refreshStatus()
        // 调试/自检入口：adb shell am start -n com.lovegenius.assistant/.MainActivity --ez show_panel true
        if (intent?.getBooleanExtra("show_panel", false) == true) {
            intent?.removeExtra("show_panel")
            OverlayManager.showPanelForTest()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Activity 已在栈顶时，adb --ez show_panel true 只会走这里，onCreate 不再执行 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("show_panel", false)) {
            intent.removeExtra("show_panel")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                OverlayManager.showPanelForTest()
            }, 150)
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "💗 恋爱军师"
            textSize = 24f
            setTextColor(0xFF222222.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "键盘弹出时点悬浮球 💗，两步拿到回复建议"
            textSize = 13f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(6), 0, dp(18))
        })

        // ---- 1 无障碍
        col.addView(sectionTitle("① 无障碍服务（读输入框 / 回填）"))
        tvA11y = statusLine("未开启")
        col.addView(tvA11y)
        btnA11y = primaryButton("去开启无障碍") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(btnA11y)
        col.addView(hintLine("在列表里找「恋爱军师·键盘助手」，打开开关。"))
        col.addView(gap())

        // ---- 2 悬浮窗
        col.addView(sectionTitle("② 悬浮窗权限（悬浮球和面板）"))
        tvOverlay = statusLine("未开启")
        col.addView(tvOverlay)
        btnOverlay = primaryButton("去开启悬浮窗") {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            i.data = Uri.parse("package:$packageName")
            runCatching { startActivity(i) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
        col.addView(btnOverlay)
        col.addView(gap())

        // ---- 3 模型供应商 + API Key
        col.addView(sectionTitle("③ 模型（点一下切换，不用填地址/模型名）"))

        // 每家一个胶囊，两行排开；点一下自动填好地址和模型名
        val provWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
        }
        providerHost = provWrap
        rebuildProviderChips()
        col.addView(provWrap)

        // 手动添加自定义模型（中转站 / 自建 ollama / 新出的模型都能接）
        col.addView(TextView(this).apply {
            text = "＋ 手动添加模型（中转站、自建服务、新模型）"
            textSize = 13f
            setTextColor(0xFFFF5C8A.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            val pv = dp(10)
            setPadding(dp(10), pv, dp(10), pv)
            layoutParams = matchWrap().also { it.topMargin = dp(2) }
            isClickable = true
            setOnClickListener { showAddProviderDialog() }
        })

        col.addView(TextView(this).apply {
            text = Providers.current().note
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            setPadding(0, dp(6), 0, dp(8))
            tvProviderNote = this
        })

        etKey = EditText(this).apply {
            hint = "粘贴 API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(Prefs.apiKey)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.bg_input)
        }
        col.addView(etKey, matchWrap())

        col.addView(TextView(this).apply {
            text = "接口地址（换供应商会自动填）"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        etBase = EditText(this).apply {
            setText(Prefs.baseUrl)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.bg_input)
        }
        col.addView(etBase, matchWrap())

        col.addView(TextView(this).apply {
            text = "模型名"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(10), 0, dp(4))
            tvModelLabel = this
        })

        // 该家可选的具体模型：做成胶囊，点一下就选，不用手打
        modelChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
        }
        col.addView(modelChipRow)

        etModel = EditText(this).apply {
            setText(Prefs.model)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.bg_input)
        }
        col.addView(etModel, matchWrap())
        updateProviderLabels()

        btnTest = primaryButton("保存并测试连通") { saveAndTest() }
        col.addView(btnTest, matchWrap().also { it.topMargin = dp(10) })
        tvVerify = statusLine("未测试")
        col.addView(tvVerify)
        col.addView(gap())

        // ---- 自检：直接在当前界面打开面板
        col.addView(sectionTitle("自检"))
        col.addView(primaryButton("试一下：现在打开面板") {
            if (Settings.canDrawOverlays(this)) {
                OverlayManager.showPanelForTest()
                Toast.makeText(this, "面板已打开（可在任意界面使用）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
        }, matchWrap().also { it.bottomMargin = dp(6) })
        col.addView(hintLine(
            "点上面按钮会在当前屏幕弹出军师面板，用来确认权限都通了；\n" +
            "平时在聊天 App 里：键盘弹出后点右上角 💗 就可以。"
        ))
        col.addView(gap())

        // ---- 4 状态 + 使用说明
        col.addView(sectionTitle("状态自检"))
        tvNet = statusLine("模型 Key：—")
        col.addView(tvNet)
        col.addView(statusLine("无障碍服务：").also { tvA11yState = it })
        col.addView(statusLine("悬浮窗权限：").also { tvOverlayState = it })
        col.addView(statusLine("键盘监听：").also { tvKbState = it })
        col.addView(gap())

        col.addView(sectionTitle("怎么用"))
        col.addView(hintLine(
            "1. 打开微信/QQ，点输入框，搜狗键盘弹出\n" +
            "2. 键盘右上角出现 💗，点它\n" +
            "3. 点「读取输入框」拿到对方那句话\n" +
            "4. 点「✨生成回复」，等 3~8 秒\n" +
            "5. 点某条的「填入」→ 文字进输入框；若提示已复制，就长按输入框选粘贴\n" +
            "6. 键盘收起，💗 自动消失"
        ))
        col.addView(gap())
        col.addView(hintLine("内容由 AI 生成，仅供参考。API Key 只存在本机。"))

        return scroll
    }

    private var tvA11yState: TextView? = null
    private var tvOverlayState: TextView? = null
    private var tvKbState: TextView? = null

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(0xFF222222.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun statusLine(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF666666.toInt())
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun hintLine(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF999999.toInt())
        setPadding(0, dp(6), 0, dp(2))
        setLineSpacing(0f, 1.35f)
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    /** 供应商选择用的小胶囊按钮 */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            isSelected = selected
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
            setBackgroundResource(
                if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip
            )
            setOnClickListener { onClick() }
        }

    /** 切换供应商：把地址和模型名填成该家的默认值 */
    private fun selectProvider(p: Providers.Provider) {
        Prefs.provider = p.id
        Prefs.baseUrl = p.baseUrl
        Prefs.model = p.model
        etBase.setText(p.baseUrl)
        etModel.setText(p.model)
        providerChips.forEachIndexed { i, chip ->
            val on = Providers.ALL[i].id == p.id
            chip.isSelected = on
            chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
            chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        }
        updateProviderLabels()
        Toast.makeText(
            this,
            "已切到 ${p.label}，记得填这家的 Key",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 手动添加一个模型：名称 / 接口地址 / 模型名 */
    private fun showAddProviderDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18)
            setPadding(p, p, p, 0)
        }
        fun field(hint: String, prefill: String = ""): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(prefill)
                textSize = 13f
                setSingleLine()
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundResource(R.drawable.bg_input)
                layoutParams = matchWrap().also { it.bottomMargin = dp(10) }
            }
            box.addView(e)
            return e
        }
        val etName = field("名称（随便起，例如：某中转站）")
        val etUrl = field("接口地址（要带 /v1 这类版本段）", "https://api.deepseek.com/v1")
        val etM = field("模型名（例如 deepseek-flash）", "deepseek-flash")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("添加模型")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString().orEmpty().trim().ifBlank { "自定义" }
                val url = etUrl.text?.toString().orEmpty().trim()
                val model = etM.text?.toString().orEmpty().trim()
                if (url.isEmpty() || model.isEmpty()) {
                    Toast.makeText(this, "地址和模型名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Prefs.addCustomProvider(name, url, model)
                Prefs.provider = "custom:$name"
                Prefs.baseUrl = url
                Prefs.model = model
                etBase.setText(url)
                etModel.setText(model)
                rebuildProviderChips()
                updateProviderLabels()
                Toast.makeText(this, "已添加并切到「$name」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 供应商胶囊按 ALL + 自定义重新生成 */
    private fun rebuildProviderChips() {
        val host = providerHost ?: return
        host.removeAllViews()
        val chips = mutableListOf<TextView>()
        val items = mutableListOf<Pair<String, String>>()   // id to label
        Providers.ALL.forEach { items.add(it.id to it.label) }
        Prefs.customProviders().forEach { items.add("custom:${it.first}" to it.first) }

        items.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWrap().also { it.bottomMargin = dp(6) }
            }
            group.forEachIndexed { i, (id, label) ->
                val c = chip(label, Prefs.provider == id) { selectProviderById(id) }
                chips.add(c)
                row.addView(c, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (i < group.lastIndex) marginEnd = dp(6)
                })
                // 自定义项长按删除
                if (id.startsWith("custom:")) {
                    c.setOnLongClickListener {
                        val name = id.removePrefix("custom:")
                        Prefs.removeCustomProvider(name)
                        if (Prefs.provider == id) {
                            Prefs.provider = Providers.DEEPSEEK.id
                            Prefs.baseUrl = Providers.DEEPSEEK.baseUrl
                            Prefs.model = Providers.DEEPSEEK.model
                            etBase.setText(Prefs.baseUrl)
                            etModel.setText(Prefs.model)
                        }
                        rebuildProviderChips()
                        updateProviderLabels()
                        Toast.makeText(this, "已删除「$name」", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            }
            repeat(3 - group.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 1f))
            }
            host.addView(row)
        }
        providerChips = chips
    }

    private fun selectProviderById(id: String) {
        if (id.startsWith("custom:")) {
            val name = id.removePrefix("custom:")
            val cp = Prefs.customProviders().firstOrNull { it.first == name } ?: return
            Prefs.provider = id
            Prefs.baseUrl = cp.second
            Prefs.model = cp.third
            etBase.setText(cp.second)
            etModel.setText(cp.third)
            rebuildProviderChips()
            updateProviderLabels()
            Toast.makeText(this, "已切到「$name」", Toast.LENGTH_SHORT).show()
        } else {
            selectProvider(Providers.byId(id))
        }
    }

    private fun updateProviderLabels() {
        val p = Providers.current()
        tvProviderNote?.text = p.note
        tvModelLabel?.text = "模型名（${p.modelHint}）"
        rebuildModelChips(p)
    }

    /** 该家的可选模型做成胶囊，点一下直接选中，不用手打 */
    private fun rebuildModelChips(p: Providers.Provider) {
        val host = modelChipRow ?: return
        host.removeAllViews()
        val options = p.models
        if (options.isEmpty()) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        // host 是纵向容器，一行最多放 3 个
        options.chunked(3).forEach { group ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWrap().also { it.bottomMargin = dp(6) }
            }
            group.forEachIndexed { i, (id, label) ->
                val c = chip(label, Prefs.model == id) {
                    Prefs.model = id
                    etModel.setText(id)
                    rebuildModelChips(p)
                    Toast.makeText(this, "已选 $id", Toast.LENGTH_SHORT).show()
                }
                line.addView(c, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (i < group.lastIndex) marginEnd = dp(6)
                })
            }
            repeat(3 - group.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(34), 1f))
            }
            host.addView(line)
        }
    }

    private fun primaryButton(
        t: String,
        lp: LinearLayout.LayoutParams = matchWrap(),
        onClick: () -> Unit
    ) = Button(this).apply {
        text = t
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.bg_btn_primary)
        setOnClickListener { onClick() }
        layoutParams = lp
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val a11y = KbAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)

        tvA11yState?.text = "无障碍服务：" + if (a11y) "✅ 已连接" else "❌ 未开启"
        tvOverlayState?.text = "悬浮窗权限：" + if (overlay) "✅ 已授予" else "❌ 未授予"
        tvKbState?.text = "键盘监听：" +
                if (a11y) "✅ 运行中（看 logcat 的 LoveGeniusA11y）" else "— 等服务开启"

        tvA11y.text = if (a11y) "✅ 已开启" else "未开启"
        btnA11y.text = if (a11y) "重新打开设置" else "去开启无障碍"
        tvOverlay.text = if (overlay) "✅ 已授予" else "未授予"
        btnOverlay.text = if (overlay) "重新打开设置" else "去开启悬浮窗"

        tvNet.text = if (Prefs.apiKey.isBlank()) "未填 Key" else "已填 Key（末四位 " +
                Prefs.apiKey.takeLast(4) + "）"
    }

    private fun saveAndTest() {
        Prefs.apiKey = etKey.text?.toString().orEmpty()
        Prefs.baseUrl = etBase.text?.toString().orEmpty().ifBlank { Prefs.DEFAULT_BASE_URL }
        Prefs.model = etModel.text?.toString().orEmpty().ifBlank { Prefs.DEFAULT_MODEL }
        // 保存时若地址没变，就保留当前选中的供应商（含自定义项）；
        // 只有地址被改过，才按地址反推
        val urlNow = Prefs.baseUrl
        if (!Prefs.provider.startsWith("custom:")) {
            val prev = Providers.byId(Prefs.provider)
            if (prev.baseUrl != urlNow) {
                Prefs.provider = Providers.detect(urlNow).id
            }
        }
        updateProviderLabels()

        if (Prefs.apiKey.isBlank()) {
            tvVerify.text = "❌ Key 是空的"
            return
        }
        tvVerify.text = "测试中…"
        btnTest.isEnabled = false
        scope.launch {
            // 测试请求也要走和真实生成一样的 JSON 契约，
            // 否则开了 json_object 的模型会报 "Prompt must contain the word json"
            val r = client.complete(
                listOf(
                    LlmClient.Msg(
                        "system",
                        "你是连通性测试。只输出 JSON：{\"replies\":[{\"text\":\"好\",\"style\":\"稳\",\"why\":\"测试\"}]}"
                    ),
                    LlmClient.Msg("user", "【关系阶段】初识期\n【风格】高情商\n【对方原话】测试\n【我的意图】")
                ),
                temperature = 0.2
            )
            btnTest.isEnabled = true
            tvVerify.text = when (r) {
                is LlmClient.Result.Ok -> "✅ 连通成功：" + r.replies.first().text.take(40)
                is LlmClient.Result.Err -> "❌ " + r.message
            }
            refreshStatus()
            Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
        }
    }
}
