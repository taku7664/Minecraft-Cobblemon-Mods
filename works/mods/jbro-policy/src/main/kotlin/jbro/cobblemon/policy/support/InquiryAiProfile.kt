package jbro.cobblemon.policy.support

import java.nio.file.Files
import java.nio.file.Path

/** The review, like public chat, has no file, shell, MCP or administration tools. */
internal object InquiryAiProfile {
    const val NAME = "jbro-inquiry-review"

    @Synchronized
    fun ensure() {
        val expected = """
            ---
            name: $NAME
            description: Read-only inquiry review using only supplied evidence.
            mainAgent: true
            subagent: false
            inheritCustomizations: false
            excludeDefaultComponents: true
            tools: [finish]
            ---
            Review the supplied inquiry and evidence only. You have no tools. Do not run commands, read files or promise compensation.
        """.trimIndent()
        val profile = Path.of(System.getProperty("user.home"), ".gemini", "config", "agents", NAME, "agent.md")
        Files.createDirectories(profile.parent)
        if (!Files.exists(profile)) Files.writeString(profile, expected)
        check(Files.readString(profile).trim() == expected) { "Inquiry review profile differs from the restricted profile" }
    }
}
