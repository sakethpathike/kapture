package io.sakethpathike.kapture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakethpathike.kapture.Kapture
import io.github.sakethpathike.kapture.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun GUI() {

    var linksText by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(Options()) }
    var concurrency by remember { mutableStateOf(5) }
    var captureFolderPath by remember { mutableStateOf("") }
    val lazyColumnState = rememberLazyListState()
    LaunchedEffect(archiving) {
        if (archiving) {
            snapshotFlow { derivedStateOf { lazyColumnState.canScrollForward }.value }.collect {
                lazyColumnState.animateScrollToItem(lazyColumnState.layoutInfo.totalItemsCount - 1)
            }
        }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface {
            LazyColumn(
                state = lazyColumnState,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 15.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = linksText,
                        onValueChange = { linksText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Links") },
                        supportingText = { Text("Enter URLs separated by new lines") },
                        minLines = 4
                    )
                }

                item {
                    OutlinedTextField(
                        value = captureFolderPath,
                        onValueChange = { captureFolderPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Capture Folder Path") },
                        supportingText = { Text("Absolute or relative path to save files") },
                        isError = captureFolderPath.isEmpty(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = concurrency.toString(),
                        onValueChange = {
                            concurrency = it.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Concurrency") },
                        supportingText = { Text("Number of pages to archive simultaneously") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Selective Asset Stripping",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 16.sp,
                        )
                        Text(
                            text = "Choose which components to embed. Unchecking items reduces file sizes and local storage footprint but may alter page rendering.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AssetStripOption(
                            label = "Include Images",
                            checked = options.includeImages,
                            onCheckedChange = { options = options.copy(includeImages = it) },
                        )
                        AssetStripOption(
                            label = "Include CSS Stylesheets",
                            checked = options.includeCss,
                            onCheckedChange = { options = options.copy(includeCss = it) },
                        )
                        AssetStripOption(
                            label = "Include Audio Elements",
                            checked = options.includeAudio,
                            onCheckedChange = { options = options.copy(includeAudio = it) },
                        )
                        AssetStripOption(
                            label = "Include Video Elements",
                            checked = options.includeVideo,
                            onCheckedChange = { options = options.copy(includeVideo = it) },
                        )
                        AssetStripOption(
                            label = "Execute JavaScript",
                            checked = options.includeJs,
                            onCheckedChange = { options = options.copy(includeJs = it) },
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = options.userAgent,
                        onValueChange = { options = options.copy(userAgent = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("User Agent") },
                        minLines = 2,
                        maxLines = 3
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = options.timeoutMillis.toString(),
                            onValueChange = {
                                options =
                                    options.copy(timeoutMillis = it.toLongOrNull()?.coerceAtLeast(1000L) ?: 30000L)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Timeout (ms)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        val base64Value = options.base64StreamSize
                        val isBase64Invalid = base64Value % 3 != 0

                        OutlinedTextField(
                            value = base64Value.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { parsed ->
                                    options = options.copy(base64StreamSize = parsed)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Base64 Stream Size") },
                            singleLine = true,
                            isError = isBase64Invalid,
                            supportingText = {
                                if (isBase64Invalid) Text("Must be a multiple of 3")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }

                if (captureFolderPath.isEmpty()) return@LazyColumn

                item {
                    if (!archiving) {
                        Button(
                            onClick = {
                                archive(
                                    urlsString = linksText,
                                    concurrency = concurrency,
                                    captureFolderPath = captureFolderPath,
                                    options = options
                                )
                            }, modifier = Modifier.fillMaxWidth().pointerHoverIcon(icon = PointerIcon.Hand)
                        ) {
                            Text("Archive the pages")
                        }
                    } else {
                        Text(
                            text = "Archiving...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = { cancelArchiveOp() },
                            modifier = Modifier.fillMaxWidth().pointerHoverIcon(icon = PointerIcon.Hand),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                if (archiveFileMap.isNotEmpty()) {
                    item {
                        Text(text = "URL <-> HTML File (Map)")
                    }

                    items(archiveFileMap) { (url, fileName) ->
                        SelectionContainer {
                            Text(text = "$url <-> $fileName")
                        }
                    }
                }
            }
        }
    }
}


private var archiveJob: Job? = null
private val archiveScope = CoroutineScope(Dispatchers.Default)
private val archiveFileMap = mutableStateListOf<Pair<String, String>>()
private var archiving by mutableStateOf(false)
private fun cancelArchiveOp() {
    archiving = false
    archiveFileMap.clear()
    archiveJob?.cancel()
}

private fun archive(urlsString: String, concurrency: Int, captureFolderPath: String, options: Options) {
    cancelArchiveOp()
    if (urlsString.isBlank()) return
    archiving = true
    archiveJob = archiveScope.launch {
        Kapture.init(options)
        val urls = urlsString.split("\n")

        urls.asFlow().flatMapMerge(concurrency) { url ->
            flow {
                try {
                    val url = url.trim()
                    val safeFolderPath = captureFolderPath.trimEnd('/', '\\')

                    @OptIn(ExperimentalUuidApi::class) val filePath =
                        "$safeFolderPath/${Uuid.random().toHexString()}.html"
                    Kapture.archive(
                        url = url, destinationFilePath = filePath
                    )
                    emit(Result.success(Pair(url, filePath)))
                } catch (e: Exception) {
                    emit(Result.failure(Exception(message = "f: $url:\n${e.message}")))
                }
            }
        }.collect { result ->
            result.onSuccess {
                archiveFileMap.add(it)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }
    archiveJob?.invokeOnCompletion {
        archiving = false
    }
}

@Composable
private fun AssetStripOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(
            onClick = {
                onCheckedChange(!checked)
            },
            indication = null,
            interactionSource = null,
        ).pointerHoverIcon(icon = PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}