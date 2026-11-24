package com.example.fluxio.transfer.persistence

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

class PersistedUriAccess(private val resolver: ContentResolver) {
    fun retainRead(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun retainWrite(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
