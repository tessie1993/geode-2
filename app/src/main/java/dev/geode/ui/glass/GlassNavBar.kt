package dev.geode.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A floating opaline dock: a structural shell carrying individually sprung gel controls. */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 1f,
) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(opacity.coerceIn(0.65f, 1f))
                .glassSurface(shape = GlassShapes.pill)
                .padding(6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                OpalineDestination(item, index == selected, { onSelect(index) }, Modifier.weight(1f))
            }
        }
    }
}

/** The same destinations become a sculptural, scrollable side rail on wider windows. */
@Composable
fun GlassNavigationRail(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(104.dp)
            .glassSurface(shape = GlassShapes.card)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEachIndexed { index, item ->
            OpalineDestination(item, index == selected, { onSelect(index) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OpalineDestination(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalGlass.current.reducedMotion
    val compression by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.92f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.52f, stiffness = 420f),
        label = "opalineDockCompression",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected && !reducedMotion) -3f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.64f, stiffness = 280f),
        label = "opalineDockLift",
    )
    Column(
        modifier
            .heightIn(min = 64.dp)
            .graphicsLayer {
                scaleX = compression
                scaleY = compression
                translationY = lift * density
                rotationX = if (reducedMotion) 0f else (1f - compression) * 45f
                cameraDistance = 16f * density
            }
            .glassSurface(
                shape = GlassShapes.pill,
                tint = if (selected) GlassPalette.mint else null,
                selected = selected,
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 3.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        val tint = if (selected) GlassPalette.textPrimary else GlassPalette.textSecondary
        Icon(item.icon, contentDescription = null, modifier = Modifier.size(23.dp), tint = tint)
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}
