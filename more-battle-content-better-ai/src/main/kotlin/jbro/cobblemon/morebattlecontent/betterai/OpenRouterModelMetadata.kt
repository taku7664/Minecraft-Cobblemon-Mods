package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class OpenRouterModelCapabilities(
    val supportedParameters: Set<String>,
    val reasoningMetadataPresent: Boolean,
    val supportedReasoningEfforts: Set<String>? = null,
) {
    val supportsReasoning: Boolean
        get() = reasoningMetadataPresent && "reasoning" in supportedParameters
}

internal object OpenRouterModelMetadata {
    private val modelIdPattern = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+")

    fun metadataUri(endpoint: URI, model: String): URI? {
        if (!endpoint.scheme.equals("https", ignoreCase = true)) return null
        val host = endpoint.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "openrouter.ai" && !host.endsWith(".openrouter.ai")) return null
        if (!modelIdPattern.matches(model)) return null
        return URI(endpoint.scheme, endpoint.authority, "/api/v1/model/$model", null, null)
    }

    fun parse(responseJson: String): OpenRouterModelCapabilities {
        val data = JsonParser.parseString(responseJson).asJsonObject.getAsJsonObject("data")
        val supportedParameters = data.getAsJsonArray("supported_parameters")
            ?.mapTo(linkedSetOf()) { it.asString }
            .orEmpty()
        val reasoningElement = data["reasoning"]
        val reasoningPresent = reasoningElement != null && !reasoningElement.isJsonNull
        val efforts = if (reasoningPresent) {
            val element = reasoningElement.asJsonObject["supported_efforts"]
            if (element == null || element.isJsonNull) null else element.asJsonArray.mapTo(linkedSetOf()) { it.asString }
        } else {
            null
        }
        return OpenRouterModelCapabilities(supportedParameters, reasoningPresent, efforts)
    }
}

internal fun interface OpenRouterModelMetadataTransport {
    fun fetch(): CompletionStage<String>
}

internal class OpenRouterModelCapabilityCache(
    private val transport: OpenRouterModelMetadataTransport,
) {
    private val refreshing = AtomicBoolean(false)
    private val capabilities = AtomicReference<OpenRouterModelCapabilities?>()

    fun current(): OpenRouterModelCapabilities? = capabilities.get()

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            transport.fetch().whenComplete { response, throwable ->
                if (throwable == null) {
                    runCatching { OpenRouterModelMetadata.parse(response) }
                        .onSuccess(capabilities::set)
                }
                refreshing.set(false)
            }
        } catch (_: Exception) {
            refreshing.set(false)
        }
    }
}

internal class OpenRouterModelMetadataHttpTransport(
    metadataUri: URI,
    timeoutMillis: Long,
    executor: Executor,
) : OpenRouterModelMetadataTransport {
    private val timeout = Duration.ofMillis(minOf(timeoutMillis, 5_000L))
    private val request = HttpRequest.newBuilder(metadataUri)
        .timeout(timeout)
        .header("Accept", "application/json")
        .GET()
        .build()
    private val client = HttpClient.newBuilder()
        .executor(executor)
        .connectTimeout(timeout)
        .build()

    override fun fetch(): CompletionStage<String> {
        val httpFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val result = CompletableFuture<String>()
        httpFuture.whenComplete { response, throwable ->
            if (throwable != null) {
                result.completeExceptionally(throwable)
            } else {
                runCatching {
                    require(response.statusCode() in 200..299) {
                        "OpenRouter model metadata returned HTTP ${response.statusCode()}"
                    }
                    response.body()
                }.fold(result::complete, result::completeExceptionally)
            }
        }
        result.whenComplete { _, _ ->
            if (result.isCancelled) httpFuture.cancel(true)
        }
        return result
    }
}
