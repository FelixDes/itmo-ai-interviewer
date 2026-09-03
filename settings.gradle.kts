plugins {
    // На хосте может не быть JDK 25 — Gradle скачает нужный тулчейн сам
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "aiinterviewer"
