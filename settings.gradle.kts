pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*") } }
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.Moriafly.*") } }
    }
}

rootProject.name = "spw-duet-lyrics"
