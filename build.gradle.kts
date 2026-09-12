plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "domain/src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "domain/*.gradle.kts")
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
