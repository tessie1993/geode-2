package dev.geode.ui.opaline.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.geode.R
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineIconButton
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.OpalineVec3
import dev.geode.ui.opaline.opalineElement
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalinePartEvents

/** The Now Playing look (plan §12b), worn with any colour pack: V1 glass, V2 bubble, V3 drop. */
enum class OpalineStyle { GLASS, BUBBLE, DROP }

/** E08's authored width over height (elements.json E08 modelAsset.bounds.size). */
private const val E08_ASPECT = 1.2575141f / 1.9f

/**
 * Glass hero (video V1): every UI076 part; the artwork inside a second UI076 dew lens (N03/water)
 * fitted into the panel's content frame; two E08 water droplets standing on the N07 glass.
 * [artwork] fills its slot.
 */
@Composable
fun OpalineGlassHero(
    title: String,
    artist: String,
    playing: Boolean,
    onArtworkClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val recipe = rememberOpalineComposition("UI076")
    val events = rememberOpalinePartEvents()
    val body = recipe.part("body")
    val frame = recipe.frame("content")
    val dew = recipe.part("dew").dimensions
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OpalineRecipeLayout(
            "UI076",
            Modifier
                .fillMaxWidth()
                .observePointer(PointerEventType.Move) { events.raise("pointermove") },
            fitWidth = true,
        ) {
            OpalineSceneParts(recipe, events, skip = setOf("body"))
            Box(Modifier.sceneNode(recipe, "body").opalinePart("UI076", events = events)) {
                // Placement follows reference video V1; not defined by the library: centred under
                // the content frame's lower corners, in its padding band, at the dew's height.
                val y = frame.position.y - (frame.height + frame.padding) / 2 - body.position.y
                val width = dew.y * E08_ASPECT
                for (side in listOf(-1, 1)) {
                    val x = frame.position.x + side * frame.width / 2 - body.position.x
                    Box(
                        Modifier
                            .align(
                                BiasAlignment(
                                    2 * x / (body.dimensions.x - width),
                                    -2 * y / (body.dimensions.y - dew.y),
                                ),
                            ).fillMaxWidth(width / body.dimensions.x)
                            .fillMaxHeight(dew.y / body.dimensions.y)
                            .opalineElement("E08", material = "water", events = events),
                    )
                }
            }
            HeroArtwork(
                Modifier
                    .sceneNode(recipe, "content")
                    .aspectRatio(dew.x / dew.y)
                    .opalinePart("UI076", "dew", value = artworkValue(playing), events = events)
                    .onFocusChanged { if (it.isFocused) events.raise("focus") },
                circle(),
                title,
                playing,
                onArtworkClick,
                artwork,
            )
        }
        HeroText(title, artist)
    }
}

/**
 * Bubble hero (video V2): the F01 isolated film bubble with the artwork afloat inside; F01's
 * authored bounds are a cube (elements.json), so its node is square. [artwork] fills its slot.
 */
@Composable
fun OpalineBubbleHero(
    title: String,
    artist: String,
    playing: Boolean,
    onArtworkClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HeroArtwork(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .opalineElement("F01", value = artworkValue(playing), events = events),
            circle(),
            title,
            playing,
            onArtworkClick,
            artwork,
        )
        HeroText(title, artist)
    }
}

/**
 * Drop hero (video V3), stacked edge to edge at one scale: the artwork in the UI060 glass tile
 * (C19/shell), the UI072 droplet (E09) hanging from it, the UI074 neck (E11) as capillary bridge
 * and the UI073 flow-primary channel (E12) showing [progress]. This hero carries the transport's
 * progress channel. [artwork] fills its slot.
 */
@Composable
fun OpalineDropHero(
    title: String,
    artist: String,
    playing: Boolean,
    progress: Float,
    seekDescription: String,
    onSeek: (Float) -> Unit,
    onArtworkClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val tile = rememberOpalineComposition("UI060")
    val body = tile.part("body").dimensions
    val image = tile.frame("image")
    val drop = rememberOpalineComposition("UI072").part("droplet").dimensions
    val neck = rememberOpalineComposition("UI074").part("neck").dimensions
    val flow = rememberOpalineComposition("UI073").part("flow-primary").dimensions
    val widest = maxOf(body.x, drop.x, neck.x, flow.x)
    val events = rememberOpalinePartEvents()
    val channel = rememberOpalinePartEvents()

    fun Modifier.link(size: OpalineVec3) = fillMaxWidth(size.x / widest).aspectRatio(size.x / size.y)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HeroArtwork(
            Modifier
                .link(body)
                .opalinePart("UI060", value = artworkValue(playing), events = events),
            Modifier
                .fillMaxWidth(image.width / body.x)
                .fillMaxHeight(image.height / body.y),
            title,
            playing,
            {
                events.raise("activate")
                onArtworkClick()
            },
            artwork,
        )
        Box(Modifier.link(drop).opalinePart("UI072", "droplet"))
        Box(Modifier.link(neck).opalinePart("UI074", "neck"))
        Box(Modifier.link(flow).opalineLiquidFlow(progress, seekDescription, channel, onSeek))
        HeroText(title, artist)
    }
}

/**
 * The transport every style shares (plan §12b): UI003 pebbles for shuffle, previous, next,
 * repeat, A-B loop and favourite, each lifted while on, Repeat One with its own icon. Play/pause
 * is A22 in the `blue` selector while playing, UI003's `gel` otherwise. Without [enabled] (no
 * media) every pebble looks and acts disabled.
 */
@Composable
fun OpalineTransport(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    shuffle: Boolean,
    onShuffle: () -> Unit,
    repeatMode: Int,
    onRepeat: () -> Unit,
    abLabel: String,
    abActive: Boolean,
    onAbLoop: () -> Unit,
    favourite: Boolean,
    onFavourite: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val pebble = rememberOpalineComposition("UI003")
    val body = pebble.part("body")
    val play = stringResource(if (playing) R.string.action_pause else R.string.action_play)
    val repeat =
        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpalineIconButton(
            Icons.Filled.Shuffle,
            stringResource(R.string.action_shuffle),
            onShuffle,
            enabled = enabled,
            selected = shuffle,
        )
        OpalineIconButton(
            Icons.Filled.SkipPrevious,
            stringResource(R.string.action_previous),
            onPrevious,
            enabled = enabled,
        )
        Box(
            Modifier
                .size(48.dp)
                .opalineElement(
                    body.element,
                    if (playing) "blue" else body.material,
                    enabled = enabled,
                ).clickable(enabled, role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                play,
                Modifier
                    .fillMaxSize(pebble.frame("content").width / body.dimensions.x)
                    .alpha(if (enabled) 1f else 0.45f),
                tint = OpalineColors.text,
            )
        }
        OpalineIconButton(
            Icons.Filled.SkipNext,
            stringResource(R.string.action_next),
            onNext,
            enabled = enabled,
        )
        OpalineIconButton(
            repeat,
            stringResource(R.string.action_repeat),
            onRepeat,
            enabled = enabled,
            selected = repeatMode != Player.REPEAT_MODE_OFF,
        )
        OpalineIconButton(
            Icons.Filled.Loop,
            abLabel,
            onAbLoop,
            enabled = enabled,
            selected = abActive,
        )
        OpalineIconButton(
            if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            stringResource(
                if (favourite) R.string.action_favourite_remove else R.string.action_favourite_add,
            ),
            onFavourite,
            enabled = enabled,
            selected = favourite,
        )
    }
}

/**
 * Drop-style text tabs (video V3): the selected label lit by UI011's focus-rail (D14/glow), set
 * under it at UI011's rail-to-input-frame proportions; selecting raises focus → illuminate.
 */
@Composable
fun OpalineDropTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI011")
    val frame = recipe.frame("input")
    val rail = recipe.part("focus-rail")
    val style = MaterialTheme.typography.titleMedium
    // Dp per catalogue metre: a label line fills UI011's input frame height.
    val metre = with(LocalDensity.current) { style.lineHeight.toDp() } / frame.height
    val gap = frame.position.y - rail.position.y - (frame.height + rail.dimensions.y) / 2
    Row(modifier.selectableGroup()) {
        titles.forEachIndexed { i, title ->
            val selected = i == selectedIndex
            val events = rememberOpalinePartEvents()
            LaunchedEffect(selected) { if (selected) events.raise("focus") }
            Column(
                Modifier
                    .selectable(selected, role = Role.Tab) { onSelect(i) }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(metre * frame.padding)
                    .width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    color = if (selected) OpalineColors.text else OpalineColors.muted,
                    style = style,
                    maxLines = 1,
                )
                if (selected) {
                    Box(
                        Modifier
                            .padding(top = metre * gap)
                            .fillMaxWidth()
                            .height(metre * rail.dimensions.y)
                            .opalinePart("UI011", "focus-rail", selected = true, events = events),
                    )
                }
            }
        }
    }
}

/**
 * Drop-style library row (video V3): a square UI060 glass tile (C19/shell) at the 48 dp touch
 * floor holding [artwork] in its image frame, then title and subtitle; activate → lift.
 */
@Composable
fun OpalineDropRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: @Composable () -> Unit = {},
    artwork: @Composable () -> Unit,
) {
    val recipe = rememberOpalineComposition("UI060")
    val body = recipe.part("body").dimensions
    val image = recipe.frame("image")
    val events = rememberOpalinePartEvents()
    val tile = 48.dp
    Row(
        modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(role = Role.Button) {
                events.raise("activate")
                onClick()
            },
        // The tile's own inset around its image frame.
        horizontalArrangement = Arrangement.spacedBy(tile * ((body.x - image.width) / 2 / body.x)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(tile)
                .opalinePart("UI060", selected = selected, events = events),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth(image.width / body.x).fillMaxHeight(image.height / body.y)) {
                artwork()
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = OpalineColors.text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = OpalineColors.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * The hero's artwork holder: [holder] registers the geometry around the art, [slot] places the
 * art inside it; it reads as the track's image with its play state.
 */
@Composable
private fun HeroArtwork(
    holder: Modifier,
    slot: Modifier,
    title: String,
    playing: Boolean,
    onClick: () -> Unit,
    artwork: @Composable () -> Unit,
) {
    val state = stringResource(if (playing) R.string.state_now_playing else R.string.state_paused)
    Box(
        holder
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = title
                stateDescription = state
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(slot) { artwork() }
    }
}

@Composable
private fun HeroText(
    title: String,
    artist: String,
) {
    Text(
        title,
        color = OpalineColors.text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        artist,
        color = OpalineColors.muted,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

/** UI061's circular image slot at its share of the C20 body: round art inside its holder. */
@Composable
private fun circle(): Modifier {
    val frame = rememberOpalineComposition("UI061")
    return Modifier
        .fillMaxSize(frame.frame("image").width / frame.part("body").dimensions.x)
        .clip(CircleShape)
}

/** The Now Playing artwork value of plan §3: 1 while playing, .25 idle. */
private fun artworkValue(playing: Boolean) = if (playing) 1f else .25f
