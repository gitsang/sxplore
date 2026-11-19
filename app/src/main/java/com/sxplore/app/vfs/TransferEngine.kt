package com.sxplore.app.vfs

/**
 * Cross-VFS copy engine: recursive plan -> streamed file-by-file copy -> progress callbacks.
 * Move = copy + delete source. Fully streamed, no intermediate files on disk.
 */
object TransferEngine {

    fun copy(
        src: Vfs,
        srcPath: String,
        dst: Vfs,
        dstDir: String,
        progress: (done: Long, total: Long) -> Unit,
    ) {
        data class Item(val src: String, val dst: String, val isDir: Boolean, val size: Long)

        val items = ArrayList<Item>()
        var total = 0L

        fun plan(sp: String, dp: String) {
            val st = src.stat(sp)
            items += Item(sp, dp, st.isDir, st.size)
            if (st.isDir) {
                src.list(sp).forEach { plan(it.path, VfsPaths.join(dp, it.name)) }
            } else {
                total += st.size
            }
        }

        plan(srcPath, VfsPaths.join(dstDir, VfsPaths.name(srcPath)))

        var done = 0L
        var lastReport = 0L
        val buf = ByteArray(128 * 1024)

        for (item in items) {
            if (item.isDir) {
                dst.mkdir(item.dst)
            } else {
                src.openInput(item.src).use { input ->
                    dst.openOutput(item.dst).use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 1024 * 1024 || done == total) {
                                lastReport = done
                                progress(done, total)
                            }
                        }
                    }
                }
            }
        }
        progress(done, total)
    }
}
