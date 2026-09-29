@file:OptIn(ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish") version "0.31.0"
}

group = "io.github.sakethpathike"
version = "1.3.0"

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    jvm()

    android {
        namespace = "io.github.sakethpathike"
        compileSdk = 37
        minSdk = 21
        optimization {
            consumerKeepRules.publish = true
            val proguardFile = File(projectDir, "proguard-rules.pro")
            consumerKeepRules.files.add(proguardFile)
        }
    }

    js {
        browser()
    }

    wasmJs {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ksoup.network)
                implementation(libs.tempfolder.sync)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        val appleMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        val linuxMain by getting {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }

        val mingwMain by getting {
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), "kapture", version.toString())

    pom {
        name = "kapture"
        description = "Kotlin Multiplatform library for saving webpages as standalone HTML files with embedded assets."
        inceptionYear = "2026"
        url = "https://github.com/sakethpathike/kapture"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "sakethpathike"
                name = "Saketh Pathike"
                url = "https://github.com/sakethpathike/"
            }
        }
        scm {
            url = "https://github.com/sakethpathike/kapture/"
            connection = "scm:git:git://github.com/sakethpathike/kapture.git"
            developerConnection = "scm:git:ssh://git@github.com/sakethpathike/kapture.git"
        }
    }
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
                "org.jetbrains.kotlin:kotlin-stdlib-wasm-js:2.4.20",
                "org.jetbrains.kotlin:kotlin-stdlib-js:2.4.20",
                "org.jetbrains.kotlin:kotlin-stdlib-common:2.4.20"
            )
        }
    }
}