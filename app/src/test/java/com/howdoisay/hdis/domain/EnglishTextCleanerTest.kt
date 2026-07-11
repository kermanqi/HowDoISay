package com.howdoisay.hdis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishTextCleanerTest {
    @Test fun `removes labels quotes and markdown`() {
        assertEquals(
            "Could I pay by card here?",
            EnglishTextCleaner.clean("```\nTranslation: \"Could I pay by card here?\"\n```")
        )
    }

    @Test fun `keeps the first nonempty answer line`() {
        assertEquals("I might be a little late today.", EnglishTextCleaner.clean("\nI might be a little late today.\nExtra text"))
    }

    @Test fun `removes a fenced language marker`() {
        assertEquals("Could I exchange this?", EnglishTextCleaner.clean("```text\nCould I exchange this?\n```"))
    }

    @Test fun `requires latin characters for usable output`() {
        assertTrue(EnglishTextCleaner.isUsableEnglish("Can I return this?"))
        assertFalse(EnglishTextCleaner.isUsableEnglish("可以退货吗"))
    }
}
