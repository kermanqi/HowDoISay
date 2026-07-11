package com.howdoisay.hdis.data

import com.howdoisay.hdis.domain.EnglishExpressionPrompt
import com.howdoisay.hdis.domain.EnglishExpressionService
import com.howdoisay.hdis.domain.EnglishTextCleaner
import com.howdoisay.hdis.domain.ExpressionError
import com.howdoisay.hdis.domain.ArkCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ArkEnglishExpressionService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) : EnglishExpressionService {

    override suspend fun express(chineseTranscript: String, credentials: ArkCredentials): Result<String> {
        if (!credentials.isReady()) return Result.failure(ExpressionException(ExpressionError.MissingConfiguration))
        if (chineseTranscript.isBlank()) return Result.failure(ExpressionException(ExpressionError.NoSpeech))

        val request = withContext(Dispatchers.IO) { Request.Builder()
            .url(ARK_URL)
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .header("Content-Type", "application/json")
            .post(JSONObject()
                .put("model", credentials.endpointId)
                .put("temperature", 0.2)
                .put("max_tokens", 80)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", EnglishExpressionPrompt.SYSTEM.trimIndent()))
                    .put(JSONObject().put("role", "user").put("content", chineseTranscript)))
                .toString().toRequestBody(JSON))
            .build() }

        return withContext(Dispatchers.IO) { client.await(request).fold(
            onSuccess = { response ->
                response.use {
                    if (!it.isSuccessful) return@withContext Result.failure(ExpressionException(statusError(it.code)))
                    val raw = JSONObject(it.body?.string().orEmpty())
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    val english = EnglishTextCleaner.clean(raw)
                    if (EnglishTextCleaner.isUsableEnglish(english)) Result.success(english)
                    else Result.failure(ExpressionException(ExpressionError.EmptyResponse))
                }
            },
            onFailure = { Result.failure(ExpressionException(it.toExpressionError())) }
        ) }
    }

    override suspend fun testConnection(credentials: ArkCredentials): Result<Unit> {
        return express("请回复 OK", credentials).map { Unit }
    }

    private companion object {
        const val ARK_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ExpressionException(val error: ExpressionError) : Exception()
