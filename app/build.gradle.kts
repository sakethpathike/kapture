plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha05"
}

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "io.sakethpathike.kapture.main"

            linkerOpts(
                "-L/usr/lib64", "-L/usr/lib", "-L/lib64", "-L/lib"
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui:1.12.0-rc01")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0-rc01")
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
        }

        desktopNativeMain.dependencies {
            implementation("dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha05")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.12.10-alpha05")
            implementation("dev.brahmkshatriya.skiko:skiko:0.151.3")
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha05")
        }
    }
}