package app.chencang.android.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.chencang.android.ChannelFeatures
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

object AboutTestTags {
    const val SITE = "about-site"
    const val SOURCE = "about-source"
    const val SHARE_APP = "about-share-app"
    const val PRIVACY_POLICY = "about-privacy-policy"
}

/** 关于陈仓：它是什么、怎么用、为什么安全，以及官网与源码入口。纯原生 Compose 文本，不嵌网页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    site: String,
    versionName: String,
    onShareApp: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenSite: () -> Unit,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    canShareApk: Boolean = ChannelFeatures.canShareApk,
) {
    Scaffold(
        containerColor = moyuColors.surfaceBase,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), color = moyuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = moyuColors.accentPrimary,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .verticalScroll(rememberScrollState())
                .padding(Moyu.Space.Xl),
        ) {
            Section(R.string.about_what_title, listOf(R.string.about_what_body))
            Section(R.string.about_how_title, listOf(R.string.about_how_step1, R.string.about_how_step2, R.string.about_how_step3))
            Section(R.string.about_safe_title, listOf(R.string.about_safe_body))
            Text(
                stringResource(R.string.about_download_title),
                style = MaterialTheme.typography.titleMedium,
                color = moyuColors.textPrimary,
            )
            Spacer(Modifier.height(Moyu.Space.S))
            Text(
                site,
                style = MaterialTheme.typography.bodyMedium,
                color = moyuColors.accentPrimary,
                modifier = Modifier.clickable(onClick = onOpenSite).testTag(AboutTestTags.SITE),
            )
            Text(
                stringResource(R.string.settings_version) + " " + versionName,
                style = MaterialTheme.typography.bodySmall,
                color = moyuColors.textSecondary,
            )
            TextButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth().testTag(AboutTestTags.SOURCE)) {
                Text(stringResource(R.string.about_source_code))
            }
            TextButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth().testTag(AboutTestTags.PRIVACY_POLICY)) {
                Text(stringResource(R.string.settings_privacy_policy))
            }
            if (canShareApk) {
                TextButton(onClick = onShareApp, modifier = Modifier.fillMaxWidth().testTag(AboutTestTags.SHARE_APP)) {
                    Text(stringResource(R.string.share_app_row))
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, paragraphs: List<Int>) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = moyuColors.textPrimary)
    Spacer(Modifier.height(Moyu.Space.S))
    paragraphs.forEach {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = moyuColors.textSecondary)
        Spacer(Modifier.height(Moyu.Space.S))
    }
    Spacer(Modifier.height(Moyu.Space.L))
}
