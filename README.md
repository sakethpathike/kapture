kapture is a Kotlin Multiplatform implementation of [Y2Z/monolith](https://github.com/Y2Z/monolith) for saving webpages
as standalone HTML files.

kapture uses temp files during archiving instead of holding everything in memory and streams Base64 instead of encoding
everything at once. Processing stays linear relative to the input.

### Library

kapture is available on Maven Central and supports `android`, `jvm`, `js`, `wasmJs`, `iosX64`, `iosArm64`,
`iosSimulatorArm64`, `macosArm64`, `linuxX64`, `linuxArm64`, and `mingwX64`.

In `build.gradle.kts`:

```
implementation("io.github.sakethpathike:kapture:1.2.0")
```

Usage:

```
...
Kapture.init(options) // kapture must be initialized before archiving
...
Kapture.archive(
    url = url, 
    destinationFilePath = filePath
)
```

On Android, SAF doesn't grant file ownership; you need a proper POSIX path. Additionally, you must include `atomicfu` as
a dependency as kapture uses [tempfolder-kmp](https://github.com/illarionov/tempfolder-kmp), which requires `atomicfu`
to work on Android:

```
implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
```

### GUI

The `app` module contains desktop GUI built on kapture. Check
the [latest release](https://github.com/sakethpathike/kapture/releases) for the Kotlin/Native (
via [compose-native](https://github.com/brahmkshatriya/compose-native)) builds for Linux and Windows.

Binaries with a `-upx` suffix are smaller, but take a bit longer to launch.

### License

Both the library and GUI are licensed under Apache 2.0.