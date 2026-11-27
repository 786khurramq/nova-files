package com.nova.files.cloudui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.db.AccountEntity
import com.nova.files.cloud.db.AuthStatus
import com.nova.files.ui.components.Muted
import com.nova.files.ui.components.NovaButton
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum

/** Home shortcut chooser: pick one of several accounts, or sign in when none is connected. */
@Composable
fun CloudAccountPicker(provider: ProviderId, accounts: List<AccountEntity>, onOpen: (String) -> Unit, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(provider.label, style = MaterialTheme.typography.titleLarge)
                if (accounts.isEmpty()) {
                    Muted("No ${provider.label} account connected yet. Sign in on ${provider.label}'s own page – Nova Files never sees your password.", maxLines = 3)
                    NovaButton("Sign in to ${provider.label}", Icons.Rounded.Login, Modifier.fillMaxWidth(), primary = true, color = Spectrum.Cloud, onClick = onSignIn)
                } else {
                    Muted("Choose an account", maxLines = 1)
                    accounts.forEach { a ->
                        NovaButton(a.displayName + (a.email?.let { " · $it" } ?: "") + if (a.authStatus != AuthStatus.OK) " (reconnect needed)" else "",
                            providerIcon(provider), Modifier.fillMaxWidth()) { onOpen(a.id) }
                    }
                    NovaButton("Add another ${provider.label} account", Icons.Rounded.Add, Modifier.fillMaxWidth(), onClick = onSignIn)
                }
                NovaButton("Cancel", null, Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}
