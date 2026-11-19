package com.lovegenius.assistant.llm

/**
 * 人设 + 关系阶段 + 风格 → OpenAI 兼容 messages。
 * 提示词严格照交接包 11.4 的模板，只做参数拼装。
 */
object PromptEngine {

    val STAGES = listOf("初识期", "暧昧期", "热恋期", "挽回期")

    /**
     * 风格全部是可点选的胶囊，不用手填。分三组，情感浓度从低到高。
     */
    val STYLE_GROUPS: List<Pair<String, List<String>>> = listOf(
        "日常" to listOf("高情商", "成熟", "细腻"),
        "进攻" to listOf("撩女生", "情场", "风流"),
        "情感浓" to listOf(
            "走心", "深情", "宝贝", "甜宠", "撒娇", "暖男",
            "肉麻", "思念", "宠溺", "治愈", "痴情", "心动"
        )
    )

    /** 扁平列表，面板与历史记录用 */
    val STYLES: List<String> = STYLE_GROUPS.flatMap { it.second }

    /** 情感浓度：低=克制，高=热烈。可点选，不用填 */
    val EMOTION_LEVELS = listOf("克制", "适中", "浓烈", "肉麻")

    /** 风格 → 一句人设说明，喂给模型 */
    private val STYLE_DESC: Map<String, String> = mapOf(
        // 日常
        "高情商" to "共情对方情绪，不评判，语气舒服",
        "成熟" to "稳、有担当，不幼稚",
        "细腻" to "注意对方说过的细节，记得住小事",
        // 进攻
        "撩女生" to "带点坏笑的追求感，敢开小玩笑但留余地",
        "情场" to "老练自信，掌控节奏",
        "风流" to "文艺轻佻，像夜里的诗",
        // 情感浓
        "走心" to "真诚讲感受，不浮夸",
        "深情" to "认真且专一，情绪浓度高",
        "宝贝" to "亲昵甜软，撒娇感",
        "甜宠" to "把对方当宝贝宠，宠得明目张胆",
        "撒娇" to "软软的、有点赖，带点小要求",
        "暖男" to "先照顾对方的情绪和身体，再谈别的",
        "肉麻" to "敢说黏人的话，不怕腻，让对方起鸡皮疙瘩",
        "思念" to "把想念说出口，讲具体画面不讲空话",
        "宠溺" to "无条件站对方这边，语气温软",
        "治愈" to "温柔接住对方的坏心情，给安全感",
        "痴情" to "只对一个人上心，毫不掩饰",
        "心动" to "把心动讲清楚，讲那一刻的细节"
    )

    private fun styleDesc(style: String): String =
        STYLE_DESC[style] ?: "按这个风格说话"

    /** 情感浓度对语气的额外约束 */
    private fun emotionRule(level: String): String = when (level) {
        "克制" -> "情绪表达克制，不要连续感叹号，不要堆形容词"
        "适中" -> "情绪表达自然，真诚但不夸张"
        "浓烈" -> "情绪表达到位，可以有强烈的喜欢和在乎，允许一句直白的告白"
        "肉麻" -> "尽情肉麻，黏人、宠溺、撒娇都可以，允许叠词和亲密称呼，不要收敛"
        else -> "情绪表达自然"
    }

    private const val SYSTEM_REPLY = """你是"恋爱军师"，中文恋爱聊天顾问。根据【关系阶段】和【风格】，为用户的聊天场景生成回复建议。
规则：
1) 口语化，像真人发微信，不要书面语、不要解释；
2) 每条 15~60 字，可用 1 个 emoji，不用网络烂梗；
3) 3 条候选要有差异：一条稳、一条进、一条俏皮；
4) 不要油腻、不要冒犯、不要编造事实；
5) 只输出 JSON：{"replies":[{"text":"...","style":"稳/进/俏皮","why":"一句话理由"}]}"""

    private const val SYSTEM_SAY = """你是"恋爱军师"，中文恋爱聊天顾问。用户只说了自己想达到的目的，请为 TA 写出可以直接发给对方的话。
规则：
1) 口语化，像真人发微信，不要书面语、不要解释；
2) 每条 15~60 字，可用 1 个 emoji，不用网络烂梗；
3) 3 条候选要有差异：一条稳、一条进、一条俏皮；
4) 不要油腻、不要冒犯、不要编造事实；
5) 只输出 JSON：{"replies":[{"text":"...","style":"稳/进/俏皮","why":"一句话理由"}]}"""

    private const val SYSTEM_POLISH = """你是"恋爱军师"。把给定的模板话术改写成更像真人发的微信消息。
规则：
1) 保留原意与关键信息，口语化，20~60 字，最多 1 个 emoji；
2) 不要书面语、不要解释、不要油腻；
3) 只输出 JSON：{"replies":[{"text":"...","style":"润色","why":"一句话理由"}]}"""

    private const val PERSONA_BY_STYLE = """
风格说明：
- 撩女生：带点坏笑的追求感，敢开小玩笑但留余地
- 高情商：共情对方情绪，不评判，语气舒服
- 走心：真诚讲感受，不浮夸
- 风流：文艺轻佻，像夜里的诗
- 情场：老练自信，掌控节奏
- 成熟：稳、有担当，不幼稚
- 细腻：注意对方说过的细节
- 深情：认真且专一，情绪浓度高
- 宝贝：亲昵甜软，撒娇感
阶段说明：
- 初识期：还在互相了解，不要越界，不要谈感情承诺
- 暧昧期：可以撩、可以试探，但别急着表白
- 热恋期：可以甜、可以亲密
- 挽回期：低姿态、不纠缠、不施压，给对方台阶
"""

    fun buildReplyMessages(
        stage: String,
        style: String,
        opponentText: String,
        intent: String = ""
    ): List<LlmClient.Msg> {
        val user = buildString {
            append("【关系阶段】").append(stage).append('\n')
            append("【风格】").append(style).append('\n')
            append("【对方原话】").append(opponentText.trim()).append('\n')
            append("【我的意图】").append(intent.trim())
        }
        return listOf(
            LlmClient.Msg("system", SYSTEM_REPLY + "\n" + persona(stage, style)),
            LlmClient.Msg("user", user)
        )
    }

    fun buildSayMessages(stage: String, style: String, intent: String): List<LlmClient.Msg> {
        val user = buildString {
            append("【关系阶段】").append(stage).append('\n')
            append("【风格】").append(style).append('\n')
            append("【我的意图】").append(intent.trim()).append('\n')
            append("【对方原话】")
        }
        return listOf(
            LlmClient.Msg("system", SYSTEM_SAY + "\n" + persona(stage, style)),
            LlmClient.Msg("user", user)
        )
    }

    fun buildPolishMessages(stage: String, style: String, template: String): List<LlmClient.Msg> {
        val user = buildString {
            append("【关系阶段】").append(stage).append('\n')
            append("【风格】").append(style).append('\n')
            append("【模板话术】").append(template.trim()).append('\n')
            append("【我的意图】让它更自然、更像我自己说的")
        }
        return listOf(
            LlmClient.Msg("system", SYSTEM_POLISH + "\n" + persona(stage, style)),
            LlmClient.Msg("user", user)
        )
    }

    private fun persona(stage: String, style: String): String =
        PERSONA_BY_STYLE + "\n本次要求：阶段=$stage，风格=$style。"
}
