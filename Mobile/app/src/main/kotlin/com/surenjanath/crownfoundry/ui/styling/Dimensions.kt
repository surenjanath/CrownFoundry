package com.surenjanath.crownfoundry.ui.styling

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Suppress("ClassName")
object Dimensions {
    val itemsVerticalPadding = 8.dp

    val navigationRailWidth = 64.dp
    val navigationRailWidthLandscape = 128.dp
    val navigationRailIconOffset = 6.dp
    val headerHeight = 140.dp

    object thumbnails {
        /** The monogram that stands in for a story's favicon. */
        val story = 54.dp
        val user = 128.dp
    }

    /** Left inset of a comment, per level of nesting. */
    val commentIndent = 14.dp
}

inline val Dp.px: Int
    @Composable
    inline get() = with(LocalDensity.current) { roundToPx() }
