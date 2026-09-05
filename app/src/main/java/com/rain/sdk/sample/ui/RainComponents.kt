package com.rain.sdk.sample.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rain.sdk.sample.R
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainType

/*
 * The sample's component kit, a Compose port of the Rain design system's `components.css`: white
 * canvas, hairline neutral borders on all four sides, 20dp cards, pill buttons that go pink on
 * press, 4dp inputs with a pink focus ring, and Phosphor icons in the pink container. Sentence case
 * everywhere; no emoji.
 */

private const val PRESS_MS = 200

private val PillShape = CircleShape
private val CardShape = RoundedCornerShape(RainRadius.Card)
private val SmallShape = RoundedCornerShape(RainRadius.Small)

// ---------------------------------------------------------------------------------------------
// Screen scaffolding
// ---------------------------------------------------------------------------------------------

/** Full-height white canvas: 8dp top, 16dp sides, 32dp bottom, 24dp between sections. */
@Composable
fun RainScreen(
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RainColors.Canvas)
            .padding(innerPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

/** Wordmark on the left, environment badge on the right. */
@Composable
fun RainHomeHeader(environmentLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.rain_wordmark),
            contentDescription = "Rain",
            modifier = Modifier.width(66.dp).height(20.dp),
        )
        RainBadge(environmentLabel)
    }
}

/** Back affordance for feature screens: a round 44dp arrow target and the destination's name. */
@Composable
fun RainBackHeader(onBack: () -> Unit, destination: String = "Home") {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).offset(x = (-12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RainIconButton(icon = R.drawable.ic_arrow_left, contentDescription = "Back", onClick = onBack)
        Text(destination, style = RainType.Label)
    }
}

/** Screen title (32 Semibold) with an optional descriptor in muted body. */
@Composable
fun RainTitleBlock(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = RainType.Title)
        if (subtitle != null) Text(subtitle, style = RainType.BodyMuted)
    }
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

@Composable
fun RainLabel(text: String, modifier: Modifier = Modifier) = Text(text, style = RainType.Label, modifier = modifier)

@Composable
fun RainStrong(text: String, modifier: Modifier = Modifier) = Text(text, style = RainType.Strong, modifier = modifier)

@Composable
fun RainMuted(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) =
    Text(text, style = RainType.BodyMuted, modifier = modifier, textAlign = textAlign)

/** A field label over its value, e.g. "USDC contract" over an address. */
@Composable
fun RainKeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        RainLabel(label)
        Text(value, style = RainType.Body)
    }
}

/** A headline figure with its unit: `1,250.00 USDC`. */
@Composable
fun RainAmount(value: String, unit: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = RainType.Title, modifier = Modifier.alignByBaseline())
        Text(unit, style = RainType.Label, modifier = Modifier.alignByBaseline())
    }
}

/** Underlined ink text that turns pink while pressed; for transaction hashes and addresses. */
@Composable
fun RainLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color by animateColorAsState(
        if (pressed) RainColors.Pink else RainColors.Ink,
        tween(PRESS_MS),
        label = "link",
    )
    Text(
        text = text,
        style = RainType.Body.copy(color = color, textDecoration = TextDecoration.Underline),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}

// ---------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------

/** White card: hairline subtle border on all sides, 20dp radius, 24dp padding, 16dp row gap. */
@Composable
fun RainCard(
    modifier: Modifier = Modifier,
    gap: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Surface, CardShape)
            .border(1.dp, RainColors.BorderSubtle, CardShape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Quiet canvas-tinted panel (no border) for status lines, notes, and empty states. */
@Composable
fun RainPanel(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.SurfaceSubtle, CardShape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Title plus explanatory copy in a quiet panel. */
@Composable
fun RainNote(title: String, body: String, modifier: Modifier = Modifier) {
    RainPanel(modifier) {
        RainStrong(title)
        RainMuted(body)
    }
}

/** Failure copy: a danger badge and the message in ink. Colour stays on the badge, not the panel. */
@Composable
fun RainErrorPanel(message: String, modifier: Modifier = Modifier) {
    RainPanel(modifier, gap = 8.dp) {
        RainBadge("Error", RainBadgeTone.Danger)
        Text(message, style = RainType.Body)
    }
}

/** Hairline divider inside a card. */
@Composable
fun RainDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RainColors.BorderSubtle))
}

/** Space-between row: title on the left, trailing content on the right. */
@Composable
fun RainRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Badges, dots, icon tiles
// ---------------------------------------------------------------------------------------------

enum class RainBadgeTone { Neutral, Success, Danger, Outline, Pink }

/** 12sp product-chrome badge: 4dp radius, 4x8 padding. */
@Composable
fun RainBadge(text: String, tone: RainBadgeTone = RainBadgeTone.Neutral, modifier: Modifier = Modifier) {
    val (background, foreground) = when (tone) {
        RainBadgeTone.Neutral -> RainColors.Gray15 to RainColors.Ink
        RainBadgeTone.Success -> RainColors.SuccessTint to RainColors.Success
        RainBadgeTone.Danger -> RainColors.DangerTint to RainColors.Danger
        RainBadgeTone.Outline -> Color.Transparent to RainColors.TextMuted
        RainBadgeTone.Pink -> RainColors.Pink to RainColors.White
    }
    val border = if (tone == RainBadgeTone.Outline) {
        Modifier.border(1.dp, RainColors.BorderStrong, SmallShape)
    } else {
        Modifier
    }
    Box(
        modifier
            .background(background, SmallShape)
            .then(border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = RainType.Badge.copy(color = foreground), maxLines = 1)
    }
}

/** 8dp status dot in a sanctioned state colour. */
@Composable
fun RainStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** The Rain icon container: 48dp rounded square, soft pink gradient, white line icon. */
@Composable
fun RainIconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { 48.dp.toPx() }
    // CSS `160deg`: pale at the upper-left flowing to blush at the lower-right.
    val gradient = remember(sizePx) {
        Brush.linearGradient(
            colors = listOf(RainColors.PinkPale, RainColors.PinkBlush),
            start = Offset(sizePx * 0.33f, 0f),
            end = Offset(sizePx * 0.67f, sizePx),
        )
    }
    Box(
        modifier = modifier.size(48.dp).background(gradient, SmallShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = RainColors.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

enum class RainButtonStyle {
    /** Ink fill, white text; pink while pressed. The one primary action on a screen. */
    Primary,

    /** Hairline strong border, ink text; fills ink while pressed. */
    Secondary,

    /** Text only; pink while pressed. */
    Ghost,
}

/**
 * Pill button. Callers size it with [modifier] (`fillMaxWidth()` or `weight(1f)`); primaries are
 * 48dp tall, the rest 44dp. Press is a colour settle, not a ripple or a bounce. Disabled is muted
 * grey with no pink.
 */
@Composable
fun RainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RainButtonStyle = RainButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    @DrawableRes icon: Int? = null,
    height: Dp = if (style == RainButtonStyle.Primary) 48.dp else 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading

    val targetBackground = when {
        !active -> if (style == RainButtonStyle.Primary) RainColors.Gray15 else Color.Transparent
        style == RainButtonStyle.Primary -> if (pressed) RainColors.Pink else RainColors.Ink
        style == RainButtonStyle.Secondary -> if (pressed) RainColors.Ink else Color.Transparent
        else -> Color.Transparent
    }
    val targetForeground = when {
        !active -> RainColors.TextSubtle
        style == RainButtonStyle.Primary -> RainColors.White
        style == RainButtonStyle.Secondary -> if (pressed) RainColors.White else RainColors.Ink
        else -> if (pressed) RainColors.Pink else RainColors.Ink
    }
    val targetBorder = when (style) {
        RainButtonStyle.Secondary -> when {
            !active -> RainColors.BorderSubtle
            pressed -> RainColors.Ink
            else -> RainColors.BorderStrong
        }
        else -> Color.Transparent
    }
    val background by animateColorAsState(targetBackground, tween(PRESS_MS), label = "buttonBg")
    val foreground by animateColorAsState(targetForeground, tween(PRESS_MS), label = "buttonFg")
    val border by animateColorAsState(targetBorder, tween(PRESS_MS), label = "buttonBorder")

    Row(
        modifier = modifier
            .height(height)
            .clip(PillShape)
            .background(background)
            .border(1.dp, border, PillShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (style == RainButtonStyle.Ghost) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            RainSpinner(color = foreground)
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text, style = RainType.Strong.copy(color = foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round 44dp icon-only target; the glyph turns pink while pressed. */
@Composable
fun RainIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(
        when {
            !enabled -> RainColors.TextSubtle
            pressed -> RainColors.Pink
            else -> RainColors.Ink
        },
        tween(PRESS_MS),
        label = "iconTint",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Inline text action (e.g. a card's "Refresh"): Semibold ink, pink while pressed. */
@Composable
fun RainTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color by animateColorAsState(
        when {
            !enabled -> RainColors.TextSubtle
            pressed -> RainColors.Pink
            else -> RainColors.Ink
        },
        tween(PRESS_MS),
        label = "textAction",
    )
    Row(
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = RainType.Strong.copy(color = color))
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Small ink progress ring; sized to sit inside a button. */
@Composable
fun RainSpinner(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = RainColors.Ink) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        trackColor = Color.Transparent,
        strokeWidth = 2.dp,
    )
}

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

/**
 * Single-line text input: 4dp radius, hairline border, 11x14 padding, pink border plus soft halo
 * on focus. Errors are reported next to the field in helper copy, never as a coloured border.
 */
@Composable
fun RainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        when {
            !enabled -> RainColors.BorderSubtle
            focused -> RainColors.Focus
            else -> RainColors.Border
        },
        tween(PRESS_MS),
        label = "fieldBorder",
    )
    val haloPx = with(LocalDensity.current) { 3.dp.toPx() }
    val haloRadiusPx = with(LocalDensity.current) { (RainRadius.Small + 3.dp).toPx() }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = RainType.Body.copy(color = if (enabled) RainColors.Ink else RainColors.TextMuted),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(RainColors.Pink),
        decorationBox = { innerField ->
            Box(
                modifier = Modifier
                    .drawBehind {
                        if (focused && enabled) {
                            drawRoundRect(
                                color = RainColors.FocusHalo,
                                topLeft = Offset(-haloPx, -haloPx),
                                size = Size(size.width + haloPx * 2, size.height + haloPx * 2),
                                cornerRadius = CornerRadius(haloRadiusPx),
                            )
                        }
                    }
                    .background(if (enabled) RainColors.Surface else RainColors.SurfaceDisabled, SmallShape)
                    .border(1.dp, borderColor, SmallShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = RainType.Body.copy(color = RainColors.TextSubtle), maxLines = 1)
                }
                innerField()
            }
        },
    )
}

/** Label, input, and optional helper copy (muted, or danger for a validation problem). */
@Composable
fun RainField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    helper: String? = null,
    helperIsError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel(label)
        RainTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            keyboardType = keyboardType,
        )
        if (helper != null) {
            Text(
                helper,
                style = if (helperIsError) RainType.Body.copy(color = RainColors.Danger) else RainType.BodyMuted,
            )
        }
    }
}

/** Input-shaped trigger showing the current choice and a caret; pairs with a dropdown menu. */
@Composable
fun RainDropdownField(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val borderColor by animateColorAsState(
        when {
            !enabled -> RainColors.BorderSubtle
            pressed -> RainColors.Focus
            else -> RainColors.Border
        },
        tween(PRESS_MS),
        label = "dropdownBorder",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Surface, SmallShape)
            .border(1.dp, borderColor, SmallShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = RainType.Body.copy(color = if (enabled) RainColors.Ink else RainColors.TextMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_caret_down),
            contentDescription = null,
            tint = if (enabled) RainColors.Ink else RainColors.TextSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Pill segmented control on a cloud track: the selected segment is an ink pill with white text. */
@Composable
fun RainSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Track, PillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                if (selected) RainColors.Ink else Color.Transparent,
                tween(PRESS_MS),
                label = "segment",
            )
            val foreground = when {
                selected -> RainColors.White
                !enabled -> RainColors.TextSubtle
                else -> RainColors.TextMuted
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(PillShape)
                    .background(background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = RainType.Strong.copy(color = foreground),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 44x24 toggle: ink track when on, mist when off, white knob. */
@Composable
fun RainToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(
        when {
            !enabled -> RainColors.Gray15
            checked -> RainColors.Ink
            else -> RainColors.Gray20
        },
        tween(PRESS_MS),
        label = "toggleTrack",
    )
    val knobOffset by animateDpAsState(if (checked) 22.dp else 2.dp, tween(PRESS_MS), label = "toggleKnob")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(PillShape)
            .background(track)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(20.dp)
                .background(RainColors.White, CircleShape),
        )
    }
}

/**
 * Selectable row in a list of choices (e.g. which token to withdraw): a 20dp bordered row that
 * fills canvas-grey and shows a check when selected.
 */
@Composable
fun RainOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (selected || pressed) RainColors.SurfaceSubtle else RainColors.Surface,
        tween(PRESS_MS),
        label = "optionBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, CardShape)
            .border(1.dp, RainColors.BorderSubtle, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RainStrong(title)
            if (subtitle != null) RainMuted(subtitle)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = "Selected",
                tint = RainColors.Ink,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Home-grid tile: icon container over a Semibold label; the border firms up while pressed. */
@Composable
fun RainFeatureTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border by animateColorAsState(
        if (pressed) RainColors.BorderStrong else RainColors.BorderSubtle,
        tween(PRESS_MS),
        label = "tileBorder",
    )
    Column(
        modifier = modifier
            .background(RainColors.Surface, CardShape)
            .border(1.dp, border, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RainIconTile(icon)
        RainStrong(label)
    }
}
