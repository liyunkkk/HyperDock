package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import java.lang.reflect.Method

/**
 * 侧边栏「点应用」的默认打开方式 + 长按菜单第一项。
 *
 * 宿主链路（安全中心 13.5.9）：
 * - 点应用（侧边栏条目与全部应用面板都走这里）→
 *   `DockAppAnimLauncher.C(ImageView, Intent, pkg, uid, callback)`（日志 "launchAppInFreeform" = 小窗）；
 *   全屏是 `com.miui.gamebooster.utils.e0.g0(context, intent, uid)`
 *   （`ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN)`），
 *   小窗是 `e0.Z(context, intent, null, uid)`。
 * - 长按菜单 `com.miui.dock.drag.DockShortCutMenu`：`c()` 里造两项，第一项是「全屏」模型
 *   （类名混淆，运行时从菜单自己的列表里按结构认出来），点它 → `DockAppAnimLauncher.H(...)`；
 *   第二项「分屏」→ `.L(...)`。菜单项文案由模型自己的 `j(Context)` 提供。
 *
 * 于是：默认全屏 = 把 `C` 拦下来改走 `g0`；菜单第一项跟着反过来——默认全屏时它显示
 * 「小窗」并改走 `Z`，默认小窗时保持原生的「全屏」。
 */
object SidebarAppLaunchHook : BaseHook() {

    private const val TAG = "HyperDock[Launch]"
    private const val LAUNCHER_CLASS = "com.miui.gamebooster.windowmanager.newbox.DockAppAnimLauncher"
    private const val UTIL_CLASS = "com.miui.gamebooster.utils.e0"
    private const val MENU_CLASS = "com.miui.dock.drag.DockShortCutMenu"
    private const val MODULE_PACKAGE = "io.github.z1812.hyperdock"
    private const val STRING_SMALL_WINDOW = "sidebar_open_small_window"

    @Volatile private var fullscreenLaunch: Method? = null
    @Volatile private var smallWindowLaunch: Method? = null
    @Volatile private var menuHooked = false
    @Volatile private var cachedLabel: String? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }
        val loader = param.defaultClassLoader

        val util = runCatching { Class.forName(UTIL_CLASS, false, loader) }.getOrNull()
        fullscreenLaunch = util?.declaredMethods?.firstOrNull { method ->
            method.name == "g0" && method.parameterCount == 3 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == Intent::class.java
        }
        smallWindowLaunch = util?.declaredMethods?.firstOrNull { method ->
            method.name == "Z" && method.parameterCount == 4 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == Intent::class.java
        }
        if (fullscreenLaunch == null || smallWindowLaunch == null) {
            logWarn(module, "launch helpers not found; open-mode switch disabled")
            return
        }

        val launcher = runCatching { Class.forName(LAUNCHER_CLASS, false, loader) }.getOrNull()
        val freeform = launcher?.declaredMethods?.firstOrNull { method ->
            method.parameterCount == 5 &&
                method.parameterTypes[0] == ImageView::class.java &&
                method.parameterTypes[1] == Intent::class.java &&
                method.parameterTypes[2] == String::class.java &&
                method.parameterTypes[3] == Integer.TYPE
        }
        if (freeform == null) {
            logWarn(module, "freeform launch entry not found")
            return
        }
        freeform.isAccessible = true
        log(module, "freeform=${freeform.declaringClass.simpleName}.${freeform.name}")
        module.hook(freeform).intercept { chain ->
            if (!defaultFullscreen()) return@intercept chain.proceed()
            val args = chain.args
            val icon = args.getOrNull(0) as? ImageView
            val intent = args.getOrNull(1) as? Intent
            val uid = args.getOrNull(3) as? Int ?: -1
            val started = launchFullscreen(icon?.context, intent, uid)
            if (started) null else chain.proceed()
        }

        hookShortcutMenu(module, loader)
    }

    /** 长按菜单：等菜单把列表造好后，认出「全屏」那一项并接管它的文案与动作。 */
    private fun hookShortcutMenu(module: XposedModule, loader: ClassLoader) {
        val menuClass = runCatching { Class.forName(MENU_CLASS, false, loader) }.getOrNull() ?: return
        menuClass.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == Void.TYPE }
            .forEach { builder ->
                builder.isAccessible = true
                module.hook(builder).intercept { chain ->
                    val result = chain.proceed()
                    if (!menuHooked) {
                        runCatching { adoptMenuItems(module, chain.thisObject) }
                            .onFailure { logWarn(module, "adopt menu failed: $it") }
                    }
                    result
                }
            }
    }

    /** 菜单第一项（全屏）→ 文案改「小窗」、点击改走小窗启动。 */
    private fun adoptMenuItems(module: XposedModule, menu: Any) {
        val list = menu.javaClass.declaredFields.asSequence()
            .filter { List::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                runCatching { field.isAccessible = true; field.get(menu) as? List<*> }.getOrNull()
            }
            .firstOrNull { it.isNotEmpty() } ?: return
        val fullscreenItem = list.firstOrNull() ?: return
        val itemClass = fullscreenItem.javaClass
        val click = itemClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 && RecyclerView.ViewHolder::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        val label = itemClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 && method.parameterTypes[0] == Context::class.java &&
                method.returnType == String::class.java
        }
        val intentGetter = itemClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType == Intent::class.java
        }
        val uidGetter = itemClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType == Integer.TYPE && method.name.length <= 2
        }
        if (click == null || label == null) {
            logWarn(module, "menu item structure unexpected: click=$click label=$label")
            return
        }
        click.isAccessible = true
        label.isAccessible = true
        menuHooked = true
        log(module, "menu item=${itemClass.name} click=${click.name} label=${label.name}")

        module.hook(label).intercept { chain ->
            if (!defaultFullscreen()) return@intercept chain.proceed()
            val context = chain.args.getOrNull(0) as? Context
            if (context == null) chain.proceed() else smallWindowLabel(context)
        }

        module.hook(click).intercept { chain ->
            if (!defaultFullscreen()) return@intercept chain.proceed()
            val holder = chain.args.getOrNull(0) as? RecyclerView.ViewHolder
            val context = holder?.itemView?.context
            val item = chain.thisObject
            val intent = intentGetter?.let { runCatching { it.invoke(item) as? Intent }.getOrNull() }
            val uid = uidGetter?.let { runCatching { it.invoke(item) as? Int ?: -1 }.getOrNull() } ?: -1
            if (context == null || intent == null) {
                chain.proceed()
            } else {
                val ok = runCatching { smallWindowLaunch?.invoke(null, context, intent, null, uid) }
                    .isSuccess
                log(module, "menu small-window launch=$ok")
                null
            }
        }
    }

    private fun defaultFullscreen(): Boolean =
        ConfigManager.getString(PrefKeys.SIDEBAR_APP_OPEN_MODE, PrefKeys.APP_OPEN_FULLSCREEN) !=
            PrefKeys.APP_OPEN_SMALL_WINDOW

    private fun launchFullscreen(context: Context?, intent: Intent?, uid: Int): Boolean {
        if (context == null || intent == null) return false
        return runCatching {
            fullscreenLaunch?.invoke(null, context, intent, uid)
            true
        }.getOrDefault(false)
    }

    /** 「小窗」文案优先用模块自己的字符串资源（宿主没有单独的小窗文案）。 */
    private fun smallWindowLabel(context: Context): String {
        cachedLabel?.let { return it }
        val resolved = runCatching {
            val moduleContext = context.createPackageContext(MODULE_PACKAGE, 0)
            val id = moduleContext.resources.getIdentifier(STRING_SMALL_WINDOW, "string", MODULE_PACKAGE)
            if (id != 0) moduleContext.getString(id) else null
        }.getOrNull()
        val label = resolved ?: "小窗"
        cachedLabel = label
        return label
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
