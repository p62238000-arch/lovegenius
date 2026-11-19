package com.lovegenius.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 极简偏好存储：API Key / 上次阶段 / 上次风格 / 历史记录。
 */
object Prefs {
    private const val NAME = "lovegenius"
    private const val K_API_KEY = "api_key"
    private const val K_BASE_URL = "base_url"
    private const val K_MODEL = "model"
    private const val K_STAGE = "stage"
    private const val K_STYLE = "style"
    private const val K_HISTORY = "history"
    private const val K_PROVIDER = "provider"
    private const val K_CUSTOM = "custom_providers"

    /** 默认走 DeepSeek 官方（OpenAI 兼容） */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    /** DeepSeek 官方在售的是 deepseek-flash / deepseek-v4-pro，旧的 deepseek-chat 已下线 */
    const val DEFAULT_MODEL = "deepseek-flash"

    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_DOUBAO = "doubao"
    const val PROVIDER_ZHIPU = "zhipu"

    /** 智谱免费档，切回去时用 */
    const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val ZHIPU_MODEL = "glm-4-flash"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            migrateDefaults()
        }
    }

    /**
     * 老版本把智谱当默认写进了 SP；现在默认改成 DeepSeek。
     * 两种情况都要兜住：
     *  1) 用户从没改过（还是智谱那套原值）→ 整套切成 DeepSeek
     *  2) 用户把地址改成了 DeepSeek 但模型名还留着 glm-4-flash → 只修模型名
     */
    private fun migrateDefaults() {
        val curBase = sp.getString(K_BASE_URL, null)
        val curModel = sp.getString(K_MODEL, null)
        val untouched =
            (curBase == null || curBase == ZHIPU_BASE_URL) &&
            (curModel == null || curModel == ZHIPU_MODEL)
        if (untouched) {
            sp.edit()
                .putString(K_BASE_URL, DEFAULT_BASE_URL)
                .putString(K_MODEL, DEFAULT_MODEL)
                .apply()
            return
        }
        // 地址是 DeepSeek 系：把已下线/打错的模型名纠到在售的
        val baseIsDeepSeek = curBase?.contains("deepseek", ignoreCase = true) == true
        if (baseIsDeepSeek) {
            val m = curModel.orEmpty()
            val known = setOf("deepseek-flash", "deepseek-v4-pro")
            // deepseek-chat 已下线；deepseek-chap 是手误
            if (m.isBlank() || m !in known) {
                sp.edit().putString(K_MODEL, DEFAULT_MODEL).apply()
            }
        }
    }

    var apiKey: String
        get() = sp.getString(K_API_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_API_KEY, v.trim()).apply()

    var baseUrl: String
        get() = sp.getString(K_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString(K_BASE_URL, v.trim().trimEnd('/')).apply()

    var model: String
        get() = sp.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(K_MODEL, v.trim()).apply()

    /** 当前选中的供应商 id：deepseek / doubao / zhipu */
    var provider: String
        get() = sp.getString(K_PROVIDER, PROVIDER_DEEPSEEK) ?: PROVIDER_DEEPSEEK
        set(v) = sp.edit().putString(K_PROVIDER, v).apply()

    var stage: String
        get() = sp.getString(K_STAGE, "初识期") ?: "初识期"
        set(v) = sp.edit().putString(K_STAGE, v).apply()

    var style: String
        get() = sp.getString(K_STYLE, "高情商") ?: "高情商"
        set(v) = sp.edit().putString(K_STYLE, v).apply()

    /** 历史：时间戳|阶段|风格|输入|输出，最多 200 条 */
    fun pushHistory(line: String) {
        val old = sp.getString(K_HISTORY, "") ?: ""
        val merged = (listOf(line) + old.split("\n").filter { it.isNotBlank() }).take(200)
        sp.edit().putString(K_HISTORY, merged.joinToString("\n")).apply()
    }

    fun history(): List<String> =
        (sp.getString(K_HISTORY, "") ?: "").split("\n").filter { it.isNotBlank() }

    // ---------------------------------------------------------------- 自定义模型
    /**
     * 用户手动添加的供应商，一行一个：
     * 名称|baseUrl|模型名
     * 这样中转站/自建 ollama 之类都能接进来。
     */
    fun customProviders(): List<Triple<String, String, String>> =
        (sp.getString(K_CUSTOM, "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split("|")
                if (p.size >= 3 && p[1].isNotBlank() && p[2].isNotBlank()) {
                    Triple(p[0].ifBlank { "自定义" }, p[1].trim(), p[2].trim())
                } else null
            }

    fun addCustomProvider(name: String, baseUrl: String, model: String) {
        val line = "${name.trim().ifBlank { "自定义" }}|${baseUrl.trim()}|${model.trim()}"
        val old = (sp.getString(K_CUSTOM, "") ?: "")
            .split("\n").filter { it.isNotBlank() && !it.startsWith(line.substringBefore("|")) }
        sp.edit().putString(K_CUSTOM, (old + line).joinToString("\n")).apply()
    }

    fun removeCustomProvider(name: String) {
        val kept = customProviders().filter { it.first != name }
            .map { "${it.first}|${it.second}|${it.third}" }
        sp.edit().putString(K_CUSTOM, kept.joinToString("\n")).apply()
    }
}
