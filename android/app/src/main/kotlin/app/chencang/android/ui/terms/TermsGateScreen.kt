package app.chencang.android.ui.terms

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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

object TermsGateTestTags {
    const val VIEW_TERMS = "terms-gate-view"
    const val ACCEPT = "terms-gate-accept"
}

/** One-time gate for existing users who have not accepted the current Terms of Use. */
@Composable
fun TermsGateScreen(onOpenTerms: () -> Unit, onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(Moyu.Space.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.terms_gate_title),
            fontSize = Moyu.FontSize.Title,
            fontWeight = FontWeight.SemiBold,
            color = moyuColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.M))
        Text(
            stringResource(R.string.terms_gate_body),
            fontSize = Moyu.FontSize.Callout,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.L))
        TextButton(onClick = onOpenTerms, modifier = Modifier.testTag(TermsGateTestTags.VIEW_TERMS)) {
            Text(stringResource(R.string.terms_view))
        }
        Spacer(Modifier.height(Moyu.Space.L))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().testTag(TermsGateTestTags.ACCEPT)) {
            Text(stringResource(R.string.terms_gate_accept))
        }
    }
}
