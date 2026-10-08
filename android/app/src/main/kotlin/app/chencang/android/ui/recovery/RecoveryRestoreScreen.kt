package app.chencang.android.ui.recovery

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object RecoveryRestoreTestTags {
    const val MNEMONIC_INPUT = "recovery-restore-input"
    const val RESTORE_BUTTON = "recovery-restore-button"
}

@Composable
fun RecoveryRestoreScreen(onRestore: (mnemonic: String) -> Unit) {
    var mnemonic by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Restore identity", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your 12-word recovery phrase, separated by spaces.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = mnemonic,
            onValueChange = { mnemonic = it },
            modifier = Modifier.testTag(RecoveryRestoreTestTags.MNEMONIC_INPUT),
            label = { Text("Recovery phrase") },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            modifier = Modifier.testTag(RecoveryRestoreTestTags.RESTORE_BUTTON),
            onClick = { onRestore(mnemonic.trim()) },
            enabled = mnemonic.trim().split(Regex("\\s+")).size in 12..24,
        ) { Text("Restore") }
    }
}
