plugins {
    kotlin("jvm") version "2.3.20"
    application
}
repositories { mavenCentral() }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.setSrcDirs(listOf(".", "../../../app/src/main/java", "../../../core/src/commonMain/kotlin"))
        kotlin.include("*.kt", "io/legado/app/api/ReaderProvider.kt",
            "io/legado/app/lib/cronet/CronetLibraryIntegrity.kt", "io/legado/app/web/api/WebApiRequest.kt")
    }
}
application { mainClass.set("security.IngressRegressionKt") }
