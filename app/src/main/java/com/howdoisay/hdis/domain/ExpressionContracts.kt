package com.howdoisay.hdis.domain

data class ProviderCredentials(
    val asrAppId: String = "",
    val asrAccessToken: String = "",
    val asrResourceId: String = "volc.bigasr.auc_turbo",
    val arkApiKey: String = "",
    val arkEndpointId: String = DEFAULT_ARK_MODEL
) {
    fun asrCredentials(): AsrCredentials? = AsrCredentials(asrAppId, asrAccessToken, asrResourceId)
        .takeIf(AsrCredentials::isReady)

    fun arkCredentials(): ArkCredentials? = ArkCredentials(arkApiKey, arkEndpointId)
        .takeIf(ArkCredentials::isReady)

    fun isReady(): Boolean = asrCredentials() != null && arkCredentials() != null

    companion object {
        const val DEFAULT_ARK_MODEL = "doubao-seed-translation-250915"
    }
}

data class AsrCredentials(
    val appId: String,
    val accessToken: String,
    val resourceId: String
) {
    fun isReady(): Boolean = listOf(appId, accessToken, resourceId).all(String::isNotBlank)
}

data class ArkCredentials(
    val apiKey: String,
    val endpointId: String
) {
    fun isReady(): Boolean = apiKey.isNotBlank() && endpointId.isNotBlank()
}

sealed interface ExpressionError {
    data object MissingConfiguration : ExpressionError
    data object NoSpeech : ExpressionError
    data object NetworkUnavailable : ExpressionError
    data object Unauthorized : ExpressionError
    data object RateLimited : ExpressionError
    data object EmptyResponse : ExpressionError
    data class ProviderFailure(val message: String? = null) : ExpressionError
    data object Cancelled : ExpressionError
}

fun ExpressionError.userMessage(): String = when (this) {
    ExpressionError.MissingConfiguration -> "Check API settings"
    ExpressionError.NoSpeech -> "No speech detected"
    ExpressionError.NetworkUnavailable -> "Network unavailable"
    ExpressionError.Unauthorized -> "Check API settings"
    ExpressionError.RateLimited -> "Too many requests. Try again shortly."
    ExpressionError.EmptyResponse -> "Couldn’t produce English"
    is ExpressionError.ProviderFailure -> message ?: "Something went wrong. Try again."
    ExpressionError.Cancelled -> "Cancelled"
}

sealed interface ResultState {
    data object Idle : ResultState
    data object Listening : ResultState
    data object Creating : ResultState
    data class Success(val english: String) : ResultState
    data class Failure(val error: ExpressionError) : ResultState
}

interface AudioTranscriber {
    suspend fun transcribe(audioFile: java.io.File, credentials: AsrCredentials): Result<String>
}

interface EnglishExpressionService {
    suspend fun express(chineseTranscript: String, credentials: ArkCredentials): Result<String>
    suspend fun testConnection(credentials: ArkCredentials): Result<Unit>
}

class ExpressionPipeline(
    private val transcriber: AudioTranscriber,
    private val expressionService: EnglishExpressionService
) {
    suspend fun translate(audioFile: java.io.File, configuration: ProviderCredentials): Result<String> {
        val asr = configuration.asrCredentials()
            ?: return Result.failure(com.howdoisay.hdis.data.ExpressionException(ExpressionError.MissingConfiguration))
        val ark = configuration.arkCredentials()
            ?: return Result.failure(com.howdoisay.hdis.data.ExpressionException(ExpressionError.MissingConfiguration))
        val transcript = transcriber.transcribe(audioFile, asr).getOrElse { return Result.failure(it) }
        return expressionService.express(transcript, ark)
    }
}
