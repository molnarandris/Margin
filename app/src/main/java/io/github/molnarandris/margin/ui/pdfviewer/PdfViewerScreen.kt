package io.github.molnarandris.margin.ui.pdfviewer

import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.InputChip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.molnarandris.margin.data.PreferencesRepository
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class JumpOrigin(val pageIndex: Int, val scrollOffset: Int, val highlightX: Float, val highlightY: Float)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun PdfViewerScreen(
    dirUri: Uri? = null,
    docId: String? = null,
    externalUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPdf: (dirUri: Uri, docId: String) -> Unit = { _, _ -> },
    viewModel: PdfViewerViewModel = viewModel()
) {
    LaunchedEffect(dirUri, docId, externalUri) {
        if (externalUri != null) viewModel.loadPdfFromUri(externalUri)
        else if (dirUri != null && docId != null) viewModel.loadPdf(dirUri, docId)
    }

    val uiState by viewModel.uiState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val completedInkStrokes by viewModel.completedInkStrokes.collectAsState()
    val inkClipboard by viewModel.inkClipboard.collectAsState()
    val penColor by viewModel.penColor.collectAsState()
    val penThickness by viewModel.penThickness.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val previousDocParams by viewModel.previousDocParams.collectAsState()
    val pendingScrollToPage by viewModel.pendingScrollToPage.collectAsState()
    val outline by viewModel.outline.collectAsState()
    var isOutlineVisible by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<Int>()) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val pdfTitle    by viewModel.displayTitle.collectAsState()
    val pdfAuthors  by viewModel.displayAuthors.collectAsState()
    val pdfProjects by viewModel.displayProjects.collectAsState()
    var isEditDialogVisible by remember { mutableStateOf(false) }
    var titleEditText  by remember { mutableStateOf("") }
    var authorChips    by remember { mutableStateOf<List<String>>(emptyList()) }
    var newAuthorText  by remember { mutableStateOf("") }
    var projectChips   by remember { mutableStateOf<List<String>>(emptyList()) }
    var newProjectText by remember { mutableStateOf("") }
    var noteDialogTarget by remember { mutableStateOf<PdfHighlight?>(null) }
    var noteDialogText   by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf(viewModel.firstVisiblePageIndex) }
    val tocBreadcrumb = remember(outline, currentPage) {
        if (outline.isEmpty()) return@remember emptyList<String>()
        val activeByLevel = sortedMapOf<Int, String>()
        for (item in outline) {
            if (item.pageIndex > currentPage) break
            activeByLevel[item.level] = item.title
            activeByLevel.keys.filter { it > item.level }.forEach { activeByLevel.remove(it) }
        }
        activeByLevel.values.toList()
    }

    var topBarVisible by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val outerScope = rememberCoroutineScope()
    val view = LocalView.current
    val gestureZonePx = remember { 32 * view.resources.displayMetrics.density }

    val context = LocalContext.current

    // Image annotation state
    var imageAnnotationSelection by remember { mutableStateOf<ImageAnnotationSelection?>(null) }
    val imageAnnotationsByPage by viewModel.imageAnnotations.collectAsState()
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = cameraImageUri ?: return@rememberLauncherForActivityResult
            outerScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, opts)
                        }
                        val maxDim = 2048
                        var sample = 1
                        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim) sample *= 2
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
                        }
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@launch
                val annot = viewModel.insertImageAnnotation(bitmap, currentPage)
                imageAnnotationSelection = ImageAnnotationSelection(
                    pageIndex = currentPage,
                    originalAnnotation = annot,
                    annotation = annot,
                )
            }
        }
    }

    val keepScreenOn by PreferencesRepository(context).keepScreenOn.collectAsState(initial = false)
    val isExternalPdf by viewModel.isExternalPdf.collectAsState()
    val isImported by viewModel.isImported.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()

    LaunchedEffect(importMessage) {
        val msg = importMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearImportMessage()
    }
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(searchQuery) {
        viewModel.search(searchQuery)
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(pendingScrollToPage, uiState) {
        val target = pendingScrollToPage
        if (target >= 0 && uiState is PdfViewerUiState.Ready) {
            currentPage = target.coerceAtMost((uiState as PdfViewerUiState.Ready).pages.size - 1)
            viewModel.clearPendingScroll()
        }
    }

    if (isEditDialogVisible) {
        AlertDialog(
            onDismissRequest = { isEditDialogVisible = false },
            title = { Text("Edit Document Info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = titleEditText,
                        onValueChange = { titleEditText = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (authorChips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            authorChips.forEach { author ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(author) },
                                    trailingIcon = {
                                        IconButton(onClick = { authorChips = authorChips - author }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newAuthorText,
                            onValueChange = { newAuthorText = it },
                            label = { Text("Add author") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val trimmed = newAuthorText.trim()
                            if (trimmed.isNotEmpty() && trimmed !in authorChips) {
                                authorChips = authorChips + trimmed
                            }
                            newAuthorText = ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add author")
                        }
                    }
                    if (projectChips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            projectChips.forEach { project ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(project) },
                                    trailingIcon = {
                                        IconButton(onClick = { projectChips = projectChips - project }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newProjectText,
                            onValueChange = { newProjectText = it },
                            label = { Text("Add project") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val trimmed = newProjectText.trim()
                            if (trimmed.isNotEmpty() && trimmed !in projectChips) {
                                projectChips = projectChips + trimmed
                            }
                            newProjectText = ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add project")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMetadata(titleEditText, authorChips, projectChips)
                    isEditDialogVisible = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { isEditDialogVisible = false }) { Text("Cancel") }
            }
        )
    }

    val noteTarget = noteDialogTarget
    if (noteTarget != null) {
        AlertDialog(
            onDismissRequest = { noteDialogTarget = null },
            title = { Text("Annotation") },
            text = {
                OutlinedTextField(
                    value = noteDialogText,
                    onValueChange = { noteDialogText = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setHighlightNote(noteTarget, noteDialogText)
                    noteDialogTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (isOutlineVisible) {
        val visibleItems = remember(outline, collapsed) {
            buildList {
                var collapseAtLevel = -1
                outline.forEachIndexed { i, item ->
                    if (collapseAtLevel >= 0 && item.level > collapseAtLevel) return@forEachIndexed
                    collapseAtLevel = -1
                    add(i to item)
                    if (item.hasChildren && i in collapsed) collapseAtLevel = item.level
                }
            }
        }
        Dialog(
            onDismissRequest = { isOutlineVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogWindowProvider = LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider
            SideEffect {
                dialogWindowProvider?.window?.setDimAmount(0.15f)
            }
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceAtLeast(240.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { isOutlineVisible = false }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.65f)
                        .heightIn(max = maxHeight)
                        .clickable(enabled = false, onClick = {}),
                    shape = RoundedCornerShape(topEnd = 16.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        items(visibleItems, key = { it.first }) { (index, item) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .clickable {
                                        viewModel.recordPageJump(currentPage, item.pageIndex)
                                        currentPage = item.pageIndex
                                        isOutlineVisible = false
                                    }
                                    .padding(
                                        start = (16 + item.level * 24).dp,
                                        end = 8.dp, top = 12.dp, bottom = 12.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.hasChildren) {
                                    Icon(
                                        imageVector = if (index in collapsed)
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                                        else
                                            Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (index in collapsed) "Expand" else "Collapse",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                collapsed = if (index in collapsed)
                                                    collapsed - index else collapsed + index
                                            }
                                    )
                                }
                                DotLeaderOutlineText(
                                    title = item.title,
                                    pageNum = item.pageIndex + 1,
                                    fontWeight = if (item.level == 0) FontWeight.Bold else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFFE0E0E0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (isExternalPdf && !isImported) {
                FloatingActionButton(onClick = { viewModel.importCurrentPdf() }) {
                    Icon(Icons.Default.Download, contentDescription = "Import to library")
                }
            }
        },
        bottomBar = {
            if (topBarVisible) {
                PdfViewerBottomBar(
                    breadcrumb = tocBreadcrumb,
                    currentPage = currentPage,
                    totalPages = (uiState as? PdfViewerUiState.Ready)?.pages?.size ?: 0,
                    outline = outline,
                    onOpenOutline = { isOutlineVisible = true }
                )
            }
        },
        topBar = { if (topBarVisible) {
            PdfViewerTopBar(
                isSearchVisible = isSearchVisible,
                searchQuery = searchQuery,
                searchFocusRequester = searchFocusRequester,
                searchState = searchState,
                pdfTitle = pdfTitle,
                pdfAuthors = pdfAuthors,
                previousDocParams = previousDocParams,
                totalPages = (uiState as? PdfViewerUiState.Ready)?.pages?.size ?: 0,
                currentPage = currentPage,
                canUndo = canUndo,
                canRedo = canRedo,
                penThickness = penThickness,
                penColor = penColor,
                onBack = onBack,
                onOpenPdf = onOpenPdf,
                onSearchQueryChange = { searchQuery = it },
                onPrevMatch = { viewModel.prevMatch() },
                onNextMatch = { viewModel.nextMatch() },
                onCloseSearch = {
                    isSearchVisible = false
                    searchQuery = ""
                    viewModel.clearSearch()
                },
                onOpenSearch = { isSearchVisible = true },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onPenThicknessChange = { viewModel.setPenThickness(it) },
                onPenColorChange = { viewModel.setPenColor(it) },
                onInsertPhoto = {
                    val photoFile = File(context.cacheDir, "photos/camera_${System.currentTimeMillis()}.jpg")
                        .also { it.parentFile?.mkdirs() }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                    cameraImageUri = uri
                    takePicture.launch(uri)
                },
                onEditMetadata = {
                    titleEditText  = pdfTitle
                    authorChips    = pdfAuthors
                    newAuthorText  = ""
                    projectChips   = pdfProjects
                    newProjectText = ""
                    isEditDialogVisible = true
                }
            )
        } },
    ) { innerPadding ->
        when (val state = uiState) {
            is PdfViewerUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is PdfViewerUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Error: ${state.message}")
                }
            }

            is PdfViewerUiState.CorruptedWithBackup -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The PDF file is corrupted.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.restoreFromBackup(state.backupFile, state.uri) }) {
                            Text("Restore backup")
                        }
                    }
                }
            }

            is PdfViewerUiState.Ready -> {
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var destinationHighlight by remember { mutableStateOf<DestinationHighlight?>(null) }
                var jumpOrigin by remember { mutableStateOf<JumpOrigin?>(null) }
                var textSelection by remember { mutableStateOf<PageTextSelection?>(null) }
                var inkStrokeSelection by remember { mutableStateOf<InkStrokeSelection?>(null) }
                // Used during handle dragging so we don't update textSelection on every event
                var dragChars by remember { mutableStateOf<List<TextChar>?>(null) }
                var isDraggingHandle by remember { mutableStateOf(false) }
                var popupHeightPx by remember { mutableStateOf(0) }
                var viewportHeightPx by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                val pagesRef = rememberUpdatedState(state.pages)
                val coroutineScope = rememberCoroutineScope()
                var showLastPageToast by remember { mutableStateOf(false) }
                var toastJob by remember { mutableStateOf<Job?>(null) }
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current

                BackHandler(enabled = jumpOrigin != null) {
                    val origin = jumpOrigin!!
                    jumpOrigin = null
                    scale = 1f; offsetX = 0f; offsetY = 0f
                    currentPage = origin.pageIndex
                    coroutineScope.launch {
                        destinationHighlight = DestinationHighlight(origin.pageIndex, origin.highlightX, origin.highlightY)
                        delay(500)
                        destinationHighlight = null
                    }
                }

                LaunchedEffect(Unit) {
                    snapshotFlow { scale to currentPage }
                        .debounce(300)
                        .collect { (currentScale, page) ->
                            viewModel.updateRenderScale(currentScale, listOf(page))
                        }
                }

                // Clear selection and update visible page when page changes (zoom preserved for swipe)
                LaunchedEffect(currentPage) {
                    textSelection = null
                    viewModel.onVisiblePageChanged(currentPage)
                }

                val density = LocalDensity.current
                val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                val marginPx = with(density) { 8.dp.toPx() }
                val contentWidthDp = with(density) { (screenWidthPx * scale).toDp() }

                // Scroll to current search match (both vertical and horizontal)
                LaunchedEffect(searchState.currentIndex) {
                    val match = searchState.matches.getOrNull(searchState.currentIndex) ?: return@LaunchedEffect
                    val page = state.pages.getOrNull(match.pageIndex) ?: return@LaunchedEffect
                    val displayedPageWidth = screenWidthPx * scale - 2 * marginPx

                    val matchCenterX = (match.wordBounds.minOf { it.left } + match.wordBounds.maxOf { it.right }) / 2f
                    val destXPx = matchCenterX / page.nativeWidth * displayedPageWidth
                    val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                    offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f

                    scale = 1f; offsetX = 0f; offsetY = 0f
                    currentPage = match.pageIndex
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .let { if (topBarVisible) it.padding(innerPadding) else it }
                        .background(Color(0xFFE0E0E0))
                        .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                        .pointerInput(screenWidthPx, marginPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startedFromLeftEdge = down.position.x < gestureZonePx
                                var wasMultiTouch = false
                                var everMultiTouch = false
                                var singleTouchAxis = 0
                                var accumDx = 0f
                                var accumDy = 0f
                                var totalDx = 0f
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pg = pagesRef.value.getOrNull(currentPage)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        if (wasMultiTouch) {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val touches = event.changes.filter { it.pressed }
                                            val span = if (touches.size >= 2)
                                                (touches[0].position - touches[1].position).getDistance()
                                            else Float.MAX_VALUE
                                            val spanChangePx = span * kotlin.math.abs(zoomChange - 1f)
                                            val closeFingers = span < 250f
                                            if (!closeFingers || spanChangePx > 8f) {
                                                val centroid = event.calculateCentroid(useCurrent = false)
                                                val minScale = if (pg != null && viewportHeightPx > 0f)
                                                    (viewportHeightPx * pg.nativeWidth / (screenWidthPx * pg.nativeHeight)).coerceAtMost(1f)
                                                else 1f
                                                val newScale = (scale * zoomChange).coerceIn(minScale, 5f)
                                                val actualZoom = newScale / scale
                                                val contentLeft = screenWidthPx * (1f - scale) / 2f + offsetX
                                                val newContentLeft = centroid.x * (1f - actualZoom) +
                                                        contentLeft * actualZoom + panChange.x
                                                val rawOffsetX = newContentLeft - screenWidthPx * (1f - newScale) / 2f
                                                scale = newScale
                                                offsetX = if (newScale > 1f) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (newScale - 1f) / 2f
                                                    rawOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                                } else 0f
                                                if (pg != null) {
                                                    val maxOffsetY = ((screenWidthPx * newScale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth -
                                                        viewportHeightPx).coerceAtLeast(0f) / 2f
                                                    offsetY = (offsetY - panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                }
                                            } else {
                                                offsetX = if (scale > 1f) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                    (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                                } else 0f
                                                if (pg != null) {
                                                    val maxOffsetY = ((screenWidthPx * scale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth -
                                                        viewportHeightPx).coerceAtLeast(0f) / 2f
                                                    offsetY = (offsetY - panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                }
                                            }
                                        }
                                        wasMultiTouch = true
                                        everMultiTouch = true
                                        singleTouchAxis = 0
                                        event.changes.forEach { it.consume() }
                                    } else if (!everMultiTouch && !isDraggingHandle) {
                                        val change = event.changes.firstOrNull { it.type != PointerType.Stylus }
                                        if (change != null) {
                                            val dx = change.position.x - change.previousPosition.x
                                            val dy = change.position.y - change.previousPosition.y
                                            if (singleTouchAxis == 0) {
                                                accumDx += dx
                                                accumDy += dy
                                                if (accumDx * accumDx + accumDy * accumDy > 64f)
                                                    singleTouchAxis = if (kotlin.math.abs(accumDx) > kotlin.math.abs(accumDy)) 1 else -1
                                            }
                                            if (scale > 1f) {
                                                if (singleTouchAxis == 1) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                    offsetX = (offsetX + dx).coerceIn(-maxOffsetX, maxOffsetX)
                                                    change.consume()
                                                }
                                                if (singleTouchAxis == -1 && pg != null) {
                                                    val pageH = (screenWidthPx * scale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth
                                                    val maxOffsetY = ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                                    offsetY = (offsetY - dy).coerceIn(-maxOffsetY, maxOffsetY)
                                                    change.consume()
                                                }
                                            } else if (singleTouchAxis == -1 && pg != null) {
                                                val pageH = (screenWidthPx * scale - 2 * marginPx) *
                                                    pg.nativeHeight / pg.nativeWidth
                                                val maxOffsetY = ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                                if (maxOffsetY > 0f) {
                                                    offsetY = (offsetY - dy).coerceIn(-maxOffsetY, maxOffsetY)
                                                    change.consume()
                                                }
                                            }
                                            if (singleTouchAxis == 1 && scale <= 1f && !startedFromLeftEdge) {
                                                totalDx += dx
                                                change.consume()
                                            }
                                        }
                                    } else {
                                        wasMultiTouch = false
                                    }
                                } while (event.changes.any { it.pressed })
                                // After gesture ends: if horizontal swipe at scale<=1, change page instantly
                                if (singleTouchAxis == 1 && scale <= 1f && !startedFromLeftEdge) {
                                    val threshold = with(density) { 60.dp.toPx() }
                                    fun maxOffsetYForPage(pg: PdfPage): Float {
                                        val pageH = (screenWidthPx * scale - 2 * marginPx) * pg.nativeHeight / pg.nativeWidth
                                        return ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                    }
                                    if (totalDx < -threshold) {
                                        if (currentPage < pagesRef.value.size - 1) {
                                            currentPage++
                                            val pg = pagesRef.value.getOrNull(currentPage)
                                            offsetY = if (pg != null) -maxOffsetYForPage(pg) else 0f
                                            toastJob?.cancel()
                                            showLastPageToast = false
                                        } else {
                                            if (showLastPageToast) {
                                                toastJob?.cancel()
                                                showLastPageToast = false
                                                viewModel.insertPage(currentPage + 1)
                                            } else {
                                                showLastPageToast = true
                                                toastJob?.cancel()
                                                toastJob = coroutineScope.launch {
                                                    delay(600)
                                                    showLastPageToast = false
                                                }
                                            }
                                        }
                                    } else if (totalDx > threshold && currentPage > 0) {
                                        currentPage--
                                        val pg = pagesRef.value.getOrNull(currentPage)
                                        offsetY = if (pg != null) maxOffsetYForPage(pg) else 0f
                                    }
                                }
                            }
                        }
                ) {
                    val page = state.pages.getOrNull(currentPage) ?: return@Box
                    val pageDisplayHeightDp = with(density) {
                        (screenWidthPx * scale * page.nativeHeight / page.nativeWidth).toDp()
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RectangleShape,
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .requiredWidth(contentWidthDp)
                                .requiredHeight(pageDisplayHeightDp)
                                .offset { IntOffset(offsetX.roundToInt(), -offsetY.roundToInt()) }
                        ) {
                            PageContent(
                                page = page,
                                index = currentPage,
                                completedInkStrokes = completedInkStrokes,
                                penColor = penColor,
                                penThickness = penThickness,
                                searchState = searchState,
                                textSelection = textSelection,
                                dragChars = dragChars,
                                destinationHighlight = destinationHighlight,
                                popupHeightPx = popupHeightPx,
                                onBarsVisibleToggle = { topBarVisible = !topBarVisible },
                                onTextSelectionChanged = { textSelection = it },
                                onDragCharsChanged = { dragChars = it },
                                onIsDraggingHandleChanged = { isDraggingHandle = it },
                                onPopupHeightPxChanged = { popupHeightPx = it },
                                onAddHighlight = { viewModel.addHighlight(it.pageIndex, it.selectedChars) },
                                onDeleteHighlight = { viewModel.deleteHighlight(it) },
                                onAnnotateHighlight = { h ->
                                    noteDialogTarget = h
                                    noteDialogText = h.note ?: ""
                                },
                                onTapAnnotatedHighlight = { h ->
                                    noteDialogTarget = h
                                    noteDialogText = h.note ?: ""
                                },
                                onCopy = { clipboardManager.setText(AnnotatedString(it)) },
                                onEraseInkStrokes = { pageIdx, ids -> viewModel.eraseInkStrokes(pageIdx, ids) },
                                onAddInkAnnotation = { pageIdx, stroke, w, h -> viewModel.addInkAnnotation(pageIdx, stroke, w, h) },
                                inkStrokeSelection = inkStrokeSelection,
                                onStrokeSelectionChanged = { inkStrokeSelection = it },
                                onSelectionDragDelta = { d ->
                                    inkStrokeSelection = inkStrokeSelection?.let { s -> s.copy(dragOffsetPx = s.dragOffsetPx + d) }
                                },
                                onCommitSelectionMove = { pageIdx, origStrokes, totalDeltaPx, pgSize ->
                                    val dx = totalDeltaPx.x / pgSize.width
                                    val dy = totalDeltaPx.y / pgSize.height
                                    val movedStrokes = origStrokes.map { s ->
                                        s.copy(points = s.points.map { Offset(it.x + dx, it.y + dy) })
                                    }
                                    viewModel.moveInkStrokes(pageIdx, origStrokes, movedStrokes)
                                    inkStrokeSelection = inkStrokeSelection?.let { sel ->
                                        sel.copy(
                                            strokes = movedStrokes,
                                            bounds = sel.bounds.translate(totalDeltaPx),
                                            dragOffsetPx = Offset.Zero
                                        )
                                    }
                                },
                                onLinkTap = { target, linkBoundsFirstRect ->
                                    when (target) {
                                        is LinkTarget.Url -> context.startActivity(Intent(Intent.ACTION_VIEW, target.uri))
                                        is LinkTarget.Goto -> coroutineScope.launch {
                                            viewModel.recordPageJump(currentPage, target.pageNumber)
                                            jumpOrigin = JumpOrigin(
                                                pageIndex = currentPage,
                                                scrollOffset = 0,
                                                highlightX = linkBoundsFirstRect.centerX(),
                                                highlightY = linkBoundsFirstRect.centerY()
                                            )
                                            val displayedPageWidth = screenWidthPx * scale - 2 * marginPx
                                            val destXPx = target.x / page.nativeWidth * displayedPageWidth
                                            val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                                            val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                            offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                            currentPage = target.pageNumber
                                            if (!target.x.isNaN() && !target.y.isNaN()) {
                                                destinationHighlight = DestinationHighlight(target.pageNumber, target.x, target.y)
                                                delay(700)
                                                destinationHighlight = null
                                            }
                                        }
                                    }
                                },
                                onDeletePage = {
                                    val deletedPage = currentPage
                                    viewModel.deletePage(deletedPage)
                                    if (deletedPage >= state.pages.size - 1) {
                                        currentPage = (state.pages.size - 2).coerceAtLeast(0)
                                    }
                                    outerScope.launch {
                                        val dismissJob = launch { delay(1500); snackbarHostState.currentSnackbarData?.dismiss() }
                                        val result = snackbarHostState.showSnackbar("Page deleted", actionLabel = "Undo")
                                        dismissJob.cancel()
                                        if (result == SnackbarResult.ActionPerformed) viewModel.cancelDelete()
                                    }
                                },
                                onInsertPageBefore = { viewModel.insertPage(currentPage) },
                                onInsertPageAfter = { viewModel.insertPage(currentPage + 1) },
                                inkClipboard = inkClipboard,
                                onCopyInkStrokes = { viewModel.copyInkStrokes(it) },
                                onPasteInkStrokes = { pageIdx, centerNorm -> viewModel.pasteInkStrokes(pageIdx, centerNorm) },
                                imageAnnotations = imageAnnotationsByPage[currentPage].orEmpty(),
                                imageAnnotationSelection = imageAnnotationSelection?.takeIf { it.pageIndex == currentPage },
                                onImageAnnotationSelectionChanged = { imageAnnotationSelection = it },
                                onImageDragDelta = { d ->
                                    imageAnnotationSelection = imageAnnotationSelection?.let { s ->
                                        s.copy(dragOffsetPx = s.dragOffsetPx + d)
                                    }
                                },
                                onCommitImageMove = { pageIdx, orig, totalDeltaPx, pgSize ->
                                    val imgW = orig.rectNorm.right - orig.rectNorm.left
                                    val imgH = orig.rectNorm.bottom - orig.rectNorm.top
                                    val dx = totalDeltaPx.x / pgSize.width
                                    val dy = totalDeltaPx.y / pgSize.height
                                    val newLeft = (orig.rectNorm.left + dx).coerceIn(0f, 1f - imgW)
                                    val newTop  = (orig.rectNorm.top  + dy).coerceIn(0f, 1f - imgH)
                                    val moved = orig.copy(rectNorm = RectF(newLeft, newTop, newLeft + imgW, newTop + imgH))
                                    viewModel.moveImageAnnotation(pageIdx, orig, moved)
                                    imageAnnotationSelection = imageAnnotationSelection?.copy(
                                        annotation = moved, dragOffsetPx = Offset.Zero, activeHandle = null
                                    )
                                },
                                onCommitImageResize = { pageIdx, orig, handle, totalDeltaPx, pgSize ->
                                    val dx = totalDeltaPx.x / pgSize.width
                                    val dy = totalDeltaPx.y / pgSize.height
                                    val minSize = 0.05f
                                    val r = orig.rectNorm
                                    val newRect = when (handle) {
                                        ResizeHandle.TOP_LEFT     -> RectF((r.left+dx).coerceAtMost(r.right-minSize), (r.top+dy).coerceAtMost(r.bottom-minSize), r.right, r.bottom)
                                        ResizeHandle.TOP_RIGHT    -> RectF(r.left, (r.top+dy).coerceAtMost(r.bottom-minSize), (r.right+dx).coerceAtLeast(r.left+minSize), r.bottom)
                                        ResizeHandle.BOTTOM_LEFT  -> RectF((r.left+dx).coerceAtMost(r.right-minSize), r.top, r.right, (r.bottom+dy).coerceAtLeast(r.top+minSize))
                                        ResizeHandle.BOTTOM_RIGHT -> RectF(r.left, r.top, (r.right+dx).coerceAtLeast(r.left+minSize), (r.bottom+dy).coerceAtLeast(r.top+minSize))
                                    }
                                    val moved = orig.copy(rectNorm = newRect)
                                    viewModel.moveImageAnnotation(pageIdx, orig, moved)
                                    imageAnnotationSelection = imageAnnotationSelection?.copy(
                                        annotation = moved, dragOffsetPx = Offset.Zero, activeHandle = null
                                    )
                                },
                                onDeleteImageAnnotation = { pageIdx, annot ->
                                    viewModel.deleteImageAnnotation(pageIdx, annot)
                                    imageAnnotationSelection = null
                                },
                            )
                        }
                        AnimatedVisibility(
                            visible = showLastPageToast,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.inverseSurface,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = "Last page. Swipe again to add a new one.",
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(
    page: PdfPage,
    index: Int,
    completedInkStrokes: Map<Int, List<InkStroke>>,
    penColor: StrokeColor,
    penThickness: StrokeThickness,
    searchState: SearchState,
    textSelection: PageTextSelection?,
    dragChars: List<TextChar>?,
    destinationHighlight: DestinationHighlight?,
    popupHeightPx: Int,
    onBarsVisibleToggle: () -> Unit,
    onTextSelectionChanged: (PageTextSelection?) -> Unit,
    onDragCharsChanged: (List<TextChar>?) -> Unit,
    onIsDraggingHandleChanged: (Boolean) -> Unit,
    onPopupHeightPxChanged: (Int) -> Unit,
    onAddHighlight: (PageTextSelection) -> Unit,
    onDeleteHighlight: (PdfHighlight) -> Unit,
    onAnnotateHighlight: (PdfHighlight) -> Unit,
    onTapAnnotatedHighlight: (PdfHighlight) -> Unit,
    onCopy: (String) -> Unit,
    onEraseInkStrokes: (Int, List<Int>) -> Unit,
    onAddInkAnnotation: (Int, List<Offset>, Int, Int) -> Int,
    onLinkTap: (LinkTarget, android.graphics.RectF) -> Unit,
    inkStrokeSelection: InkStrokeSelection?,
    onStrokeSelectionChanged: (InkStrokeSelection?) -> Unit,
    onSelectionDragDelta: (Offset) -> Unit,
    onCommitSelectionMove: (Int, List<InkStroke>, Offset, IntSize) -> Unit,
    onDeletePage: () -> Unit,
    onInsertPageBefore: () -> Unit,
    onInsertPageAfter: () -> Unit,
    inkClipboard: List<InkStroke>?,
    onCopyInkStrokes: (List<InkStroke>) -> Unit,
    onPasteInkStrokes: (Int, Offset) -> Unit,
    imageAnnotations: List<PdfImageAnnotation>,
    imageAnnotationSelection: ImageAnnotationSelection?,
    onImageAnnotationSelectionChanged: (ImageAnnotationSelection?) -> Unit,
    onImageDragDelta: (Offset) -> Unit,
    onCommitImageMove: (Int, PdfImageAnnotation, Offset, IntSize) -> Unit,
    onCommitImageResize: (Int, PdfImageAnnotation, ResizeHandle, Offset, IntSize) -> Unit,
    onDeleteImageAnnotation: (Int, PdfImageAnnotation) -> Unit,
) {
    PdfPageBase(page = page, modifier = Modifier.fillMaxSize()) {
        PdfAnnotationLayer(
            state = PdfPageState(
                page = page,
                index = index,
                inkStrokes = completedInkStrokes[index] ?: emptyList(),
                penColor = penColor,
                penThickness = penThickness,
                inkStrokeSelection = inkStrokeSelection,
                inkClipboard = inkClipboard,
                searchState = searchState,
                textSelection = textSelection,
                dragChars = dragChars,
                destinationHighlight = destinationHighlight,
                popupHeightPx = popupHeightPx,
                imageAnnotations = imageAnnotations,
                imageAnnotationSelection = imageAnnotationSelection,
            ),
            actions = PdfPageActions(
                onBarsVisibleToggle = onBarsVisibleToggle,
                onTextSelectionChanged = onTextSelectionChanged,
                onDragCharsChanged = onDragCharsChanged,
                onIsDraggingHandleChanged = onIsDraggingHandleChanged,
                onPopupHeightPxChanged = onPopupHeightPxChanged,
                onAddHighlight = onAddHighlight,
                onDeleteHighlight = onDeleteHighlight,
                onAnnotateHighlight = onAnnotateHighlight,
                onTapAnnotatedHighlight = onTapAnnotatedHighlight,
                onCopy = onCopy,
                onLinkTap = onLinkTap,
                onEraseInkStrokes = { ids -> onEraseInkStrokes(index, ids) },
                onAddInkAnnotation = { pts, w, h -> onAddInkAnnotation(index, pts, w, h) },
                onStrokeSelectionChanged = onStrokeSelectionChanged,
                onSelectionDragDelta = onSelectionDragDelta,
                onCommitSelectionMove = { strokes, delta, sz -> onCommitSelectionMove(index, strokes, delta, sz) },
                onCopyInkStrokes = onCopyInkStrokes,
                onPasteInkStrokes = { center -> onPasteInkStrokes(index, center) },
                onDeletePage = onDeletePage,
                onInsertPageBefore = onInsertPageBefore,
                onInsertPageAfter = onInsertPageAfter,
                onImageAnnotationSelectionChanged = onImageAnnotationSelectionChanged,
                onImageDragDelta = onImageDragDelta,
                onCommitImageMove = { annot, delta, sz -> onCommitImageMove(index, annot, delta, sz) },
                onCommitImageResize = { annot, handle, delta, sz -> onCommitImageResize(index, annot, handle, delta, sz) },
                onDeleteImageAnnotation = { annot -> onDeleteImageAnnotation(index, annot) },
            ),
            modifier = Modifier.matchParentSize(),
        )
    }
}



@Composable
private fun BreadcrumbText(
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelSmall
    val separator = " › "
    SubcomposeLayout(modifier) { constraints ->
        val maxWidth = constraints.maxWidth

        // Measure separator width
        val sepWidth = subcompose("sep") {
            Text(separator, style = style, maxLines = 1)
        }[0].measure(Constraints()).width
        val totalSepWidth = (items.size - 1) * sepWidth
        val available = (maxWidth - totalSepWidth).coerceAtLeast(0)

        // Measure natural (unconstrained) widths
        val naturalWidths = items.mapIndexed { i, text ->
            subcompose("nat_$i") {
                Text(text, style = style, maxLines = 1, softWrap = false)
            }[0].measure(Constraints()).width
        }

        // Minimum width: just enough for a single ellipsis character
        val ellipsisWidth = subcompose("ellipsis") {
            Text("…", style = style)
        }[0].measure(Constraints()).width

        // Reduce widths from the front until everything fits
        val allocatedWidths = naturalWidths.toIntArray()
        var excess = allocatedWidths.sum() - available
        for (i in items.indices) {
            if (excess <= 0) break
            val canReduce = (allocatedWidths[i] - ellipsisWidth).coerceAtLeast(0)
            val reduce = minOf(excess, canReduce)
            allocatedWidths[i] -= reduce
            excess -= reduce
        }

        // Final measurement with allocated widths
        val itemPlaceables = items.mapIndexed { i, text ->
            subcompose("item_$i") {
                Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            }[0].measure(Constraints(maxWidth = allocatedWidths[i].coerceAtLeast(0)))
        }
        val sepPlaceables = (0 until items.size - 1).map { i ->
            subcompose("sep_$i") {
                Text(separator, style = style, maxLines = 1)
            }[0].measure(Constraints())
        }

        val height = maxOf(
            itemPlaceables.maxOfOrNull { it.height } ?: 0,
            sepPlaceables.maxOfOrNull { it.height } ?: 0
        )
        layout(maxWidth, height) {
            var x = 0
            items.indices.forEach { i ->
                itemPlaceables[i].placeRelative(x, (height - itemPlaceables[i].height) / 2)
                x += allocatedWidths[i]
                if (i < sepPlaceables.size) {
                    sepPlaceables[i].placeRelative(x, (height - sepPlaceables[i].height) / 2)
                    x += sepWidth
                }
            }
        }
    }
}

@Composable
private fun PdfViewerBottomBar(
    breadcrumb: List<String>,
    currentPage: Int,
    totalPages: Int,
    outline: List<OutlineItem>,
    onOpenOutline: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(36.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (outline.isNotEmpty()) {
                IconButton(onClick = onOpenOutline, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of contents")
                }
            }
            BreadcrumbText(
                items = breadcrumb,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onOpenOutline() })
                    }
            )
            if (totalPages > 0) {
                Text(
                    text = "${currentPage + 1} / $totalPages",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun DotLeaderOutlineText(
    title: String,
    pageNum: Int,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val dotColor = Color.Gray.copy(alpha = 0.45f)
    SubcomposeLayout(modifier) { constraints ->
        val pageNumPlaceable = subcompose("pageNum") {
            Text(text = "$pageNum", color = Color.Gray, fontSize = 12.sp)
        }[0].measure(Constraints())

        val minDotGap = 20
        val maxTitleWidth = (constraints.maxWidth - pageNumPlaceable.width - minDotGap).coerceAtLeast(0)
        val titlePlaceable = subcompose("title") {
            Text(text = title, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }[0].measure(Constraints(maxWidth = maxTitleWidth))

        val height = maxOf(titlePlaceable.height, pageNumPlaceable.height)
        val dotsStart = titlePlaceable.width
        val dotsEnd = constraints.maxWidth - pageNumPlaceable.width
        val dotsWidth = (dotsEnd - dotsStart).coerceAtLeast(0)

        val dotsPlaceable = subcompose("dots") {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * 0.78f
                drawLine(
                    color = dotColor,
                    start = Offset(4.dp.toPx(), y),
                    end = Offset(size.width - 4.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()), 0f)
                )
            }
        }[0].measure(Constraints.fixed(dotsWidth, height))

        layout(constraints.maxWidth, height) {
            titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
            dotsPlaceable.placeRelative(dotsStart, 0)
            pageNumPlaceable.placeRelative(
                constraints.maxWidth - pageNumPlaceable.width,
                (height - pageNumPlaceable.height) / 2
            )
        }
    }
}

