package com.gazw89.goodhive.ui.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val ITEM_HEIGHT = GoodHiveSpacing.xxl + GoodHiveSpacing.lg // 48.dp
private val PICKER_WIDTH = GoodHiveSpacing.xxl * 3 // 96.dp
private const val NEIGHBOR_MIN_SCALE = 0.88f
private const val NEIGHBOR_MIN_ALPHA = 0.25f

/**
 * iOS/Material-style scrolling number picker: a snapping LazyColumn with the selected value
 * centered between two divider lines. Used for hour/minute/temperature entry instead of a
 * raw text field — the plain OutlinedTextField-based dialog was flagged as feeling amateur.
 */
@Composable
fun WheelPicker(
    values: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Value picker",
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, values.lastIndex.coerceAtLeast(0)))
    val reducedMotion = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { ITEM_HEIGHT.toPx() }

    val snapLayoutInfoProvider = remember(listState) { SnapLayoutInfoProvider(listState) }
    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
    val snapAnimationSpec = remember(reducedMotion) {
        GoodHiveMotion.settleSpring<Float>(reducedMotion)
    }
    val flingBehavior = remember(snapLayoutInfoProvider, decayAnimationSpec, snapAnimationSpec, reducedMotion) {
        snapFlingBehavior(
            snapLayoutInfoProvider = snapLayoutInfoProvider,
            decayAnimationSpec = if (reducedMotion) {
                exponentialDecay(frictionMultiplier = 100f)
            } else {
                decayAnimationSpec
            },
            snapAnimationSpec = if (reducedMotion) snap() else snapAnimationSpec,
        )
    }

    // Extra empty space (one item tall) above/below so the first/last real item can still
    // scroll to the centered position, rather than being pinned at the edge.
    val centeredIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) return@derivedStateOf selectedIndex
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index
                ?.coerceIn(0, values.lastIndex)
                ?: selectedIndex
        }
    }

    var hapticsArmed by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { index ->
                onSelectedIndexChange(index)
                if (hapticsArmed) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                } else {
                    hapticsArmed = true
                }
            }
    }

    val selectedLabel = values.getOrElse(centeredIndex) { "" }
    val selectionBandColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    val selectionShape = MaterialTheme.shapes.small

    Box(
        modifier = modifier
            // Fixed width so HorizontalDivider's fillMaxWidth cannot expand this box and
            // squeeze sibling wheels (hour/minute) out of the parent Row.
            .width(PICKER_WIDTH)
            .height(ITEM_HEIGHT * 3)
            .semantics(mergeDescendants = true) {
                this.contentDescription = "$contentDescription, $selectedLabel"
                stateDescription = selectedLabel
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .background(selectionBandColor, selectionShape),
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = ITEM_HEIGHT),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(values) { index, value ->
                val distanceFraction by remember(index, itemHeightPx) {
                    derivedStateOf {
                        val layoutInfo = listState.layoutInfo
                        val viewportCenter =
                            (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                        val item = layoutInfo.visibleItemsInfo.find { it.index == index }
                        if (item == null) {
                            2f
                        } else {
                            val itemCenter = item.offset + item.size / 2f
                            abs(itemCenter - viewportCenter) / itemHeightPx.coerceAtLeast(1f)
                        }
                    }
                }
                val alpha = (1f - distanceFraction * 0.35f).coerceIn(NEIGHBOR_MIN_ALPHA, 1f)
                val scale = (1f - distanceFraction * 0.06f).coerceIn(NEIGHBOR_MIN_SCALE, 1f)
                val isSelected = index == centeredIndex

                Box(
                    modifier = Modifier
                        .height(ITEM_HEIGHT)
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable {
                            scope.launch {
                                if (reducedMotion) {
                                    listState.scrollToItem(index)
                                } else {
                                    listState.animateScrollToItem(index)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value,
                        style = if (isSelected) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.graphicsLayer { translationY = -ITEM_HEIGHT.toPx() / 2 })
        HorizontalDivider(modifier = Modifier.graphicsLayer { translationY = ITEM_HEIGHT.toPx() / 2 })
    }
}
