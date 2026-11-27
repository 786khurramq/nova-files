package com.nova.files.cloudui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.db.AuthStatus
import com.nova.files.cloud.db.TransferKind
import com.nova.files.cloud.transfer.Endpoint
import com.nova.files.core.fmtSize
import com.nova.files.ui.AppState
import com.nova.files.ui.Tab
import com.nova.files.ui.components.Muted
import com.nova.files.ui.components.NovaButton
import com.nova.files.ui.theme.Nova
import kotlinx.coroutines.launch
import java.io.File

/** Upload selected local files to a cloud account – into the folder last opened for that account, or its root. */
@Composable
fun UploadToCloudDialog(c: AppContainer, state: AppState, files: List<File>, onDone: () -> Unit) {
    val accounts by c.cloud.accounts.observe().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val regular = files.filter { it.isFile }
    val folders = files.size - regular.size
    Dialog(onDismissRequest = onDone) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Upload ${regular.size} file(s)", style = MaterialTheme.typography.titleLarge)
                Muted(fmtSize(regular.sumOf { it.length() }) + if (folders > 0) " · $folders folder(s) skipped – folder upload comes in a later update" else "", maxLines = 3)
                val usable = accounts.filter { it.authStatus == AuthStatus.OK && c.cloud.accounts.provider(ProviderId.valueOf(it.providerId)) != null }
                if (usable.isEmpty()) Muted("No connected cloud accounts. Add one in the Cloud tab.")
                usable.forEach { a ->
                    val pid = ProviderId.valueOf(a.providerId)
                    val (folderId, folderName) = state.cloudFolders[a.id] ?: (null to if (pid == ProviderId.GOOGLE_DRIVE) "My Drive" else pid.label)
                    NovaButton("${a.displayName} › $folderName", providerIcon(pid), Modifier.fillMaxWidth(), enabled = regular.isNotEmpty()) {
                        scope.launch {
                            regular.forEach { f ->
                                c.cloud.queue.enqueue(TransferKind.COPY, Endpoint.Local(f.path), Endpoint.Cloud(a.id, pid.name, parentId = folderId, parentName = folderName),
                                    f.name, f.length(), ConflictPolicy.KEEP_BOTH)
                            }
                            c.cloud.startQueued(ctx)
                            c.transfers.toast("Uploading ${regular.size} file(s) to ${pid.label} › $folderName")
                            state.cloudAccount = null; state.tab = Tab.CLOUD
                            onDone()
                        }
                    }
                }
                Muted("To pick another destination folder, open it in the Cloud tab first.", maxLines = 2)
                NovaButton("Cancel", null, Modifier.fillMaxWidth(), onClick = onDone)
            }
        }
    }
}
