package io.github.sakethpathike.kapture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val PlatformIODispatcher: CoroutineDispatcher = Dispatchers.Default