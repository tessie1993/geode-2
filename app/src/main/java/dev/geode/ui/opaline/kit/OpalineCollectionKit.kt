package dev.geode.ui.opaline.kit

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalinePressable
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.OpalineRow
import dev.geode.ui.opaline.binding
import dev.geode.ui.opaline.frameBox
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.partBox
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalinePartEvents
import dev.geode.ui.opaline.rememberRecipeRail
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** motion.js MotionController.drag gains over the host view size. */
private const val ROTARY_GAIN = 2.4f
private const val XY_GAIN = 3f
private const val SLIDER_GAIN = 3.6f

/** UI055 Accordion stack: body C03/shell, section-* C04/gel with titles in `header-*` frames. */
@Composable
fun OpalineAccordionStack(
    titles: List<String>,
    expanded: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    val (padding, gap) = rememberRecipeRail("UI055", "body", "section-0")
    Column(
        modifier.fillMaxWidth().opalinePart("UI055").padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        titles.forEachIndexed { index, title ->
            val events = rememberOpalinePartEvents()
            val open = expanded(index)
            OpalinePressable(
                "UI055",
                "section-0",
                "header-0",
                { onToggle(index) },
                Modifier.fillMaxWidth().semantics {
                    heading()
                    expandable(open) {
                        events.raise("activate")
                        onToggle(index)
                    }
                },
                alignment = Alignment.CenterStart,
                events = events,
            ) { Text(title) }
            if (open) Column { content(index) }
        }
    }
}

/** UI056 Expandable section: body C16/shell; [title] in `header`, [content] in `content`. */
@Composable
fun OpalineExpandableSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val events = rememberOpalinePartEvents()
    val toggle = {
        events.raise("activate")
        onExpandedChange(!expanded)
    }
    OpalineRecipeLayout(
        "UI056",
        modifier.fillMaxWidth(),
        fitWidth = true,
        flex = "content",
        flexAlignment = Alignment.TopStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI056", events = events))
        Box(
            Modifier
                .recipeFrame("header")
                .fillMaxSize()
                .clickable(role = Role.Button, onClick = toggle)
                .semantics {
                    heading()
                    expandable(expanded, toggle)
                },
            contentAlignment = Alignment.CenterStart,
        ) { Text(title, style = MaterialTheme.typography.titleSmall, color = OpalineColors.text) }
        Column(Modifier.recipeFrame("content")) { if (expanded) content() }
    }
}

/** UI057 Selectable list: body C03/shell holding lazy OpalineRow rows (row-* A05/gel). */
@Composable
fun <T> OpalineSelectableList(
    items: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    title: (T) -> String,
    modifier: Modifier = Modifier,
    subtitle: (T) -> String = { "" },
    key: ((T) -> Any)? = null,
    state: LazyListState = rememberLazyListState(),
    leading: @Composable (T) -> Unit = {},
    trailing: @Composable (T) -> Unit = {},
) {
    val (padding, gap) = rememberRecipeRail("UI057", "body", "row-0")
    LazyColumn(
        modifier.opalinePart("UI057").semantics { collectionInfo = CollectionInfo(items.size, 1) },
        state = state,
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(items, key = key?.let { { _: Int, item: T -> it(item) } }) { index, item ->
            val chosen = isSelected(item)
            OpalineRow(
                title = title(item),
                subtitle = subtitle(item),
                onClick = { onSelect(item) },
                modifier =
                    Modifier.semantics {
                        selected = chosen
                        collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                    },
                leading = { leading(item) },
                trailing = { trailing(item) },
                selected = chosen,
            )
        }
    }
}

/** UI058 Grid of cards: card-* C01/shell at the recipe's columns and pitch, lazily. */
@Composable
fun <T> OpalineCardGrid(
    items: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val recipe = rememberOpalineComposition("UI058")
    val boxes = recipe.parts.map { recipe.partBox(it.id) }
    BoxWithConstraints(modifier) {
        val scale = maxWidth.value / (boxes.maxOf { it.right } - boxes.minOf { it.left })
        LazyVerticalGrid(
            GridCells.Fixed(recipe.parts.map { it.position.x }.distinct().size),
            Modifier.fillMaxWidth(),
            verticalArrangement =
                Arrangement.spacedBy(((boxes[2].top - boxes[0].bottom) * scale).dp),
            horizontalArrangement =
                Arrangement.spacedBy(((boxes[1].left - boxes[0].right) * scale).dp),
        ) {
            itemsIndexed(items, key = key?.let { { _: Int, item: T -> it(item) } }) { index, item ->
                val events = rememberOpalinePartEvents()
                val card = recipe.parts[index % recipe.parts.size].id
                val frame = recipe.contentFrames.first { it.owner == card }.id
                OpalineRecipeLayout(
                    "UI058",
                    Modifier.clickable(role = Role.Button) {
                        events.raise("activate")
                        onSelect(item)
                    },
                    fitWidth = true,
                ) {
                    Box(Modifier.recipePart(card).opalinePart("UI058", card, events = events))
                    Column(Modifier.recipeFrame(frame)) { content(item) }
                }
            }
        }
    }
}

/**
 * UI059 Depth carousel: card-0…2 C03/shell hold the previous, current and next of [items]; a
 * swipe past half the orbit pitch, or a tap on a side card, raises swipe (orbit-reconfigure).
 */
@Composable
fun <T> OpalineDepthCarousel(
    items: List<T>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(T) -> Unit,
) {
    val recipe = rememberOpalineComposition("UI059")
    val events = rememberOpalinePartEvents()
    val cards = recipe.parts.sortedBy { it.position.x }
    val boxes = cards.map { recipe.partBox(it.id) }
    val threshold =
        (boxes[1].center.x - boxes[0].center.x) / 2 / (boxes.last().right - boxes.first().left)
    val move = { step: Int ->
        if (index + step in items.indices) {
            events.raise("swipe")
            onIndexChange(index + step)
        }
    }
    val latestMove by rememberUpdatedState(move)
    OpalineRecipeLayout(
        "UI059",
        modifier
            .semantics { collectionInfo = CollectionInfo(1, items.size) }
            .pointerInput(Unit) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = {
                        if (abs(travel) > size.width * threshold) {
                            latestMove(if (travel < 0) 1 else -1)
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    travel += drag
                }
            },
        fitWidth = true,
    ) {
        cards.forEachIndexed { slot, card ->
            val item = items.getOrNull(index + slot - 1)
            Box(
                Modifier
                    .recipePart(card.id)
                    .zIndex(card.position.z)
                    .opalinePart("UI059", card.id, enabled = item != null, events = events)
                    .clickable(enabled = item != null, role = Role.Button) {
                        if (slot == 1 && item != null) onOpen(item) else move(slot - 1)
                    },
                contentAlignment = Alignment.Center,
            ) { if (item != null) content(item) }
        }
    }
}

/** UI060 Image frame: body C19/shell; [image] fills the `image` slot. Activation lifts it. */
@Composable
fun OpalineImageFrame(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    image: @Composable () -> Unit,
) = ImageFrame("UI060", RectangleShape, 0.5f, modifier, onClick, image)

/** UI061 Circular image frame: body C20/shell valued [value]; [image] fills the round slot. */
@Composable
fun OpalineCircularImageFrame(
    modifier: Modifier = Modifier,
    value: Float = 0.5f,
    onClick: (() -> Unit)? = null,
    image: @Composable () -> Unit,
) = ImageFrame("UI061", CircleShape, value, modifier, onClick, image)

@Composable
private fun ImageFrame(
    composition: String,
    shape: Shape,
    value: Float,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    image: @Composable () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    val action =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(role = Role.Button) {
                events.raise("activate")
                onClick()
            }
        }
    OpalineRecipeLayout(composition, modifier.then(action)) {
        Box(Modifier.recipePart("body").opalinePart(composition, value = value, events = events))
        Box(
            Modifier.recipeFrame("image").fillMaxSize().clip(shape),
            propagateMinConstraints = true,
        ) { image() }
    }
}

/** UI062 Tree branch navigation: body D06/shell with node-* A01/gel; one dock per node group. */
@Composable
fun OpalineTreeNavigation(
    nodes: List<String>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI062")
    val slots = recipe.parts.filter { it.id.startsWith("node-") }
    Column(modifier) {
        nodes.chunked(slots.size).forEachIndexed { group, labels ->
            OpalineRecipeLayout("UI062", Modifier.fillMaxWidth(), fitWidth = true) {
                Box(Modifier.recipePart("body").opalinePart("UI062"))
                slots.forEachIndexed { slot, node ->
                    val events = rememberOpalinePartEvents()
                    val label = labels.getOrNull(slot)
                    Box(
                        Modifier
                            .recipePart(node.id)
                            .opalinePart("UI062", node.id, enabled = label != null, events = events)
                            .clickable(enabled = label != null, role = Role.Button) {
                                events.raise("activate")
                                onNavigate(group * slots.size + slot)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = OpalineColors.text,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * UI063 Colour picker: body C08/shell, hue ring B17/gel valued [hue] and saturation/brightness
 * plane B21/pigment; drags use the motion.js rotary (ring) and xy (plane) gains.
 */
@Composable
fun OpalineColourPicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (hue: Float, saturation: Float, brightness: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI063")
    val view = LocalView.current
    val colour by rememberUpdatedState(Triple(hue, saturation, brightness))
    val change by rememberUpdatedState(onChange)
    val body = recipe.partBox("body")
    val plane = recipe.partBox("plane")
    val percent = NumberFormat.getPercentInstance()
    OpalineRecipeLayout("UI063", modifier, fitWidth = true) {
        Box(
            Modifier.recipePart("body").opalinePart("UI063").pointerInput(Unit) {
                var onPlane = false
                var h = 0f
                var s = 0f
                var b = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        val x = body.left + start.x / size.width * body.width
                        val y = body.top + start.y / size.height * body.height
                        onPlane = x in plane.left..plane.right && y in plane.top..plane.bottom
                        h = colour.first
                        s = colour.second
                        b = colour.third
                    },
                ) { pointer, drag ->
                    pointer.consume()
                    val dx = drag.x / view.width
                    val dy = drag.y / view.height
                    if (onPlane) {
                        s = (s + dx * XY_GAIN).coerceIn(0f, 1f)
                        b = (b - dy * XY_GAIN).coerceIn(0f, 1f)
                    } else {
                        h = (h + (dx - dy) * ROTARY_GAIN).coerceIn(0f, 1f)
                    }
                    change(h, s, b)
                }
            },
        )
        Box(
            Modifier.recipePart("hue").opalinePart("UI063", "hue", value = hue).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..1f)
                setProgress {
                    change(it, saturation, brightness)
                    true
                }
            },
        )
        Box(
            Modifier
                .recipePart("plane")
                .opalinePart("UI063", "plane", value = saturation, secondaryValue = brightness)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(saturation, 0f..1f)
                    stateDescription =
                        listOf(saturation, brightness).joinToString {
                            percent.format(it.toDouble())
                        }
                    setProgress {
                        change(hue, it, brightness)
                        true
                    }
                },
        )
    }
}

/**
 * UI064 Date grid picker: body C01/shell, day-* A04/gel; [month] in the `month` frame. A sixth
 * week reuses the fifth week's parts one row pitch lower.
 */
@Composable
fun OpalineDateGridPicker(
    month: YearMonth,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI064")
    val days = recipe.parts.filter { it.id.startsWith("day-") }
    val columns = days.map { it.position.x }.distinct().size
    val step = (days[0].position.y - days[columns].position.y) / days[0].dimensions.y
    val locale = Locale.getDefault()
    val first = month.atDay(1)
    val weekStart = WeekFields.of(locale).firstDayOfWeek.value
    val lead = (first.dayOfWeek.value - weekStart + columns) % columns
    val start = first.minusDays(lead.toLong())
    val cells = if (lead + month.lengthOfMonth() > days.size) days.size + columns else days.size
    val title = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"))
    val spoken = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    val grid = CollectionInfo(cells / columns, columns)
    OpalineRecipeLayout("UI064", modifier.semantics { collectionInfo = grid }, fitWidth = true) {
        Box(Modifier.recipePart("body").opalinePart("UI064"))
        Box(Modifier.recipeFrame("month").fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                month.format(title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = OpalineColors.text,
            )
        }
        for (cell in 0 until cells) {
            val events = rememberOpalinePartEvents()
            val date = start.plusDays(cell.toLong())
            val inMonth = YearMonth.from(date) == month
            val chosen = date == selected
            val extra = cell >= days.size
            val id = days[if (extra) cell - columns else cell].id
            Box(
                Modifier
                    .recipePart(id)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val down = if (extra) (placeable.height * step).roundToInt() else 0
                        layout(placeable.width, placeable.height) { placeable.place(0, down) }
                    }.opalinePart(
                        "UI064",
                        id,
                        selected = chosen,
                        enabled = inMonth,
                        events = events,
                    ).semantics {
                        collectionItemInfo =
                            CollectionItemInfo(cell / columns, 1, cell % columns, 1)
                        contentDescription = date.format(spoken)
                    }.selectable(chosen, enabled = inMonth) {
                        events.raise("activate")
                        onSelect(date)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (inMonth) OpalineColors.text else OpalineColors.muted,
                )
            }
        }
    }
}

/** UI065 Time wheel picker: body C01/shell, hours and minutes B22/gel over the bindings' ranges. */
@Composable
fun OpalineTimeWheelPicker(
    hour: Int,
    minute: Int,
    onChange: (hour: Int, minute: Int) -> Unit,
    hourLabel: String,
    minuteLabel: String,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI065")
    val hours = recipe.bindingRange("select-hour")
    val minutes = recipe.bindingRange("select-minute")
    val locale = Locale.getDefault()
    val hourText = String.format(locale, "%02d", hour)
    val minuteText = String.format(locale, "%02d", minute)
    OpalineRecipeLayout("UI065", modifier, fitWidth = true) {
        Box(Modifier.recipePart("body").opalinePart("UI065"))
        Wheel(
            "UI065",
            "hours",
            hour,
            hours.start.toInt()..hours.endInclusive.toInt(),
            hourLabel,
            hourText,
            { onChange(it, minute) },
        ) { Text(hourText, style = MaterialTheme.typography.titleLarge) }
        Wheel(
            "UI065",
            "minutes",
            minute,
            minutes.start.toInt()..minutes.endInclusive.toInt(),
            minuteLabel,
            minuteText,
            { onChange(hour, it) },
        ) { Text(minuteText, style = MaterialTheme.typography.titleLarge) }
    }
}

/** UI066 Option wheel picker: body C03/shell, wheel B22/nacre; the chosen option in `selection`. */
@Composable
fun OpalineOptionWheelPicker(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val option = options.getOrElse(selectedIndex) { "" }
    OpalineRecipeLayout("UI066", modifier, fitWidth = true) {
        Box(Modifier.recipePart("body").opalinePart("UI066"))
        Wheel("UI066", "wheel", selectedIndex, options.indices, label, option, onSelect)
        Box(
            Modifier.recipeFrame("selection").fillMaxSize().clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                option,
                style = MaterialTheme.typography.titleMedium,
                color = OpalineColors.text,
                maxLines = 1,
            )
        }
    }
}

/** A B22 wheel part over [range]: the motion.js rotary drag gain, slider semantics. */
@Composable
private fun Wheel(
    composition: String,
    part: String,
    value: Int,
    range: IntRange,
    label: String,
    state: String,
    onValueChange: (Int) -> Unit,
    content: @Composable () -> Unit = {},
) {
    val view = LocalView.current
    val current by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onValueChange)
    val span = (range.last - range.first).coerceAtLeast(1)
    Box(
        Modifier
            .recipePart(part)
            .opalinePart(composition, part, value = (value - range.first).toFloat() / span)
            .pointerInput(range) {
                var fraction = 0f
                detectDragGestures(
                    onDragStart = { fraction = (current - range.first).toFloat() / span },
                ) { pointer, drag ->
                    pointer.consume()
                    val turn = (drag.x / view.width - drag.y / view.height) * ROTARY_GAIN
                    fraction = (fraction + turn).coerceIn(0f, 1f)
                    commit(range.first + (fraction * span).roundToInt())
                }
            }.semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        value.toFloat(),
                        range.first.toFloat()..range.last.toFloat(),
                        span - 1,
                    )
                setProgress {
                    commit(it.roundToInt().coerceIn(range))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * UI067 Resizable split view: body C16/shell, divider B23/nacre; [first] and [second] in the
 * `left` and `right` frames, each pane at least the binding's minimumFraction.
 */
@Composable
fun OpalineSplitView(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val recipe = rememberOpalineComposition("UI067")
    val minimum = (recipe.binding("resize-split")["minimumFraction"] as Number).toFloat()
    val limits = minimum..1 - minimum
    val body = recipe.partBox("body")
    val divider = recipe.partBox("divider")
    val left = recipe.frameBox("left")
    val right = recipe.frameBox("right")
    val rest = (divider.center.x - body.left) / body.width
    val split = if (fraction.isFinite()) fraction.coerceIn(limits) else rest
    val latest by rememberUpdatedState(split)
    val change by rememberUpdatedState(onFractionChange)
    BoxWithConstraints(modifier.opalinePart("UI067")) {
        val scale = maxWidth.value / body.width
        val centre = maxWidth * split
        val widthPx = constraints.maxWidth.toFloat()
        val after = right.left - divider.center.x
        Box(
            Modifier
                .offset(x = ((left.left - body.left) * scale).dp)
                .width(centre - ((divider.center.x - body.left - left.width) * scale).dp)
                .padding(
                    top = ((left.top - body.top) * scale).dp,
                    bottom = ((body.bottom - left.bottom) * scale).dp,
                ).fillMaxHeight(),
        ) { first() }
        Box(
            Modifier
                .offset(x = centre + (after * scale).dp)
                .width(maxWidth - centre - ((after + body.right - right.right) * scale).dp)
                .padding(
                    top = ((right.top - body.top) * scale).dp,
                    bottom = ((body.bottom - right.bottom) * scale).dp,
                ).fillMaxHeight(),
        ) { second() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = centre - (divider.width / 2 * scale).dp)
                .width((divider.width * scale).dp)
                .fillMaxHeight(divider.height / body.height)
                .opalinePart("UI067", "divider")
                .pointerInput(widthPx) {
                    var at = 0f
                    detectHorizontalDragGestures(onDragStart = { at = latest }) { pointer, drag ->
                        pointer.consume()
                        at = (at + drag / widthPx).coerceIn(limits)
                        change(at)
                    }
                }.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(split, limits)
                    setProgress {
                        change(it.coerceIn(limits))
                        true
                    }
                },
        )
    }
}

/** UI068 Scrollbar rail: body B03/nacre valued by [state]'s position; B03 slider drag gain. */
@Composable
fun OpalineScrollbarRail(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val range = rememberOpalineComposition("UI068").bindingRange("scroll")
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val position by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val travel = info.totalItemsCount - info.visibleItemsInfo.size
            if (travel > 0) {
                (state.firstVisibleItemIndex.toFloat() / travel).coerceIn(range)
            } else {
                range.start
            }
        }
    }
    val scrollTo = { target: Float ->
        val info = state.layoutInfo
        val travel = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
        scope.launch { state.scrollToItem((target.coerceIn(range) * travel).roundToInt()) }
    }
    OpalineRecipeLayout("UI068", modifier) {
        // B03 runs bottom to top; the list's position runs top to bottom.
        Box(
            Modifier
                .recipePart("body")
                .opalinePart("UI068", value = range.endInclusive - position)
                .pointerInput(state) {
                    var at = 0f
                    detectVerticalDragGestures(onDragStart = { at = position }) { pointer, drag ->
                        pointer.consume()
                        at = (at + drag / view.height * SLIDER_GAIN).coerceIn(range)
                        scrollTo(at)
                    }
                }.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(position, range)
                    setProgress {
                        scrollTo(it)
                        true
                    }
                },
        )
    }
}

/**
 * UI069 Panel resize handle: body B23/nacre; drags resize the owner's [size], never below the
 * binding's minimum (catalogue metres at the handle's own scale).
 */
@Composable
fun OpalinePanelResizeHandle(
    size: DpSize,
    onSizeChange: (DpSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI069")
    val minimum =
        (recipe.binding("resize-owner")["minimum"] as List<*>).map { (it as Number).toFloat() }
    val handle = recipe.partBox("body")
    val latest by rememberUpdatedState(size)
    val change by rememberUpdatedState(onSizeChange)
    OpalineRecipeLayout("UI069", modifier) {
        Box(
            Modifier.recipePart("body").opalinePart("UI069").pointerInput(Unit) {
                var at = DpSize.Zero
                detectDragGestures(onDragStart = { at = latest }) { pointer, drag ->
                    pointer.consume()
                    val metre = this.size.width / handle.width
                    at =
                        DpSize(
                            maxOf(at.width + drag.x.toDp(), (minimum[0] * metre).toDp()),
                            maxOf(at.height + drag.y.toDp(), (minimum[1] * metre).toDp()),
                        )
                    change(at)
                }
            },
        )
    }
}

/**
 * UI070 Reorder handle row: body C04/shell, grip B23/nacre; [content] in `content`. Dragging the
 * grip raises drag (reorder) and calls [onMove] with ± rows per row height travelled.
 */
@Composable
fun OpalineReorderRow(
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    val move by rememberUpdatedState(onMove)
    var height by remember { mutableIntStateOf(0) }
    OpalineRecipeLayout(
        "UI070",
        modifier.fillMaxWidth().onSizeChanged { height = it.height },
        fitWidth = true,
        flex = "content",
        flexAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI070", events = events))
        Box(
            Modifier
                .recipePart("grip")
                .opalinePart("UI070", "grip", events = events)
                .pointerInput(Unit) {
                    var travel = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            travel = 0f
                            events.raise("drag")
                        },
                    ) { pointer, drag ->
                        pointer.consume()
                        travel += drag
                        val rows = if (height > 0) (travel / height).toInt() else 0
                        if (rows != 0) {
                            travel -= rows * height
                            move(rows)
                        }
                    }
                },
        )
        Box(Modifier.recipeFrame("content")) { content() }
    }
}
