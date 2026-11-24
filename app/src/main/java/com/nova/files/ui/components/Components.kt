package com.nova.files.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nova.files.ui.theme.Nova

/**
 * Card that is fully usable by touch and D-pad: visible focus ring + gentle scale on TV,
 * long-press support on touch screens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Nova.colors
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && Nova.isTv) 1.035f else 1f, tween(140), label = "s")
    val ring = accent ?: c.accent
    val border = when {
        focused -> BorderStroke(if (Nova.isTv) 3.dp else 2.dp, ring)
        selected -> BorderStroke(2.dp, ring.copy(alpha = 0.8f))
        else -> BorderStroke(1.dp, c.outline.copy(alpha = 0.6f))
    }
    val bg = when {
        selected -> ring.copy(alpha = if (c.dark) 0.16f else 0.10f)
        focused -> c.surfaceHigh
        else -> c.surface
    }
    val base = modifier.scale(scale).clip(shape)
    val clickMod = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    else Modifier
    Column(
        base.background(bg).border(border, shape).then(clickMod)
            .then(if (focused && c.glow > 0f) Modifier.drawBehind { drawRoundRect(ring.copy(alpha = 0.10f * c.glow * 3), cornerRadius = CornerRadius(20.dp.toPx())) } else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Focus-aware pill button for toolbars and actions; works with D-pad OK. */
@Composable
fun NovaButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    color: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Nova.colors
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val tint = color ?: c.accent
    val scale by animateFloatAsState(if (focused && Nova.isTv) 1.06f else 1f, tween(120), label = "b")
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick, enabled = enabled, interactionSource = src, shape = shape,
        color = when { primary -> tint; focused -> c.surfaceHigh; else -> c.surfaceHigh.copy(alpha = 0.7f) },
        contentColor = if (primary) (if (color != null) Color.White else c.onAccent) else c.text,
        border = if (focused) BorderStroke(if (Nova.isTv) 3.dp else 2.dp, if (primary) c.text else tint) else null,
        modifier = modifier.scale(scale).heightIn(min = if (Nova.isTv) 48.dp else 42.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = if (primary) LocalContentColor.current else tint); if (text.isNotEmpty()) Spacer(Modifier.width(8.dp)) }
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

@Composable
fun NovaIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color? = null, onClick: () -> Unit) {
    val c = Nova.colors
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Surface(onClick = onClick, interactionSource = src, shape = CircleShape,
        color = if (focused) c.surfaceHigh else Color.Transparent,
        border = if (focused) BorderStroke(if (Nova.isTv) 3.dp else 2.dp, c.accent) else null,
        modifier = modifier.size(if (Nova.isTv) 52.dp else 44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint ?: c.text, modifier = Modifier.size(22.dp)) }
    }
}


@Composable
fun CategoryBadge(icon: ImageVector, color: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.52f)) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Nova.colors.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun GradientProgress(fraction: Float, modifier: Modifier = Modifier, brush: Brush? = null, height: Dp = 8.dp) {
    val c = Nova.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250), label = "p")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(c.outline.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(brush ?: c.accentBrush))
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Nova.colors.muted, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Nova.colors.muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted(body, maxLines = 4)
    }
}
