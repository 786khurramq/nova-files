package com.nova.files

import android.app.Application
import com.nova.files.core.DeviceProfile
import com.nova.files.core.FileRepository
import com.nova.files.core.SettingsRepository
import com.nova.files.transfer.AndroidIdentityKey
import com.nova.files.transfer.HistoryStore
import com.nova.files.transfer.TransferManager
import com.nova.files.transfer.TransferService
import com.nova.files.transfer.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container – small, explicit, no reflection. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profile = DeviceProfile.detect(app)
    val settings = SettingsRepository(app, scope)
    val files = FileRepository(app)
    val identity = AndroidIdentityKey()
    val trust = TrustStore(app)
    val history = HistoryStore(app)
    val thumbs = com.nova.files.thumbs.ThumbnailLoader(app)
    val transfers = TransferManager(app, scope, settings, profile, identity, trust, history)
}

class NovaApp : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TransferService.createChannels(this)
        container.transfers.start()
    }
}
