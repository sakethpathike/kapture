plugins {
    alias(libs.plugins.multiplatform)
}

group = "io.github.sakethpathike"
version = "0.1.2"

kotlin {
    jvm()
    js { browser() }
    wasmJs { browser() }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.5.2")
            implementation("io.ktor:ktor-client-cio:3.5.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("com.fleeksoft.ksoup:ksoup-network:0.2.6")
        }
    }
}