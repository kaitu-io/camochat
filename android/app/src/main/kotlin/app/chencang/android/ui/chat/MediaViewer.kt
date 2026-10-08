package app.chencang.android.ui.chat

import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.chencang.design.Moyu
import app.chencang.design.MoyuLight
import app.chencang.design.moyuColors
import app.chencang.shared.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 全屏看图(纯黑底):双指缩放、双击放大/还原、单击关闭、顶栏「转发」(当前这张)、「保存到相册」(spec §3.4)。 */
@Composable
internal fun ImageViewer(path: String, onForward: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxPx) {
        // 解码失败(损坏文件/OOM)不能崩这个 Dialog——跟 rememberThumbnail 同一个 runCatching 套路。
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapDecode.sampled(path, maxPx)?.asImageBitmap() }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // scale = 1 时真正画出来的位图尺寸——不能拿 Image 自己的布局盒子算(fillMaxWidth 只钉住宽度,
    // 高度不够放的时候 Compose 会把盒子撑到整个 viewport,ContentScale.Fit 再在那个盒子里居中缩小
    // 画,盒子本身早就不是画出来的那块了;竖图在窄屏上会因此被少算高度,缩放拖到头能被拖出屏幕)。
    // 直接按 min(视口宽/位图宽, 视口高/位图高)算,跟 ContentScale.Fit 在任何布局盒子里的画法都一致。
    fun paintedSize(): IntSize {
        val bmp = bitmap
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0 || boxSize.width <= 0 || boxSize.height <= 0) {
            return IntSize.Zero
        }
        val fit = minOf(boxSize.width.toFloat() / bmp.width, boxSize.height.toFloat() / bmp.height)
        return IntSize((bmp.width * fit).roundToInt(), (bmp.height * fit).roundToInt())
    }

    fun clampOffset(raw: Offset, atScale: Float): Offset {
        val painted = paintedSize()
        val maxX = ((painted.width * atScale - boxSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((painted.height * atScale - boxSize.height) / 2f).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(moyuColors.mediaViewerBackground)
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else MediaLayout.DOUBLE_TAP_ZOOM
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, MediaLayout.MAX_ZOOM)
                        scale = newScale
                        // 缩放拉到 1 就回正中;否则把平移夹在图片不会被拖出屏幕的范围内。
                        offset = if (newScale <= 1f) Offset.Zero else clampOffset(offset + pan, newScale)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = stringResource(R.string.media_image_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            ViewerCloseButton(onDismiss, Modifier.align(Alignment.TopStart))
            if (onForward != null) {
                TextButton(
                    onClick = onForward,
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(Moyu.Space.Xl),
                    // 有意复用 MoyuLight.accentOnPrimary：mediaViewerBackground 两条轨都是纯黑，前景需恒为浅色
                ) { Text(stringResource(R.string.common_forward), color = MoyuLight.accentOnPrimary) }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        // 最多拷 2 MB 的明文文件——挪到 IO 线程,免得卡住主线程;MediaStore 在个别
                        // 机型上会抛 IOException 之外的异常(SecurityException 等),兜个底不崩溃。
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                MediaSaver.saveImage(context, path)
                            } catch (e: Exception) {
                                false
                            }
                        }
                        Toast.makeText(context, if (ok) R.string.media_saved else R.string.media_save_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(Moyu.Space.Xl),
                // 有意复用 MoyuLight.accentOnPrimary：mediaViewerBackground 两条轨都是纯黑，前景需恒为浅色
            ) { Text(stringResource(R.string.media_save_to_album), color = MoyuLight.accentOnPrimary) }
        }
    }
}

/** 视频全屏播放:平台 VideoView + MediaController(裁决:不引入 ExoPlayer UI)。 */
@Composable
internal fun VideoPlayerDialog(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(moyuColors.mediaViewerBackground).windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoPath(path)
                        setOnPreparedListener { start() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { it.stopPlayback() },
            )
            ViewerCloseButton(onDismiss, Modifier.align(Alignment.TopStart))
        }
    }
}

/** 看图/看视频共用的左上角关闭图标（单击空白处关闭的手势仍保留）。前景恒浅色：查看器背景两条轨都是纯黑。 */
@Composable
private fun ViewerCloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onDismiss,
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(Moyu.Space.Xl),
    ) {
        // 有意复用 MoyuLight.accentOnPrimary：mediaViewerBackground 两条轨都是纯黑，前景需恒为浅色
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MoyuLight.accentOnPrimary)
    }
}
