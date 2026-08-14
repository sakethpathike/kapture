plugins {
    alias(libs.plugins.multiplatform)
}

group = "io.github.sakethpathike"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    jvm()

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
        val commonMain by getting
    }
}

tasks.register("prepareKotlinBuildScriptModel") {}
