package com.landosol.toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Landscape emulator windows have plenty of width but very little vertical room.  Treat them as
 * a compact viewport instead of scaling the portrait layout up until only one card is visible.
 */
internal fun isCompactLandscape(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= 600 && widthDp > heightDp && heightDp < 720

internal fun shouldUseNavigationRail(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= 600 && widthDp > heightDp

internal val CompactLandscapeTypography = Typography(
    headlineLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp),
    headlineMedium = TextStyle(fontSize = 18.sp, lineHeight = 23.sp),
    headlineSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    titleSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    bodySmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
    labelLarge = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelMedium = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontSize = 9.sp, lineHeight = 12.sp),
)

/** A deliberately short app header; unlike TopAppBar it does not reserve 64 dp below the inset. */
@Composable
internal fun CompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 40.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { back ->
                TextButton(onClick = back, enabled = backEnabled) {
                    Text("返回", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            actions()
        }
    }
}
