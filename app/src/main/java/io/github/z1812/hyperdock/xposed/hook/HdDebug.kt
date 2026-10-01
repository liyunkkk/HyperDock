package io.github.z1812.hyperdock.xposed.hook

import android.content.Context
import java.io.File

/**
 * 落盘调试日志。
 *
 * 这台设备的 logd 是坏的（logcat 只有旧日志），模块日志看不到，所以把关键路径
 * 写进宿主进程自己的 filesDir（`/data/data/com.miui.securitycenter/files/hyperdock-debug.log`），
 * 有 root 就能读。文件超过 64KB 自动截断重来。
 */
internal object HdDebug {

    private const val FILE_NAME = "hyperdock-debug.log"
    private const val MAX_BYTES = 64 * 1024

    @Volatile private var file: File? = null
    @Volatile private var failed = false

    fun log(tag: String, message: String) {
        if (failed) return
        val target = resolve() ?: return
        runCatching {
            if (target.length() > MAX_BYTES) target.delete()
            target.appendText("${System.currentTimeMillis()} [$tag] $message\n")
        }.onFailure { failed = true }
    }

    private fun resolve(): File? {
        file?.let { return it }
        val context = appContext() ?: return null
        val created = File(context.filesDir, FILE_NAME)
        file = created
        return created
    }

    fun appContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
        val method = thread.getDeclaredMethod("currentApplication")
        method.isAccessible = true
        method.invoke(null) as? Context
    }.getOrNull()
}
