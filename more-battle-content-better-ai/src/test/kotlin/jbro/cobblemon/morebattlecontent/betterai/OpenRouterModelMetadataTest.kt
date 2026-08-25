package jbro.cobblemon.morebattlecontent.betterai

import java.net.URI
import java.util.concurrent.CompletableFuture
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelCapabilityCache
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenRouterModelMetadataTest {
    @Test
    fun `official endpoint derives a single model metadata uri including variants`() {
        assertEquals(
            URI.create("https://openrouter.ai/api/v1/model/openai/gpt-5:free"),
            OpenRouterModelMetadata.metadataUri(
                URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "openai/gpt-5:free",
            ),
        )
        assertEquals(
            URI.create("https://eu.openrouter.ai/api/v1/model/anthropic/claude-sonnet-4"),
            OpenRouterModelMetadata.metadataUri(
                URI.create("https://eu.openrouter.ai/api/v1/chat/completions"),
                "anthropic/claude-sonnet-4",
            ),
        )
        assertNull(
            OpenRouterModelMetadata.metadataUri(
                URI.create("https://router.example.test/v1/chat/completions"),
                "test/model",
            ),
        )
        assertNull(
            OpenRouterModelMetadata.metadataUri(
                URI.create("http://openrouter.ai/api/v1/chat/completions"),
                "test/model",
            ),
        )
    }

    @Test
    fun `metadata parser distinguishes reasoning support from omitted metadata`() {
        val supported = OpenRouterModelMetadata.parse("""{
          "data": {
            "id": "test/reasoning",
            "supported_parameters": ["max_tokens", "temperature", "reasoning", "structured_outputs"],
            "reasoning": {
              "supported_efforts": ["high", "medium", "low"],
              "default_effort": "medium",
              "mandatory": false
            }
          }
        }""")
        val dynamic = OpenRouterModelMetadata.parse("""{
          "data": {
            "id": "openrouter/auto",
            "supported_parameters": ["max_tokens", "temperature", "structured_outputs"]
          }
        }""")

        assertTrue(supported.supportsReasoning)
        assertEquals(setOf("high", "medium", "low"), supported.supportedReasoningEfforts)
        assertFalse(dynamic.supportsReasoning)
        assertNull(dynamic.supportedReasoningEfforts)
    }

    @Test
    fun `capability cache is nonblocking and retains successful metadata`() {
        val response = CompletableFuture<String>()
        val cache = OpenRouterModelCapabilityCache { response }

        cache.refresh()
        assertNull(cache.current())

        response.complete("""{
          "data": {
            "id": "test/model",
            "supported_parameters": ["reasoning"],
            "reasoning": {"supported_efforts": null, "mandatory": false}
          }
        }""")

        assertTrue(cache.current()!!.supportsReasoning)
    }

    @Test
    fun `failed metadata leaves capability unknown instead of failing decisions`() {
        val cache = OpenRouterModelCapabilityCache {
            CompletableFuture.failedFuture(IllegalStateException("offline"))
        }

        cache.refresh()

        assertNull(cache.current())
    }
}
