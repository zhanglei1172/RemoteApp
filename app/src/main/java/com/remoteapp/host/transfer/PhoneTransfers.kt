package com.remoteapp.host.transfer

import android.content.Context
import java.io.File

object PhoneTransfers {
    private var instance: TransferStore? = null
    @Synchronized fun store(context: Context): TransferStore = instance
        ?: TransferStore(File(context.applicationContext.filesDir, "transfers")).also { instance = it }
}
