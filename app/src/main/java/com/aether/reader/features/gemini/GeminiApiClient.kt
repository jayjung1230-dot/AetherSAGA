package com.aether.reader.features.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Robust, lightweight in-app Gemini REST API Client.
 * Communicates directly with Google's generativelanguage.googleapis.com (gemini-1.5-flash)
 * using standard HTTPS JSON without requiring any external app or Google Play Services.
 */
object GeminiApiClient {

    private const val MODEL = "gemini-1.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    // In-memory LRU cache to prevent re-querying identical prompts
    private val responseCache = Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 30
    })

    /**
     * Executes a raw generateContent request to Gemini API.
     */
    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String = "당신은 깊이 있는 통찰을 제공하는 독서 및 인문학 전문 어시스턴트입니다.",
        temperature: Double = 0.7,
        maxTokens: Int = 800
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) throw IOException("Gemini API Key is missing.")

        val cacheKey = "$prompt|$systemInstruction"
        responseCache[cacheKey]?.let { return@withContext it }

        val urlStr = "$BASE_URL?key=$cleanKey"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true

        val contentsArray = JSONArray()

        // System Instruction / Context turn
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
        })
        contentsArray.put(JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", "네, 해당 문맥을 분석하여 명쾌하고 지적인 통찰을 제공하겠습니다.") }) })
        })

        // User Query
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) })
        })

        val payload = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
            })
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code == 200) {
            val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(respStr)
            val candidates = json.getJSONArray("candidates")
            val text = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
            responseCache[cacheKey] = text
            text
        } else {
            val errStr = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
            val errorMsg = try {
                JSONObject(errStr).getJSONObject("error").getString("message")
            } catch (_: Throwable) {
                errStr
            }
            throw IOException("Gemini API ($code): $errorMsg")
        }
    }

    /**
     * Synthesizes a profound 1-2 sentence intellectual bridge connecting two contrasting books.
     */
    suspend fun generateSerendipityInsight(
        apiKey: String,
        bookTitleA: String,
        authorA: String,
        quoteA: String,
        noteA: String?,
        bookTitleB: String,
        authorB: String,
        quoteB: String,
        noteB: String?
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
다음 두 권의 서로 다른 책에서 발췌한 인용구와 독자의 메모를 읽고, 두 텍스트가 시대를 넘어 마주치는 '핵심 지적 통찰과 철학적 연결 고리'를 한국어로 1~2문장으로 명쾌하고 깊이 있게 작성해주세요.

[도서 A] 《$bookTitleA》 ($authorA)
- 인용구: "$quoteA"
${if (!noteA.isNullOrBlank()) "- 독자 메모: $noteA" else ""}

[도서 B] 《$bookTitleB》 ($authorB)
- 인용구: "$quoteB"
${if (!noteB.isNullOrBlank()) "- 독자 메모: $noteB" else ""}

※ 작성 지침:
- 군더더기 서두나 결론 없이, 바로 두 생각의 충돌 또는 융합을 짚는 1~2문장의 핵심 통찰만 출력하세요.
- 따옴표나 불필요한 번호 표시는 생략하세요.
""".trimIndent()

        val sys = "당신은 인문학, 철학, 문학 간의 보이지 않는 연결 고리를 꿰뚫어 보는 지적 서평가입니다."
        try {
            generateContent(apiKey, prompt, sys, temperature = 0.6, maxTokens = 250)
        } catch (_: Throwable) {
            "두 텍스트가 공유하는 인간 본성에 대한 깊은 사유와 실존적 통찰의 접점"
        }
    }

    /**
     * Synthesizes an intellectual growth narrative from 4 milestone quotes across a book.
     */
    suspend fun generateEvolutionSummary(
        apiKey: String,
        bookTitle: String,
        author: String,
        quotes: List<String>
    ): String = withContext(Dispatchers.IO) {
        val quotesText = quotes.mapIndexed { idx, q -> "${idx + 1}단계: \"$q\"" }.joinToString("\n")
        val prompt = """
도서 《$bookTitle》 ($author)의 1장부터 완독까지 남긴 다음 4개의 독서 메모 흐름을 읽고, 독자의 생각이 어떻게 진화하고 확장되었는지를 1문장(최대 100자)으로 요약해 주세요.

$quotesText

※ 작성 지침: 단순 나열이 아닌, '초반의 의심/질문에서 시작해 완독 후 어떻게 새로운 인식에 도달했는지'의 생각의 진화 과정을 한 문장으로 압축하세요.
""".trimIndent()

        val sys = "독자의 사유와 인식의 성장 과정을 날카롭게 포착하는 독서 코치입니다."
        try {
            generateContent(apiKey, prompt, sys, temperature = 0.6, maxTokens = 200)
        } catch (_: Throwable) {
            "한 권의 책을 통과하며 기존의 고정관념을 깨고 새로운 시각으로 확장된 사유의 여정"
        }
    }

    /**
     * Summarizes an in-book discussion dialogue into a crisp philosophical quote for SNS cards.
     */
    suspend fun generateDiscussionSummary(
        apiKey: String,
        bookTitle: String,
        author: String,
        quote: String,
        userQuery: String,
        aiReply: String
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
도서 《$bookTitle》 ($author)의 본문 인용구와 독자-AI 간의 토론 문답입니다. 이 대화에서 가장 핵심적인 철학적 결론이자 통찰이 되는 문장을 1~2문장으로 정제해 주세요.

- 원문: "$quote"
- 독자 질문: "$userQuery"
- 답변 요약: "$aiReply"

※ 작성 지침:
- SNS 공유 카드에 인쇄될 핵심 문장이므로 울림이 있고 단단한 문체로 1~2문장만 작성하세요.
""".trimIndent()

        val sys = "복잡한 토론에서 정수를 뽑아내는 아포리즘 및 명언 에디터입니다."
        try {
            generateContent(apiKey, prompt, sys, temperature = 0.5, maxTokens = 200)
        } catch (_: Throwable) {
            aiReply.take(120)
        }
    }
}
