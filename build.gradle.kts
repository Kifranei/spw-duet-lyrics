import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
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

    // Compose: provided by the host at runtime (Workshop API 0.1.0-dev23 pins Compose 1.12.1 /
    // Kotlin 2.4.20). Only used for the per-line alignment hook.
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.runtime)

    // Bundled into lib/ of the .spmod (embedded-tag lyrics reader, LGPL-2.1).
    implementation(libs.jaudiotagger)
    // Bundled: bytecode reader that locates SPW's lyric line call site (BSD-3-Clause).
    implementation(libs.asm)

    testImplementation(libs.compose.desktop.linux)
    testImplementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation(libs.spw.workshop.api) { isTransitive = false }
    testImplementation(libs.pf4j)
}

tasks.test {
    useJUnitPlatform()
    // Optional: real SPW jars (not in the repo) for the hook-target verification test.
    systemProperty("spw.jars", providers.gradleProperty("spwJars").orElse("/workspace/spw-1.19.0-jars").get())
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
    PluginDescription = "解析 TTML 与增强 LRC（ELRC）歌词：逐字时间、v1/v2 对唱、背景人声与翻译；不再把 TTML 原始 XML 当作歌词显示；对唱歌词在 SPW 自带歌词界面中逐行对齐（v1 左 / v2 右，需类转换权限）"
    PluginOpenSourceUrl = "https://github.com/Kifranei/spw-duet-lyrics"
    PluginHasConfig = true
    // CLASS_TRANSFORM: per-line alignment in SPW's own lyric view (optional; without it the
    // plugin still converts lyrics and SPW's global "歌词对齐" applies to every line).
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM)
}

tasks.named<Zip>("plugin") {
    archiveFileName.set("spw-duet-lyrics-${project.version}.spmod")
    from("LICENSE", "README.md", "THIRD_PARTY_NOTICES.md")
    into("licenses") { from("licenses") }
}
