package io.github.z1812.hyperdock.xposed.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
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
    @Volatile private var context: Context? = null
    @Volatile private var probeInstalled = false

    /**
     * `onPackageLoaded` 早于 Application 创建，那会儿拿不到 Context，
     * 所以挂一个 Application.attach 探针，进程一起来就记住 Context 并落第一行日志。
     */
    fun installContextProbe(module: XposedModule, loader: ClassLoader) {
        if (probeInstalled) return
        probeInstalled = true
        runCatching {
            val appClass = Class.forName("android.app.Application", false, loader)
            val attach = appClass.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    context = ctx
                    log("HdDebug", "Application attached, log → " + File(ctx.filesDir, FILE_NAME).absolutePath)
                }
                result
            }
        }.onFailure { failed = false; log("HdDebug", "context probe failed: $it") }
    }

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
        val context = context ?: appContext() ?: return null
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
