package dev.geode.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val RowCardShape: Shape = RoundedCornerShape(20.dp)

/**
 * Frosted liquid glass card row directly matching the track and playlist items
 * in the approved mockup (liquid_player_lib_1789776612307.jpg):
 * - Rounded frosted translucent card with dual-tier ambient shadow and thin iridescent rim
 * - Leading frosted bubble container for artwork or vinyl disc icon
 * - Clean white typography with soft secondary detail
 * - Tactile water ripple on touch and gentle floating on water field
 */
@Composable
fun GlassListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(shape = RowCardShape, selected = selected, glow = if (selected) 0.35f else 0f)
                .floatOnWater(strength = 0.22f)
                .then(if (onClick != null) Modifier.glassTouch(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) {
            Box(
                modifier =
                    Modifier
                        .size(46.dp)
                        .glassSurface(shape = RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
        }
        Column(Modifier.weight(1f)) {
            val content = if (selected) GlassPalette.textPrimary else GlassPalette.textPrimary.copy(alpha = 0.95f)
            CompositionLocalProvider(LocalContentColor provides content) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassPalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (trailing != null) trailing()
    }
}
