package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.z1812.hyperdock.xposed.hook.HdDebug
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
    @Volatile private var launcherInstance: Any? = null
    @Volatile private var launcherClass: Class<*>? = null
    @Volatile private var freeformLaunch: Method? = null

    /** 菜单项自己走小窗时，别被「默认全屏」那条拦截又改成全屏。 */
    @Volatile private var bypassFullscreen = false
    @Volatile private var cachedLabel: String? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        HdDebug.log(TAG, "onInit start: pkg=" + param.packageName + " proc=" + processName)
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            HdDebug.log(TAG, "skip non-UI process")
            return
        }
        val loader = param.defaultClassLoader
        HdDebug.installContextProbe(module, loader)

        HdDebug.log(TAG, "resolving launch helpers")
        val util = runCatching { Class.forName(UTIL_CLASS, false, loader) }.getOrNull()
        // 按**签名**匹配，不再写死方法名（新宿主把 g0/Z 改名了，写死名字会解析成 null，
        // 表现就是"默认全屏失效、退回宿主自己的小窗"）。
        fullscreenLaunch = util?.declaredMethods?.firstOrNull { method ->
            method.parameterCount == 3 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == Intent::class.java &&
                method.parameterTypes[2] == Integer.TYPE &&
                method.returnType == Void.TYPE
        }
        smallWindowLaunch = util?.declaredMethods?.firstOrNull { method ->
            method.parameterCount == 4 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == Intent::class.java &&
                method.parameterTypes[2] == String::class.java &&
                method.parameterTypes[3] == Integer.TYPE &&
                method.returnType == Void.TYPE
        }
        runCatching {
            val all = util?.declaredMethods?.joinToString("; ") { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")->" +
                    m.returnType.simpleName
            }
            HdDebug.log(TAG, "e0 methods: " + all)
        }
        HdDebug.log(TAG, "onInit fullscreen=${fullscreenLaunch?.name} smallWindow=${smallWindowLaunch?.name}")
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
        freeformLaunch = freeform
        launcherClass = launcher
        launcherInstance = resolveLauncher()
        log(module, "freeform=${freeform.declaringClass.simpleName}.${freeform.name}")
        module.hook(freeform).intercept { chain ->
            if (bypassFullscreen) return@intercept chain.proceed()
            if (!defaultFullscreen()) return@intercept chain.proceed()
            val args = chain.args
            val icon = args.getOrNull(0) as? ImageView
            val intent = args.getOrNull(1) as? Intent
            val uid = args.getOrNull(3) as? Int ?: -1
            val started = launchFullscreen(icon?.context, intent, uid)
            HdDebug.log(TAG, "click→fullscreen=$started pkg=${intent?.component?.packageName}")
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

    /**
     * 菜单第一项（全屏）→ 文案改「小窗」、点击改走小窗启动。
     *
     * 宿主实现细节（决定了 hook 方式）：
     * - 文案：`a8.m.e(holder)` 里 `textView.setText(this.d)`，d 是**字符串资源 id**，
     *   不走 `j(context)`，所以只能等 bind 完再把 TextView 的文字覆盖掉；
     * - 点击：`a8.f.c(holder)` 覆写基类，最终调 `DockAppAnimLauncher.H(...)`（全屏）；
     * - `h()`（Intent）、`k()`（uid）声明在父类 `a8.m` 上，取方法要沿着继承链找。
     */
    private fun adoptMenuItems(module: XposedModule, menu: Any) {
        val list = menu.javaClass.declaredFields.asSequence()
            .filter { List::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                runCatching { field.isAccessible = true; field.get(menu) as? List<*> }.getOrNull()
            }
            .firstOrNull { it.isNotEmpty() }
        if (list == null) {
            HdDebug.log(TAG, "adopt: menu item list not found")
            return
        }
        val fullscreenItem = list.firstOrNull() ?: return
        val itemClass = fullscreenItem.javaClass
        val hierarchy = generateSequence(itemClass as Class<*>?) { it.superclass }.toList()

        fun find(condition: (Method) -> Boolean): Method? =
            hierarchy.asSequence()
                .flatMap { runCatching { it.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
                .firstOrNull(condition)

        val click = find { method ->
            method.parameterCount == 1 && method.returnType == Void.TYPE &&
                method.parameterTypes[0] != Context::class.java && !method.parameterTypes[0].isPrimitive
        }
        val bind = hierarchy.asSequence()
            .flatMap { runCatching { it.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
            .firstOrNull { method ->
                method.name == "e" && method.parameterCount == 1 && method.returnType == Void.TYPE
            }
        val intentGetter = find { it.parameterCount == 0 && it.returnType == Intent::class.java }
        val uidGetter = find {
            it.parameterCount == 0 && it.returnType == Integer.TYPE && it.name.length <= 2
        }
        val pkgGetter = find {
            it.parameterCount == 0 && it.returnType == String::class.java && it.name.length <= 2
        }
        if (click == null) {
            HdDebug.log(TAG, "adopt: click method not found on ${itemClass.name}")
            return
        }
        click.isAccessible = true
        menuHooked = true
        HdDebug.log(
            TAG,
            "adopt item=${itemClass.name} click=${click.name} bind=${bind?.name} " +
                "intent=${intentGetter?.name} uid=${uidGetter?.name}",
        )

        if (bind != null) {
            bind.isAccessible = true
            module.hook(bind).intercept { chain ->
                val result = chain.proceed()
                if (defaultFullscreen() && chain.thisObject?.javaClass == itemClass) {
                    runCatching { relabelSmallWindow(chain.args.getOrNull(0)) }
                }
                result
            }
        }

        module.hook(click).intercept { chain ->
            if (!defaultFullscreen()) return@intercept chain.proceed()
            val item = chain.thisObject
            val itemView = findItemView(chain.args.getOrNull(0))
            val context = itemView?.context
            val intent = intentGetter?.let { runCatching { it.invoke(item) as? Intent }.getOrNull() }
            val uid = uidGetter?.let { runCatching { it.invoke(item) as? Int }.getOrNull() } ?: -1
            val pkg = pkgGetter?.let { runCatching { it.invoke(item) as? String }.getOrNull() }
            if (context == null || intent == null) {
                HdDebug.log(TAG, "menu click: context=$context intent=$intent → 交回原生")
                chain.proceed()
            } else {
                // 宿主的小窗入口就是 DockAppAnimLauncher.C（普通点击走的那条）；
                // e0.Z 依赖 MiuiMultiWindowUtils 反射，拿不到 ActivityOptions 会静默什么都不做。
                val icon = firstImageView(itemView)
                val target = launcherInstance ?: resolveLauncher()
                val launched = runCatching {
                    bypassFullscreen = true
                    freeformLaunch?.invoke(target, icon, intent, pkg, uid, null)
                    true
                }.onFailure { HdDebug.log(TAG, "freeform invoke failed: $it") }
                    .getOrDefault(false)
                bypassFullscreen = false
                HdDebug.log(TAG, "menu click: freeform launch=$launched pkg=$pkg icon=${icon != null}")
                null
            }
        }
    }

    /** 把菜单第一项的文字换成「小窗」（宿主用资源 id 直接 setText，只能事后覆盖）。 */
    private fun relabelSmallWindow(holder: Any?) {
        val itemView = findItemView(holder) ?: return
        val text = firstTextView(itemView) ?: return
        val label = smallWindowLabel(itemView.context)
        if (text.text?.toString() != label) text.text = label
    }

    private fun firstImageView(root: View): ImageView? {
        if (root is ImageView) return root
        val group = root as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            firstImageView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun firstTextView(root: View): android.widget.TextView? {
        if (root is android.widget.TextView) return root
        val group = root as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            firstTextView(group.getChildAt(index))?.let { return it }
        }
        return null
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

    /**
     * 取 DockAppAnimLauncher 实例：先试静态工厂 `y()`，再退到同类型的静态字段。
     * 之前直接 `y()` 拿不到时传了 null，反射调用就 NPE（日志里 launch=false）。
     */
    private fun resolveLauncher(): Any? {
        launcherInstance?.let { return it }
        val type = launcherClass ?: return null
        val fromFactory = runCatching { type.getDeclaredMethod("y").invoke(null) }.getOrNull()
        if (fromFactory != null) {
            launcherInstance = fromFactory
            return fromFactory
        }
        val fromField = type.declaredFields.firstOrNull { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)
        }?.let { field -> runCatching { field.isAccessible = true; field.get(null) }.getOrNull() }
        if (fromField != null) launcherInstance = fromField
        HdDebug.log(TAG, "resolveLauncher factory=$fromFactory field=$fromField")
        return fromField
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

    /** 取 ViewHolder 的 itemView（按字段名，宿主用的是 miuix 的 RecyclerView）。 */
    private fun findItemView(holder: Any?): View? {
        val target = holder ?: return null
        val field = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { runCatching { it.declaredFields.asSequence() }.getOrDefault(emptySequence()) }
            .firstOrNull { it.name == "itemView" && View::class.java.isAssignableFrom(it.type) }
            ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(target) as? View
        }.getOrNull()
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
