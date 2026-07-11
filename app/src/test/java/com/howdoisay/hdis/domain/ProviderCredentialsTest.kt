package com.howdoisay.hdis.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialsTest {
    @Test fun `requires Ark fields only`() {
        assertFalse(ProviderCredentials().isReady())
        assertTrue(ProviderCredentials(arkApiKey = "ark", arkEndpointId = "ep-123").isReady())
    }
}
