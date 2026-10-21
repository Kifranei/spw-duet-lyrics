import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "io.github.kifranei.duetlyrics"
version = providers.gradleProperty("version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

dependencies {
    // Provided by Salt Player at runtime: never bundle.
    compileOnly(kotlin("stdlib"))
    // Non-transitive: the API pulls the host's Compose UI stack, which a lyric-only plugin does not need.
    compileOnly(libs.spw.workshop.api) { isTransitive = false }
    compileOnly(libs.pf4j)
    kapt(libs.spw.workshop.api) { isTransitive = false }
    kapt(libs.pf4j) // PF4J @Extension annotation processor -> META-INF/extensions.idx

    // Bundled into lib/ of the .spmod (embedded-tag lyrics reader, LGPL-2.1).
    implementation(libs.jaudiotagger)

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation(libs.spw.workshop.api) { isTransitive = false }
    testImplementation(libs.pf4j)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spmod {
    PluginClass = "io.github.kifranei.duetlyrics.plugin.DuetLyricsPlugin"
    PluginId = "io.github.kifranei.duetlyrics"
    PluginVersion = project.version.toString()
    PluginProvider = "Kifranei"
    PluginName = "Duet Lyrics (TTML / ELRC)"
    PluginDescription = "解析 TTML 与增强 LRC（ELRC）歌词：逐字时间、v1/v2 对唱、背景人声与翻译；不再把 TTML 原始 XML 当作歌词显示"
    PluginOpenSourceUrl = "https://github.com/Kifranei/spw-duet-lyrics"
    PluginHasConfig = true
    // No permission needed: lyrics are read from the song file / sidecar files only.
    PluginPermissions = emptyList<PluginPermission>()
}

tasks.named<Zip>("plugin") {
    archiveFileName.set("spw-duet-lyrics-${project.version}.spmod")
    from("LICENSE", "README.md", "THIRD_PARTY_NOTICES.md")
    into("licenses") { from("licenses") }
}
