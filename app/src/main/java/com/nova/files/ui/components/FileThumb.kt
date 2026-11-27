package com.nova.files.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.files.thumbs.FileFormat
import com.nova.files.thumbs.FileFormats
import com.nova.files.thumbs.FormatKind
import com.nova.files.thumbs.Thumb
import com.nova.files.thumbs.ThumbnailLoader
import com.nova.files.ui.theme.Spectrum
import java.io.File

/** Distinct icon + colour per format: level-4 fallback and loading placeholder. */
fun formatStyle(k: FormatKind): Pair<ImageVector, Color> = when (k) {
    FormatKind.APK -> Icons.Rounded.Android to Spectrum.Apps
    FormatKind.APP_BUNDLE -> Icons.Rounded.Layers to Spectrum.Apps
    FormatKind.IMAGE -> Icons.Rounded.Image to Spectrum.Photos
    FormatKind.RAW_IMAGE -> Icons.Rounded.CameraAlt to Spectrum.Photos
    FormatKind.VECTOR -> Icons.Rounded.Gesture to Color(0xFFE85CD0)
    FormatKind.VIDEO -> Icons.Rounded.Movie to Spectrum.Video
    FormatKind.AUDIO -> Icons.Rounded.MusicNote to Spectrum.Audio
    FormatKind.PDF -> Icons.Rounded.PictureAsPdf to Color(0xFFE5484D)
    FormatKind.WORD -> Icons.Rounded.Description to Color(0xFF2B7CD3)
    FormatKind.EXCEL -> Icons.Rounded.TableChart to Color(0xFF1D9F5E)
    FormatKind.POWERPOINT -> Icons.Rounded.Slideshow to Color(0xFFE8692E)
    FormatKind.TEXT -> Icons.Rounded.Subject to Color(0xFF7F8AA8)
    FormatKind.CSV -> Icons.Rounded.TableRows to Color(0xFF19B3A2)
    FormatKind.WEB -> Icons.Rounded.Html to Color(0xFFF0652F)
    FormatKind.DATA -> Icons.Rounded.DataObject to Color(0xFFE2B33C)
    FormatKind.EBOOK -> Icons.Rounded.MenuBook to Color(0xFFB8875A)
    FormatKind.ARCHIVE -> Icons.Rounded.FolderZip to Spectrum.Archive
    FormatKind.FONT -> Icons.Rounded.FontDownload to Color(0xFF9B7BFF)
    FormatKind.DESIGN -> Icons.Rounded.Palette to Color(0xFFD86CFF)
    FormatKind.DATABASE -> Icons.Rounded.Storage to Color(0xFF3FB6E0)
    FormatKind.CONFIG -> Icons.Rounded.Tune to Color(0xFF8E99B3)
    FormatKind.CODE -> Icons.Rounded.Code to Color(0xFF7FCB3C)
    FormatKind.DISK_IMAGE -> Icons.Rounded.Album to Color(0xFF6D7C99)
    FormatKind.CERTIFICATE -> Icons.Rounded.VerifiedUser to Color(0xFFD9A93B)
    FormatKind.SUBTITLE -> Icons.Rounded.ClosedCaption to Color(0xFF5AA0E6)
    FormatKind.EXECUTABLE -> Icons.Rounded.Terminal to Color(0xFFCC5B5B)
    FormatKind.TORRENT -> Icons.Rounded.Hub to Color(0xFF4FB87A)
    FormatKind.FOLDER -> Icons.Rounded.Folder to Spectrum.Folder
    FormatKind.UNKNOWN -> Icons.Rounded.InsertDriveFile to Color(0xFF8E95AB)
}

/** Format badge: gradient tile, format icon and the extension (e.g. "DOCX") when there's room. */
@Composable
fun FormatBadge(format: FileFormat, size: Dp, modifier: Modifier = Modifier) {
    val (icon, color) = formatStyle(format.kind)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        val showLabel = size >= 40.dp && format.label.isNotEmpty() && format.kind != FormatKind.FOLDER
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * if (showLabel) 0.42f else 0.52f).offset(y = if (showLabel) -size * 0.08f else 0.dp))
        if (showLabel) Text(format.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.18f).coerceIn(7f, 13f).sp,
            maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.07f))
    }
}

/** Loads (or returns cached) preview for a file; null while loading or when none exists. */
@Composable
fun rememberThumb(loader: ThumbnailLoader, file: File, isDir: Boolean, modified: Long, size: Long, sizeDp: Dp): State<Thumb?> {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceIn(48, 512)
    val format = remember(file.name, isDir) { FileFormats.of(file.name, isDir) }
    val key = remember(file.path, modified, size, px) { if (isDir || format.extractor == com.nova.files.thumbs.Extractor.NONE) null else loader.key(file, px) }
    return produceState(initialValue = key?.let { loader.peek(it) }, key) {
        if (key != null && value == null && !loader.knownMissing(key)) value = loader.load(file, px)
    }
}

/**
 * Fixed-size visual for a file: real preview when available, otherwise the format badge.
 * Same footprint in every state, so lists never jump while thumbnails load.
 */
@Composable
fun FileThumb(file: File, isDir: Boolean, thumb: Thumb?, size: Dp, selected: Boolean = false, modifier: Modifier = Modifier) {
    val format = remember(file.name, isDir) { FileFormats.of(file.name, isDir) }
    val bmp = thumb?.bitmap
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            val shape = RoundedCornerShape(size * 0.22f)
            if (thumb.fill) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            else Box(Modifier.fillMaxSize().clip(shape).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
                Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(size * 0.06f))
            }
        } else FormatBadge(format, size)
        if (selected) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.22f)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}
