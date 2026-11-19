package com.lovegenius.assistant.llm

import com.lovegenius.assistant.data.Prefs

/**
 * 支持的大模型供应商。都走 OpenAI 兼容的 /chat/completions，
 * 差别只在 base URL、模型名、以及是否支持 response_format。
 *
 * 说明：模型 ID 会随厂商升级下线（deepseek-chat、doubao-1-5、glm-4-flash 都走过这条路），
 * 所以每条都带了 models 候选列表，面板上做成可点选项；用户不必手打。
 */
object Providers {

    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val model: String,
        val modelHint: String,
        val jsonMode: Boolean,
        val note: String,
        /** 该家可选的具体模型：(模型 id, 显示名)；空表示只能手填 */
        val models: List<Pair<String, String>> = emptyList(),
        /** 推理型：先输出 reasoning，max_tokens 要给足 */
        val reasoning: Boolean = false
    )

    val DEEPSEEK = Provider(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-flash",
        modelHint = "点下面的选项，不用手打",
        jsonMode = true,
        note = "官方在售：flash（快、便宜、支持图片）/ v4-pro（更强）",
        models = listOf(
            "deepseek-flash" to "Flash · 快",
            "deepseek-v4-pro" to "V4 Pro · 强"
        ),
        reasoning = true
    )

    val DOUBAO = Provider(
        id = "doubao",
        label = "豆包",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        model = "doubao-seed-2-1-pro",
        modelHint = "点下面的选项；也可填你在方舟创建的接入点 ID（ep-开头）",
        jsonMode = false,
        note = "需要先在火山方舟「开通模型」，把接入点 ID 或模型名填对",
        models = listOf(
            "doubao-seed-2-1-pro" to "Seed 2.1 Pro",
            "doubao-seed-2-1-lite" to "Seed 2.1 Lite",
            "doubao-seed-2-1-turbo" to "Seed 2.1 Turbo",
            "doubao-seed-2-0-pro" to "Seed 2.0 Pro"
        )
    )

    val ZHIPU = Provider(
        id = "zhipu",
        label = "智谱",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3-flash",
        modelHint = "点下面的选项，不用手打",
        jsonMode = false,
        note = "glm-5.3-flash 是免费档，日常够用",
        models = listOf(
            "glm-5.3-flash" to "5.3 Flash · 免费",
            "glm-5.3" to "5.3 · 更强",
            "glm-5.2" to "5.2"
        )
    )

    val QWEN = Provider(
        id = "qwen",
        label = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        modelHint = "点下面的选项；名字不对时按控制台里的模型名改",
        jsonMode = true,
        note = "阿里云百炼，新用户有免费额度",
        models = listOf(
            "qwen-plus" to "qwen-plus",
            "qwen-turbo" to "qwen-turbo",
            "qwen-max" to "qwen-max",
            "qwen-flash" to "qwen-flash"
        )
    )

    val KIMI = Provider(
        id = "kimi",
        label = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k3.1-pro",
        modelHint = "点下面的选项，不用手打",
        jsonMode = false,
        note = "月之暗面，中文语感好",
        models = listOf(
            "kimi-k3.1-pro" to "K3.1 Pro",
            "kimi-k3.1-pro-highspeed" to "K3.1 Pro · 高速",
            "moonshot-v1-8k" to "老版 v1-8k"
        )
    )

    val SILICON = Provider(
        id = "silicon",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "Qwen/Qwen2.5-7B-Instruct",
        modelHint = "填平台上任意可用模型全名（带厂商前缀）",
        jsonMode = false,
        note = "有一批免费小模型，模型名需按平台显示填写",
        models = listOf(
            "Qwen/Qwen2.5-7B-Instruct" to "Qwen2.5-7B 免费",
            "Qwen/Qwen2.5-72B-Instruct" to "Qwen2.5-72B",
            "deepseek-ai/DeepSeek-V3" to "DeepSeek-V3"
        )
    )

    val ALL = listOf(DEEPSEEK, DOUBAO, ZHIPU, QWEN, KIMI, SILICON)

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: DEEPSEEK

    /** 根据当前 base URL 猜是哪家（用户可能手动改过地址） */
    fun detect(baseUrl: String): Provider = ALL.firstOrNull { p ->
        val host = p.baseUrl.substringAfter("://").substringBefore("/")
        baseUrl.contains(host, ignoreCase = true)
    } ?: DEEPSEEK

    /** 当前生效的配置；自定义项从 Prefs 里取 */
    fun current(): Provider {
        val id = Prefs.provider
        if (id.startsWith("custom:")) {
            val name = id.removePrefix("custom:")
            val cp = Prefs.customProviders().firstOrNull { it.first == name }
            if (cp != null) {
                return Provider(
                    id = id,
                    label = cp.first,
                    baseUrl = cp.second,
                    model = cp.third,
                    modelHint = "自定义模型",
                    // 自定义接口多半兼容 OpenAI，开 JSON 模式更稳；不支持时用户可改提示词
                    jsonMode = true,
                    note = "自定义：${cp.second}"
                )
            }
        }
        return byId(id)
    }
}
