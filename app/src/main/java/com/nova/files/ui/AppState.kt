package com.nova.files.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.nova.files.transfer.protocol.SendItem
import java.io.File

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.SpaceDashboard),
    FILES("Files", Icons.Rounded.Folder),
    TRANSFER("Transfer", Icons.Rounded.WifiTethering),
    CLEANER("Cleaner", Icons.Rounded.CleaningServices),
    APKS("APKs", Icons.Rounded.Android),
    CLOUD("Cloud", Icons.Rounded.Cloud),
}

data class PendingSend(val items: List<SendItem>, val sources: List<String>, val label: String)

data class Clipboard(val files: List<File>, val move: Boolean)

/** UI-level state shared between screens (not persisted). */
class AppState {
    var tab by mutableStateOf(Tab.HOME)
    var showSettings by mutableStateOf(false)
    var pendingSend by mutableStateOf<PendingSend?>(null)
    var clipboard by mutableStateOf<Clipboard?>(null)
    /** Request for the Files screen to open a directory. */
    var openDir by mutableStateOf<File?>(null)

    fun openFolder(dir: File) { openDir = dir; tab = Tab.FILES; showSettings = false }
}
