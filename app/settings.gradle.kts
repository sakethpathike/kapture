pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

includeBuild("..") {
    dependencySubstitution {
        substitute(module("io.sakethpathike.kapture:core")).using(project(":core"))
        substitute(module("io.sakethpathike.kapture:shared")).using(project(":shared"))
    }
}