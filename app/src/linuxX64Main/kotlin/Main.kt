package io.sakethpathike.kapture

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(title = "KaptureGUI", onCloseRequest = ::exitApplication) {
    }
}