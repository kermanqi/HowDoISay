package com.howdoisay.hdis.data

import android.util.Base64
import com.howdoisay.hdis.domain.AudioTranscriber
import com.howdoisay.hdis.domain.AsrCredentials
import com.howdoisay.hdis.domain.ExpressionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A short-utterance adapter for Doubao BigASR. The endpoint returns the final
 * transcript in one response, which matches HDIS's explicit tap-to-stop flow.
 * The interface deliberately hides this transport so a streaming SDK can replace it later.
 */
class DoubaoAudioTranscriber(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
) : AudioTranscriber {

    override suspend fun transcribe(audioFile: File, credentials: AsrCredentials): Result<String> {
        if (!credentials.isReady()) return Result.failure(ExpressionException(ExpressionError.MissingConfiguration))
        if (!audioFile.exists() || audioFile.length() <= WAV_HEADER_BYTES) {
            return Result.failure(ExpressionException(ExpressionError.NoSpeech))
        }

        val request = withContext(Dispatchers.IO) { Request.Builder()
            .url(ASR_URL)
            .header("X-Api-App-Key", credentials.appId)
            .header("X-Api-Access-Key", credentials.accessToken)
            .header("X-Api-Resource-Id", credentials.resourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
            .post(JSONObject()
                .put("user", JSONObject().put("uid", credentials.appId))
                .put("audio", JSONObject().put("data", Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)))
                .put("request", JSONObject().put("model_name", "bigmodel"))
                .toString().toRequestBody(JSON))
            .build() }

        return withContext(Dispatchers.IO) { client.await(request).fold(
            onSuccess = { response ->
                response.use {
                    if (!it.isSuccessful) return@withContext Result.failure(ExpressionException(statusError(it.code)))
                    val providerStatus = it.header("X-Api-Status-Code")
                    if (providerStatus != null && providerStatus != SUCCESS_STATUS) {
                        return@withContext Result.failure(ExpressionException(providerStatusError(it.header("X-Api-Message"))))
                    }
                    val content = it.body?.string().orEmpty()
                    val transcript = JSONObject(content).optJSONObject("result")?.optString("text").orEmpty().trim()
                    if (transcript.isBlank()) Result.failure(ExpressionException(ExpressionError.NoSpeech))
                    else Result.success(transcript)
                }
            },
            onFailure = { Result.failure(ExpressionException(it.toExpressionError())) }
        ) }
    }

    private fun providerStatusError(message: String?): ExpressionError {
        val normalized = message.orEmpty().lowercase()
        return if (listOf("auth", "token", "access", "resource", "appid", "app key").any(normalized::contains)) {
            ExpressionError.Unauthorized
        } else ExpressionError.ProviderFailure(message?.takeIf(String::isNotBlank))
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val SUCCESS_STATUS = "20000000"
        const val ASR_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
