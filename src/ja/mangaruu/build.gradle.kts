import io.github.keiyoushi.gradle.api.ContentWarning
import org.gradle.api.tasks.testing.Test

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mangaruu"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Mangaruu"
        lang = "ja"
        baseUrl = "https://mangaruu.com"
    }

    deeplink {
        path("/m/..*")
    }
}

android {
    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    inputs.property("mangaruuLiveTest", providers.environmentVariable("MANGARUU_LIVE_TEST").orElse("0"))
    inputs.property("mangaruuCapturedMetadata", providers.environmentVariable("MANGARUU_CAPTURED_METADATA").orElse("0"))
    testLogging.showStandardStreams = true
}

// Source generation belongs to the APK; the JVM tests define no extension entry point.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
