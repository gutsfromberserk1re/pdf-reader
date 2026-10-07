package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val externalPdfUri: Uri? = if (intent?.action == android.content.Intent.ACTION_VIEW) {
            intent.data
        } else null

        setContent {
            PdfReaderApp(initialUri = externalPdfUri)
        }
    }
}

@Composable
fun PdfReaderApp(initialUri: Uri?) {
    var pdfUri by remember { mutableStateOf(initialUri) }
    var isNightMode by remember { mutableStateOf(true) }

    val backgroundColor = if (isNightMode) Color(0xFF101010) else Color(0xFFF7F4EF)
    val textColor = if (isNightMode) Color(0xFFE0E0E0) else Color(0xFF1F1F1F)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { pdfUri = it } }

    Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
        if (pdfUri == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📖 Pure E-Reader", fontSize = 28.sp, color = textColor)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Open any PDF file to start reading", fontSize = 14.sp, color = textColor.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { filePickerLauncher.launch("application/pdf") },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isNightMode) Color(0xFF333333) else Color(0xFF2C3E50)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose PDF File", color = Color.White)
                }
            }
        } else {
            PdfViewer(
                uri = pdfUri!!,
                isNightMode = isNightMode,
                onToggleNightMode = { isNightMode = !isNightMode },
                onOpenNewFile = { filePickerLauncher.launch("application/pdf") }
            )
        }
    }
}

@Composable
fun PdfViewer(uri: Uri, isNightMode: Boolean, onToggleNightMode: () -> Unit, onOpenNewFile: () -> Unit) {
    val context = LocalContext.current
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(true) }

    DisposableEffect(uri) {
        runCatching {
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            fileDescriptor?.let { fd ->
                val renderer = PdfRenderer(fd)
                pdfRenderer = renderer
                pageCount = renderer.pageCount
            }
        }
        onDispose {
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    if (pageCount == 0 || pdfRenderer == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                showControls = !showControls
            }
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
            PdfPageRenderView(renderer = pdfRenderer!!, pageIndex = pageIndex, isNightMode = isNightMode)
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenNewFile) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White)
                }
                Text("Page ${pagerState.currentPage + 1} of $pageCount", color = Color.White, fontSize = 14.sp)
                IconButton(onClick = onToggleNightMode) {
                    Icon(if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun PdfPageRenderView(renderer: PdfRenderer, pageIndex: Int, isNightMode: Boolean) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pageIndex, isNightMode) {
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(pageIndex)
            val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            if (isNightMode) {
                val canvas = android.graphics.Canvas(bmp)
                val paint = Paint()
                paint.colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                )
                canvas.drawBitmap(bmp, 0f, 0f, paint)
            }
            page.close()
            bitmap = bmp
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            )
        } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
    }
}
