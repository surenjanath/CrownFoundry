package com.surenjanath.crownfoundry.utils

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

data class ScrollingInfo(
    val isScrollingDown: Boolean = false,
    val isFar: Boolean = false
) {
    fun and(condition: Boolean) =
//        copy(isScrollingDown = isScrollingDown && condition, isFar = isFar && condition)
        if (condition) this else copy(isScrollingDown = !isScrollingDown, isFar = !isFar)
}

@Composable
fun LazyListState.scrollingInfo(): ScrollingInfo {
    var previousIndex by remember(this) {
        mutableStateOf(firstVisibleItemIndex)
    }

    var previousScrollOffset by remember(this) {
        mutableStateOf(firstVisibleItemScrollOffset)
    }

    return remember(this) {
        derivedStateOf {
            val isScrollingDown = if (previousIndex == firstVisibleItemIndex) {
                firstVisibleItemScrollOffset > previousScrollOffset
            } else {
                firstVisibleItemIndex > previousIndex
            }

            val isFar = firstVisibleItemIndex > layoutInfo.visibleItemsInfo.size

            previousIndex = firstVisibleItemIndex
            previousScrollOffset = firstVisibleItemScrollOffset

            ScrollingInfo(isScrollingDown, isFar)
        }
    }.value
}
