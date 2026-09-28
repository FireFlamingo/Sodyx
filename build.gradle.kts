plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target(
            "app/src/**/*.kt",
            "domain/src/**/*.kt",
            "security/src/**/*.kt",
            "framing/src/**/*.kt"
        )
        ktlint("1.7.1")
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "app/*.gradle.kts",
            "domain/*.gradle.kts",
            "security/*.gradle.kts",
            "framing/*.gradle.kts"
        )
        ktlint("1.7.1")
    }
    format("text") {
        target(
            "README.md",
            "docs/**/*.md",
            "*.properties",
            "gradle/*.toml",
            ".gitignore",
            ".gitattributes"
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
