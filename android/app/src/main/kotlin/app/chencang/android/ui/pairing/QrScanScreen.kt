package app.chencang.android.ui.pairing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.chencang.design.moyuColors
import app.chencang.shared.R
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

object QrScanTestTags {
    const val CAMERA = "qr-scan-camera"
    const val CANCEL_BUTTON = "qr-scan-cancel"
    const val DENIED = "qr-scan-denied"
    const val PICK_PHOTO = "qr-scan-pick-photo"
}

/**
 * Face-to-face camera scan of a peer's pairing QR (or a QR picked from the photo library). The
 * decoded text is the pairing link — it funnels through the same intake / `PairingCoordinator`
 * path as the paste flow; this composable only transports it. The text is never logged.
 *
 * Compile-verified only — the live camera path is validated at the device UAT.
 * Camera permission is requested at runtime; denial shows a short message + 取消.
 * The [DecoratedBarcodeView]'s resume/pause is tied to the Compose lifecycle via
 * [DisposableEffect]; it stops decoding after the first successful decode.
 */
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { isGranted ->
        granted = isGranted
        denied = !isGranted
    }

    // 从相册选图：系统照片选择器，不需要相册权限；识别结果走和相机一样的 onResult。
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { QrImageDecoder.decode(context, uri) }
                if (text != null) onResult(text)
                else Toast.makeText(context, R.string.scanner_photo_no_code, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pickButton: @Composable () -> Unit = {
        OutlinedButton(
            modifier = Modifier.testTag(QrScanTestTags.PICK_PHOTO),
            onClick = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        ) { Text(stringResource(R.string.scanner_pick_photo)) }
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (granted) {
            val aim = stringResource(R.string.pairing_scan_aim)
            // Hold a single decoder instance across recompositions so resume/pause
            // and the one-shot decode guard apply to the same view.
            val barcodeView = remember {
                DecoratedBarcodeView(context).apply {
                    setStatusText(aim)
                }
            }
            // Track whether we've already fired so the continuous callback is one-shot.
            val decoded = remember { mutableStateOf(false) }

            AndroidView(
                factory = {
                    barcodeView.apply {
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult) {
                                if (decoded.value) return
                                decoded.value = true
                                pause()
                                onResult(result.text)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().height(360.dp).testTag(QrScanTestTags.CAMERA),
            )

            DisposableEffect(barcodeView) {
                barcodeView.resume()
                onDispose { barcodeView.pause() }
            }

            Spacer(Modifier.height(16.dp))
            pickButton()
            OutlinedButton(
                modifier = Modifier.testTag(QrScanTestTags.CANCEL_BUTTON),
                onClick = onCancel,
            ) { Text(stringResource(R.string.common_cancel)) }
        } else {
            Text(
                text = stringResource(R.string.pairing_scan_no_camera),
                style = MaterialTheme.typography.bodyMedium,
                color = if (denied) moyuColors.statusDanger else moyuColors.textPrimary,
                modifier = Modifier.testTag(QrScanTestTags.DENIED),
            )
            Spacer(Modifier.height(16.dp))
            pickButton()
            OutlinedButton(
                modifier = Modifier.testTag(QrScanTestTags.CANCEL_BUTTON),
                onClick = onCancel,
            ) { Text(stringResource(R.string.common_back)) }
        }
    }
}
