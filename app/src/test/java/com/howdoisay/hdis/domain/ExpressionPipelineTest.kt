package com.howdoisay.hdis.domain

import com.howdoisay.hdis.data.ExpressionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExpressionPipelineTest {
    private val configuration = ProviderCredentials("app", "token", "resource", "ark", "ep-test")

    @Test fun `passes recorded audio directly to Ark`() = runBlocking {
        val audio = File("unused.wav")
        val ark = FakeArk(Result.success("Can I pay by card?"))
        val result = ExpressionPipeline(ark).translate(audio, configuration)
        assertEquals("Can I pay by card?", result.getOrThrow())
        assertEquals(audio, ark.receivedAudio)
    }

    @Test fun `does not call Ark when configuration is missing`() = runBlocking {
        val ark = FakeArk(Result.success("unused"))
        val result = ExpressionPipeline(ark).translate(File("unused.wav"), ProviderCredentials())
        assertTrue(result.isFailure)
        assertEquals(null, ark.receivedAudio)
    }

    private class FakeArk(private val answer: Result<String>) : EnglishExpressionService {
        var receivedAudio: File? = null
        override suspend fun express(audioFile: File, credentials: ArkCredentials): Result<String> {
            receivedAudio = audioFile
            return answer
        }
        override suspend fun testConnection(credentials: ArkCredentials): Result<Unit> = Result.success(Unit)
    }
}
