package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import java.lang.ref.WeakHashMap
import java.lang.reflect.Method
import java.util.Collections

/**
 * 收起态小横条：隐藏 + 触摸面积加长。
 *
 * 宿主结构（安全中心 13.5.9，`com.miui.securitycenter:ui` 进程）：
 * - `com.miui.dock.sidebar.p` 是 SidebarWrapper，构造函数里 inflate 侧边栏布局、
 *   取到小横条 `com.miui.dock.sidebar.RegionSamplingImageView`（继承 ImageView），
 *   并在构造函数里 `setImageDrawable(...)`（全生命周期只设这一次）；
 * - 收起态真正接收拖拽的是 `p.w()` 返回的 cover view（日志 tag "SidebarCoverView"，
 *   自身实现 View.OnTouchListener），它的窗口参数由 DockWindowManager 的
 *   `w0(p, boolean)` 生成：`width = dimen`、`height = dimen`、`y = H0()`；
 * - 全面屏手势下（`Settings.Global miui_fsgesture_state == 1`）cover view 的
 *   onTouch 在 ACTION_DOWN 就 `return false`，原生不处理时把事件交给小横条
 *   （`p.h0(event)` == `handle.dispatchTouchEvent(event)`）。
 *
 * 因此这里做三件事：
 * 1. 隐藏：把小横条的 drawable 置空。不动 visibility —— 宿主父类 `base.a` 的
 *    setVisibility 带 setActive 门闩，且拖动逻辑会读小横条的 getHeight()，
 *    置空 drawable 不改变布局尺寸，最安全。
 * 2. 加长：把 cover view 窗口的 height 乘上倍率。y 保持宿主给的值、不参与居中，
 *    避免与 dock 自己的移动逻辑互抢；原始高度按 wrapper 实例只记一次，
 *    防止重复放大叠加。
 * 3. 触摸转发：给 cover view 的 OnTouchListener 包壳，原生返回 false 时把事件
 *    转发给小横条，使放大后的整片区域都能拖动。
 */
object SidebarHandleHook : BaseHook() {

    private const val TAG = "HyperDock[Handle]"

    /** 小横条类名：被布局 XML 引用，不会混淆。 */
    private const val HANDLE_CLASS = "com.miui.dock.sidebar.RegionSamplingImageView"

    /** 触摸区域倍率的合法上限，防止误填导致整屏被吃掉。 */
    private const val MAX_SCALE = 8

    @Volatile private var coverAccessor: Method? = null
    @Volatile private var handleAccessor: Method? = null

    /** wrapper 实例 -> 首次见到的窗口高度。 */
    private val baseHeights = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    /** wrapper 实例 -> 是否已经包过触摸转发。 */
    private val touchWrapped = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** wrapper 实例 -> 当前是否处于「原生不处理、由我们转发」的手势中。 */
    private val forwarding = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** 保留 wrapper 弱引用，配置变化时重新应用。 */
    private val wrappers = Collections.synchronizedList(mutableListOf<java.lang.ref.WeakReference<Any>>())

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }

        val loader = param.defaultClassLoader
        val openMethod = SidebarExpandDexDiscovery.findOpenMethod(loader)
        if (openMethod == null) {
            logWarn(module, "normal sidebar open method unavailable")
            return
        }
        val wrapperClass = openMethod.parameterTypes[0]
        val handleClass = runCatching { Class.forName(HANDLE_CLASS, false, loader) }.getOrNull()
        if (handleClass == null) {
            logWarn(module, "handle view class not found")
            return
        }

        handleAccessor = wrapperClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && handleClass.isAssignableFrom(method.returnType)
        }?.apply { isAccessible = true }
        coverAccessor = findCoverViewAccessor(wrapperClass, handleClass)
        log(
            module,
            "wrapper=${wrapperClass.name} handle=${handleAccessor?.name} cover=${coverAccessor?.name}",
        )

        // 构造后立刻应用一次：隐藏要尽早，避免用户看到一闪。
        wrapperClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                remember(chain.thisObject)
                applyTo(module, chain.thisObject)
                result
            }
        }

        // 每次侧边栏打开时再应用一次：此时窗口参数已由宿主建好。
        openMethod.isAccessible = true
        module.hook(openMethod).intercept { chain ->
            val result = chain.proceed()
            chain.args.firstOrNull()?.let { wrapper ->
                remember(wrapper)
                applyTo(module, wrapper)
            }
            result
        }
    }

    override fun onConfigChanged() {
        val current = module ?: return
        val alive = synchronized(wrappers) { wrappers.mapNotNull { it.get() } }
        alive.forEach { wrapper ->
            runCatching { applyTo(current, wrapper) }
        }
    }

    private var module: XposedModule? = null

    private fun remember(wrapper: Any) {
        val list = synchronized(wrappers) {
            wrappers.removeAll { it.get() == null }
            wrappers
        }
        if (list.none { it.get() === wrapper }) list.add(java.lang.ref.WeakReference(wrapper))
    }

    /**
     * 找 cover view 的取值方法：无参、返回 View 子类、该子类自己实现 OnTouchListener，
     * 并且持有 wrapper 类型的字段（宿主里 `com.miui.dock.sidebar.e` 正是如此）。
     * 结构特征比方法名稳，宿主改混淆名也能命中。
     */
    private fun findCoverViewAccessor(wrapperClass: Class<*>, handleClass: Class<*>): Method? {
        val candidates = wrapperClass.declaredMethods.filter { method ->
            method.parameterCount == 0 &&
                View::class.java.isAssignableFrom(method.returnType) &&
                View.OnTouchListener::class.java.isAssignableFrom(method.returnType) &&
                !handleClass.isAssignableFrom(method.returnType)
        }
        val strict = candidates.firstOrNull { method ->
            method.returnType.declaredFields.any { wrapperClass.isAssignableFrom(it.type) }
        }
        return (strict ?: candidates.firstOrNull())?.apply { isAccessible = true }
    }

    private fun applyTo(module: XposedModule, wrapper: Any) {
        this.module = module
        val hide = ConfigManager.getBoolean(PrefKeys.SIDEBAR_HANDLE_HIDDEN, false)
        val scale = scaleFactor()
        if (!hide && scale <= 1) return

        runCatching {
            val cover = coverAccessor?.invoke(wrapper) as? View
            val handle = handleAccessor?.invoke(wrapper) as? View

            if (hide && handle is ImageView) handle.setImageDrawable(null)

            if (cover != null && scale > 1) {
                val params = cover.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    val base = baseHeights.getOrPut(wrapper) { params.height }
                    val target = base * scale
                    if (params.height != target) {
                        params.height = target
                        updateWindowParams(cover, params)
                        log(module, "touch area height $base -> $target (x$scale)")
                    }
                }
            }

            if (cover != null && handle != null && touchWrapped[wrapper] != true) {
                installTouchForwarding(module, wrapper, cover, handle)
            }
        }.onFailure { logWarn(module, "apply failed: $it") }
    }

    /** 用宿主自己的 WindowManager 更新参数；视图还没 attach 时静默跳过，下次打开再补。 */
    private fun updateWindowParams(view: View, params: WindowManager.LayoutParams) {
        runCatching {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.updateViewLayout(view, params)
        }
    }

    private fun installTouchForwarding(module: XposedModule, wrapper: Any, cover: View, handle: View) {
        val native = cover as? View.OnTouchListener
        cover.setOnTouchListener { view, event ->
            if (forwarding[wrapper] == true) {
                val result = runCatching { handle.dispatchTouchEvent(event) }.getOrDefault(false)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    forwarding.remove(wrapper)
                }
                result
            } else {
                val handled = runCatching { native?.onTouch(view, event) ?: false }.getOrDefault(false)
                if (handled) {
                    true
                } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    forwarding[wrapper] = true
                    runCatching { handle.dispatchTouchEvent(event) }.getOrDefault(false)
                } else {
                    false
                }
            }
        }
        touchWrapped[wrapper] = true
        log(module, "cover touch forwarding installed")
    }

    private fun scaleFactor(): Int {
        val raw = ConfigManager.getString(PrefKeys.SIDEBAR_TOUCH_SCALE, PrefKeys.TOUCH_SCALE_OFF)
        return raw.toIntOrNull()?.coerceIn(1, MAX_SCALE) ?: 1
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
