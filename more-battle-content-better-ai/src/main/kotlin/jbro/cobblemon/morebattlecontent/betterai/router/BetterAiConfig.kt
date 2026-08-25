package jbro.cobblemon.morebattlecontent.betterai.router

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

internal enum class BetterAiDecisionMode(
    val reasoningEffort: String,
    val maximumOutputTokens: Int,
) {
    QUALITY("high", 8_192),
    BALANCED("medium", 4_096),
    ECONOMY("low", 2_048),
}

internal data class BetterAiConfig(
    val schemaVersion: Int = 3,
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val timeoutMillis: Long = 10_000,
    val softTimeoutMillis: Long = 0,
    val maximumConcurrentRequests: Int = 2,
    val maximumQueuedRequests: Int = 32,
    // Schema 1 compatibility field. Accepted and validated, but no longer limits Router decisions:
    // the selected Router Brain is consulted for every request with more than one legal action.
    val maximumCallsPerBattle: Int = 8,
    val mindGamesEnabled: Boolean = true,
    val logDecisionSummary: Boolean = false,
    val promptVersion: String = "humanlike-v1",
    val requireStructuredOutput: Boolean = true,
    val decisionMode: BetterAiDecisionMode = BetterAiDecisionMode.QUALITY,
    val routerPolicy: RouterPolicyConfig = RouterPolicyConfig.bossOnlyMbcPve(),
    val appUrl: String = "",
    val appName: String = "Cobblemon More Battle Content",
) {
    init {
        require(schemaVersion == 3) { "Unsupported Better AI config schema" }
        val endpointUri = runCatching { URI.create(endpoint) }.getOrNull()
        require(endpointUri?.scheme.equals("https", ignoreCase = true) && !endpointUri?.host.isNullOrBlank()) {
            "Better AI endpoint must be a valid HTTPS URI with a host"
        }
        require(timeoutMillis in 1_000..20_000)
        require(softTimeoutMillis == 0L || softTimeoutMillis in 500..timeoutMillis)
        require(maximumConcurrentRequests in 1..16)
        require(maximumQueuedRequests in 0..256)
        require(maximumCallsPerBattle in 1..32)
        require(promptVersion == "humanlike-v1")
        require(appName.isNotBlank() && appName.length <= 80)
        require(appUrl.isBlank() || appUrl.startsWith("https://"))
    }

    val externallyUsable: Boolean get() = enabled && apiKey.isNotBlank() && model.isNotBlank()

    override fun toString(): String =
        "BetterAiConfig(" +
            "schemaVersion=$schemaVersion, " +
            "enabled=$enabled, " +
            "apiKey=<redacted>, " +
            "model=$model, " +
            "endpoint=$endpoint, " +
            "timeoutMillis=$timeoutMillis, " +
            "softTimeoutMillis=$softTimeoutMillis, " +
            "maximumConcurrentRequests=$maximumConcurrentRequests, " +
            "maximumQueuedRequests=$maximumQueuedRequests, " +
            "maximumCallsPerBattle=$maximumCallsPerBattle, " +
            "mindGamesEnabled=$mindGamesEnabled, " +
            "logDecisionSummary=$logDecisionSummary, " +
            "promptVersion=$promptVersion, " +
            "requireStructuredOutput=$requireStructuredOutput, " +
            "decisionMode=$decisionMode, " +
            "routerDefaultMode=${routerPolicy.defaultMode}, " +
            "appUrl=$appUrl, " +
            "appName=$appName)"
}

internal object BetterAiConfigStore {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun loadOrCreate(path: Path): BetterAiConfig {
        if (!Files.exists(path)) writeNew(path, BetterAiConfig())
        return Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            decode(root)
        }
    }

    private fun decode(root: JsonObject): BetterAiConfig {
        val defaults = BetterAiConfig()
        val sourceSchema = root.int("schemaVersion", 1)
        require(sourceSchema in 1..3) { "Unsupported Better AI config schema" }
        val decisionMode = if (sourceSchema == 1) {
            BetterAiDecisionMode.QUALITY
        } else {
            enumValueOf<BetterAiDecisionMode>(root.string("decisionMode", defaults.decisionMode.name))
        }
        val routerPolicy = if (sourceSchema < 3) {
            RouterPolicyConfig.legacyAll()
        } else {
            decodeRouterPolicy(requireNotNull(root.getAsJsonObject("routerPolicy")) {
                "Schema 3 requires routerPolicy"
            })
        }
        return BetterAiConfig(
            schemaVersion = 3,
            enabled = root.boolean("enabled", defaults.enabled),
            apiKey = root.string("apiKey", defaults.apiKey),
            model = root.string("model", defaults.model),
            endpoint = root.string("endpoint", defaults.endpoint),
            timeoutMillis = root.long("timeoutMillis", defaults.timeoutMillis),
            softTimeoutMillis = root.long("softTimeoutMillis", defaults.softTimeoutMillis),
            maximumConcurrentRequests = root.int("maximumConcurrentRequests", defaults.maximumConcurrentRequests),
            maximumQueuedRequests = root.int("maximumQueuedRequests", defaults.maximumQueuedRequests),
            maximumCallsPerBattle = root.int("maximumCallsPerBattle", defaults.maximumCallsPerBattle),
            mindGamesEnabled = root.boolean("mindGamesEnabled", defaults.mindGamesEnabled),
            logDecisionSummary = root.boolean("logDecisionSummary", defaults.logDecisionSummary),
            promptVersion = root.string("promptVersion", defaults.promptVersion),
            requireStructuredOutput = root.boolean("requireStructuredOutput", defaults.requireStructuredOutput),
            decisionMode = decisionMode,
            routerPolicy = routerPolicy,
            appUrl = root.string("appUrl", defaults.appUrl),
            appName = root.string("appName", defaults.appName),
        )
    }

    private fun writeNew(path: Path, config: BetterAiConfig) {
        Files.createDirectories(requireNotNull(path.parent))
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, gson.toJson(encode(config)) + System.lineSeparator(), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun encode(config: BetterAiConfig): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", config.schemaVersion)
        addProperty("enabled", config.enabled)
        addProperty("apiKey", config.apiKey)
        addProperty("model", config.model)
        addProperty("endpoint", config.endpoint)
        addProperty("timeoutMillis", config.timeoutMillis)
        addProperty("softTimeoutMillis", config.softTimeoutMillis)
        addProperty("maximumConcurrentRequests", config.maximumConcurrentRequests)
        addProperty("maximumQueuedRequests", config.maximumQueuedRequests)
        addProperty("mindGamesEnabled", config.mindGamesEnabled)
        addProperty("logDecisionSummary", config.logDecisionSummary)
        addProperty("promptVersion", config.promptVersion)
        addProperty("requireStructuredOutput", config.requireStructuredOutput)
        addProperty("decisionMode", config.decisionMode.name)
        add("routerPolicy", encodeRouterPolicy(config.routerPolicy))
        addProperty("appUrl", config.appUrl)
        addProperty("appName", config.appName)
    }

    private fun JsonObject.string(name: String, default: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString ?: default

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: default

    private fun JsonObject.long(name: String, default: Long): Long =
        get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default

    private fun decodeRouterPolicy(root: JsonObject): RouterPolicyConfig {
        val defaultMode = enumValueOf<RouterActivationMode>(root.string("defaultMode", "LOCAL_ONLY"))
        val rulesRoot = requireNotNull(root.getAsJsonObject("contentRules")) {
            "Router policy requires contentRules"
        }
        val rules = linkedMapOf<String, RouterContentRule>()
        rulesRoot.entrySet().forEach { (contentId, element) ->
            require(element.isJsonObject) { "Router content rule for $contentId must be an object" }
            val rule = element.asJsonObject
            val mode = enumValueOf<RouterActivationMode>(rule.string("mode", ""))
            val tiers = rule.get("tiers")?.let { value ->
                require(value.isJsonArray) { "Router tiers for $contentId must be an array" }
                value.asJsonArray.mapTo(linkedSetOf()) { tier -> enumValueOf<BattleTrainerTier>(tier.asString) }
            }.orEmpty()
            rules[contentId] = RouterContentRule(mode, tiers)
        }
        return RouterPolicyConfig(defaultMode, rules)
    }

    private fun encodeRouterPolicy(config: RouterPolicyConfig): JsonObject = JsonObject().apply {
        addProperty("defaultMode", config.defaultMode.name)
        add("contentRules", JsonObject().apply {
            config.contentRules.forEach { (contentId, rule) ->
                add(contentId, JsonObject().apply {
                    addProperty("mode", rule.mode.name)
                    if (rule.mode == RouterActivationMode.DIFFICULTY_TIERS) {
                        add("tiers", gson.toJsonTree(rule.tiers.map { it.name }))
                    }
                })
            }
        })
    }
}
