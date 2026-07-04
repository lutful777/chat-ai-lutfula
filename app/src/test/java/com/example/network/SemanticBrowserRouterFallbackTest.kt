package com.example.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticBrowserRouterFallbackTest {
    @Test
    fun runtimeConfigRequiresCompleteTextApiSettings() {
        val incomplete = TextApiConfigSnapshot(
            apiKey = "",
            baseUrl = "https://example.com/v1",
            path = "/chat/completions",
            model = "example-model"
        )
        val complete = incomplete.copy(apiKey = "test-key")

        assertFalse(incomplete.isComplete)
        assertTrue(complete.isComplete)
    }

    @Test
    fun endpointJoinsBaseUrlAndPathWithoutDuplicateSlash() {
        val config = TextApiConfigSnapshot(
            apiKey = "test-key",
            baseUrl = "https://example.com/v1/",
            path = "chat/completions",
            model = "example-model"
        )

        assertTrue(config.endpoint == "https://example.com/v1/chat/completions")
    }
}
