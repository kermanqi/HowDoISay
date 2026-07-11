package com.howdoisay.hdis.data

import android.util.Base64
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

    override suspend fun express(audioFile: java.io.File, credentials: ArkCredentials): Result<String> {
        if (!credentials.isReady()) return Result.failure(ExpressionException(ExpressionError.MissingConfiguration))
        if (!audioFile.exists() || audioFile.length() <= WAV_HEADER_BYTES) {
            return Result.failure(ExpressionException(ExpressionError.NoSpeech))
        }

        val audio = withContext(Dispatchers.IO) {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", EnglishExpressionPrompt.SYSTEM.trimIndent()))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put(
                    "text",
                    "请理解这段中文语音，并按照系统要求只输出一句自然的英文表达。"
                ))
                .put(JSONObject().put("type", "input_audio").put(
                    "input_audio",
                    JSONObject().put("data", audio).put("format", "wav")
                ))))
        return complete(credentials, messages)
    }

    private suspend fun complete(credentials: ArkCredentials, messages: JSONArray): Result<String> {
        val request = withContext(Dispatchers.IO) {
            Request.Builder()
                .url(ARK_URL)
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .header("Content-Type", "application/json")
                .post(JSONObject()
                    .put("model", credentials.endpointId)
                    .put("temperature", 0.2)
                    .put("max_tokens", 80)
                    .put("messages", messages)
                    .toString().toRequestBody(JSON))
                .build()
        }

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
        if (!credentials.isReady()) return Result.failure(ExpressionException(ExpressionError.MissingConfiguration))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Reply with OK only."))
            .put(JSONObject().put("role", "user").put("content", "Reply with OK only."))
        return complete(credentials, messages).map { Unit }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val ARK_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ExpressionException(val error: ExpressionError) : Exception()
