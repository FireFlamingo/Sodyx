plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.sodyx.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.sodyx.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        ndk {
            val requested = providers.gradleProperty("sodyx.abi")
                .orElse("arm64-v8a,x86_64").get().split(',')
            require(requested.all { it in setOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") })
            abiFilters += requested
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        resources.excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll")
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // SDK and dependency upgrades require a deliberate compatibility review.
        disable +=
            setOf(
                "GradleDependency",
                "AndroidGradlePluginVersion",
                "NewerVersionAvailable",
                "OldTargetApi",
                // Single-ABI APKs are intentional; default builds include x86_64.
                "ChromeOsAbiSupport"
            )
    }

    testOptions {
        unitTests.all {
            it.systemProperty(
                "sodyx.mainManifest",
                file("src/main/AndroidManifest.xml").absolutePath
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(project(":domain"))
    implementation(project(":security"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
