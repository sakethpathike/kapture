import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha05"
}

private val sdl3Version = "3.4.10"
private val sdl3MingwLibDir = layout.buildDirectory.dir("sdl3-mingw/SDL3-$sdl3Version/x86_64-w64-mingw32/lib")

private val downloadSdl3Mingw by tasks.registering {
    val archive = layout.buildDirectory.file("sdl3-mingw.tar.gz")
    outputs.dir(sdl3MingwLibDir)
    doLast {
        URI("https://github.com/libsdl-org/SDL/releases/download/release-$sdl3Version/SDL3-devel-$sdl3Version-mingw.tar.gz").toURL()
            .openStream().use { input ->
                archive.get().asFile.outputStream().use(input::copyTo)
            }
        copy {
            from(tarTree(resources.gzip(archive.get().asFile)))
            into(layout.buildDirectory.dir("sdl3-mingw"))
        }
    }
}

val downloadWindowsIcuData by tasks.registering {
    val outFile = layout.buildDirectory.file("icudtl.dat")
    outputs.file(outFile)
    doLast {
        val file = outFile.get().asFile
        file.parentFile.mkdirs()
        val url = "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/0.144.4/skiko-awt-runtime-windows-x64-0.144.4.jar"

        URI(url).toURL().openStream().use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "icudtl.dat") {
                        file.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
}

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "io.sakethpathike.kapture.main"
        }
    }

    linuxX64 {
        binaries.all {
            linkerOpts("-L/usr/lib/x86_64-linux-gnu")
        }
    }

    linuxArm64 {
        binaries.all {
            linkerOpts("-L/usr/lib/aarch64-linux-gnu", "-lEGL")
        }
    }

    mingwX64 {
        binaries.all {
            linkTaskProvider.configure { dependsOn(downloadSdl3Mingw) }
            linkTaskProvider.configure { dependsOn(downloadWindowsIcuData) }
            linkerOpts("-L${sdl3MingwLibDir.get().asFile.absolutePath}")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui:1.12.0-rc01")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0-rc01")
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            implementation("io.sakethpathike.kapture:shared")
            implementation("io.sakethpathike.kapture:core")
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