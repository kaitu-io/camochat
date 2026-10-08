package app.chencang.android.ui.recovery

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object RecoveryExportTestTags {
    const val REVEAL_BUTTON = "recovery-export-reveal"
    const val MNEMONIC = "recovery-export-mnemonic"
}

@Composable
fun RecoveryExportScreen(mnemonicWords: List<String>) {
    var revealed by remember { mutableStateOf(false) }

    // FLAG_SECURE blocks screenshots / screen recording on this screen
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!revealed) {
            Text("⚠️ Warning", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "The recovery phrase below is your identity. Don't screenshot or share it. Write it down and keep it offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                modifier = Modifier.testTag(RecoveryExportTestTags.REVEAL_BUTTON),
                onClick = { revealed = true },
            ) { Text("I understand, show the phrase") }
        } else {
            Text("Recovery phrase (12 words)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                text = mnemonicWords.joinToString(" "),
                modifier = Modifier.testTag(RecoveryExportTestTags.MNEMONIC),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
