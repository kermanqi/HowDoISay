package com.howdoisay.hdis.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialsTest {
    @Test fun `requires all provider fields`() {
        assertFalse(ProviderCredentials().isReady())
        assertTrue(ProviderCredentials("id", "token", "resource", "ark", "ep-123").isReady())
    }
}
