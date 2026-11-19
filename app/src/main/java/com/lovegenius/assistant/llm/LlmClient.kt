package com.lovegenius.assistant.llm

import com.lovegenius.assistant.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的 chat/completions 客户端，默认打到 DeepSeek（api.deepseek.com/v1）。
 */
class LlmClient {

    data class Msg(val role: String, val content: String)
    data class Reply(val text: String, val style: String, val why: String)

    sealed class Result {
        data class Ok(val replies: List<Reply>) : Result()
        data class Err(val message: String) : Result()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /**
     * 各家 base 的写法不同，统一拼出 /chat/completions：
     *  - DeepSeek：https://api.deepseek.com 或 .../v1 都要能拼对
     *  - 豆包/智谱：base 已带版本段（/api/v3、/api/paas/v4），直接用
     */
    private fun endpoint(): String {
        var base = Prefs.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) base = Prefs.DEFAULT_BASE_URL
        if (base.contains("deepseek", ignoreCase = true) && !base.endsWith("/v1")) {
            base = "$base/v1"
        }
        return "$base/chat/completions"
    }

    suspend fun complete(messages: List<Msg>, temperature: Double = 0.9): Result =
        withContext(Dispatchers.IO) {
            val key = Prefs.apiKey
            if (key.isBlank()) return@withContext Result.Err("还没填 API Key，请在 App 里设置")

            val prov = Providers.current()
            val payload = JSONObject().apply {
                put("model", Prefs.model.ifBlank { Prefs.DEFAULT_MODEL })
                put("temperature", temperature)
                // 推理型模型会先花 token 思考，给小了正文就是空的
                put("max_tokens", if (prov.reasoning) 2000 else 600)
                put("stream", false)
                // DeepSeek 支持 JSON 模式，返回更规矩；豆包/智谱不开，避免报错
                if (prov.jsonMode) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
                put("messages", JSONArray().apply {
                    messages.forEach {
                        put(JSONObject().put("role", it.role).put("content", it.content))
                    }
                })
            }

            val req = Request.Builder()
                .url(endpoint())
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(mediaJson))
                .build()

            try {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.Err(
                            "接口 ${resp.code}：" + body.take(160)
                        )
                    }
                    val content = JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                    val replies = parseReplies(content)
                    if (replies.isEmpty()) Result.Err("模型没返回可用内容")
                    else Result.Ok(replies)
                }
            } catch (t: Throwable) {
                Result.Err("网络失败：" + (t.message ?: t.javaClass.simpleName))
            }
        }

    /** 模型可能包 ```json 代码块，或偷懒直接返回数组，都要兜住。 */
    private fun parseReplies(raw: String): List<Reply> {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```")
                .substringBefore("```").trim()
        }
        val jsonText = runCatching {
            if (s.startsWith("{")) s else s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1)
        }.getOrNull() ?: return emptyList()

        return runCatching {
            val arr = JSONObject(jsonText).getJSONArray("replies")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isEmpty()) null
                else Reply(
                    text = text,
                    style = o.optString("style", "稳"),
                    why = o.optString("why", "")
                )
            }
        }.getOrElse { emptyList() }
    }
}
