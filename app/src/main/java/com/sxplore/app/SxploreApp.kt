package com.sxplore.app

import android.app.Application
import android.content.Context
import com.sxplore.app.data.ConnectionStore
import com.sxplore.app.proxy.StreamProxy
import com.sxplore.app.vfs.LocalVfs
import com.sxplore.app.vfs.SmbConnectionInfo
import com.sxplore.app.vfs.SmbVfs

class SxploreApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Lightweight DI container: global proxy + connection store + VFS factories. */
class AppContainer(context: Context) {

    val connectionStore = ConnectionStore(context)

    val proxy = StreamProxy().apply {
        runCatching { startProxy() }
    }

    fun newLocalVfs(): LocalVfs = LocalVfs()

    fun newSmbVfs(info: SmbConnectionInfo): SmbVfs = SmbVfs(info)
}
