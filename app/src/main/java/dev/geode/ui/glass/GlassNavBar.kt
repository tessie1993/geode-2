package dev.geode.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.geode.ui.theme.StoneHapticCue
import dev.geode.ui.theme.performStoneHaptic

/**
 * Floating glass capsule navigation bar matching the mockup in liquid_player_lib_1789776612307.jpg:
 * - Rounded frosted glass capsule container floating detached from the screen edge
 * - Active tab highlighted by an opalescent glowing cyan/mint pill chip
 * - Inactive tabs display neat icons with subtle typography
 * - Tactile water ripple and spring transitions on tab change
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 1f,
) {
    val field = LocalWaterField.current
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(opacity.coerceIn(0f, 1f))
            .glassSurface(shape = GlassShapes.pill)
            .floatOnWater(strength = 0.2f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val sel = i == selected
                val contentColor by animateColorAsState(
                    targetValue = if (sel) Color.White else GlassPalette.textSecondary,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "navColor",
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .selectable(
                            selected = sel,
                            role = Role.Tab,
                            onClick = {
                                if (!sel) {
                                    view.performStoneHaptic(StoneHapticCue.SLIDER_TICK)
                                    field?.tap(0f, 0f, 1.2f)
                                    onSelect(i)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sel) {
                        // Luminous Active Teal Pill Chip matching ref mockup
                        Row(
                            modifier = Modifier
                                .glassSurface(
                                    shape = GlassShapes.pill,
                                    tint = GlassPalette.cyan,
                                    glow = 0.55f,
                                )
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor,
                                maxLines = 1,
                            )
                        }
                    } else {
                        // Inactive tab: clean stacked icon + label
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
                                fontWeight = FontWeight.Normal,
                                color = contentColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
