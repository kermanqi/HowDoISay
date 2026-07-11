package com.howdoisay.hdis.domain

import com.howdoisay.hdis.data.ExpressionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExpressionPipelineTest {
    private val configuration = ProviderCredentials("app", "token", "resource", "ark", "ep-test")

    @Test fun `passes a transcript from ASR to Ark`() = runBlocking {
        val asr = FakeAsr(Result.success("我想问能不能刷卡"))
        val ark = FakeArk(Result.success("Can I pay by card?"))
        val result = ExpressionPipeline(asr, ark).translate(File("unused.wav"), configuration)
        assertEquals("Can I pay by card?", result.getOrThrow())
        assertEquals("我想问能不能刷卡", ark.receivedTranscript)
    }

    @Test fun `does not call Ark when ASR fails`() = runBlocking {
        val asr = FakeAsr(Result.failure(ExpressionException(ExpressionError.NoSpeech)))
        val ark = FakeArk(Result.success("unused"))
        val result = ExpressionPipeline(asr, ark).translate(File("unused.wav"), configuration)
        assertTrue(result.isFailure)
        assertEquals(null, ark.receivedTranscript)
    }

    private class FakeAsr(private val answer: Result<String>) : AudioTranscriber {
        override suspend fun transcribe(audioFile: File, credentials: AsrCredentials): Result<String> = answer
    }

    private class FakeArk(private val answer: Result<String>) : EnglishExpressionService {
        var receivedTranscript: String? = null
        override suspend fun express(chineseTranscript: String, credentials: ArkCredentials): Result<String> {
            receivedTranscript = chineseTranscript
            return answer
        }
        override suspend fun testConnection(credentials: ArkCredentials): Result<Unit> = Result.success(Unit)
    }
}
