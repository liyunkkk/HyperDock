package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.z1812.hyperdock.xposed.hook.HdDebug
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 收起态小横条：闲置自动隐藏 + 触摸面积加长。
 *
 * 宿主结构（安全中心 13.5.9，`com.miui.securitycenter:ui` 进程）：
 * - `com.miui.dock.sidebar.p` 是 SidebarWrapper，构造函数里 inflate 侧边栏布局、
 *   取到小横条 `com.miui.dock.sidebar.RegionSamplingImageView`（继承 ImageView），
 *   并在构造函数里 `setImageDrawable(...)`（全生命周期只设这一次，因此可以
 *   先把原 drawable 存下来，隐藏时置空、恢复时塞回同一个实例）；
 * - 收起态真正接收拖拽的是 `p.w()` 返回的 cover view（日志 tag "SidebarCoverView"），
 *   它的窗口参数由 DockWindowManager 的 `w0(p, boolean)` 生成，
 *   `height` 就是可拖拽区域长度；
 * - 全面屏手势下（`miui_fsgesture_state == 1`）cover view 的 onTouch 在 ACTION_DOWN
 *   就 `return false`，原生不处理时把事件交给小横条
 *   （`p.h0(event)` == `handle.dispatchTouchEvent(event)`）。
 *
 * 这里做三件事：
 * 1. 闲置自动隐藏：小横条平时照常显示，触摸侧边栏/小横条后开始计时，
 *    若干秒没有触摸就把 drawable 置空（不动 visibility、不改布局尺寸，
 *    父类 `base.a` 的 setVisibility 带 setActive 门闩，且拖动逻辑会读 getHeight()）；
 *    下一次触摸立刻把原 drawable 塞回。
 * 2. 触摸面积加长：cover view 窗口 height × 倍率；原始高度按 wrapper 实例只记一次，
 *    防止重复放大叠加。y 保持宿主给的值、不参与居中，避免与 dock 的移动逻辑互抢。
 * 3. 触摸转发：给 cover view 的 OnTouchListener 包壳，原生返回 false 时把整个手势
 *    转发给小横条，使放大后的整片区域都能拖动。
 */
object SidebarHandleHook : BaseHook() {

    private const val TAG = "HyperDock[Handle]"

    /** 小横条类名：被布局 XML 引用，不会混淆。 */
    private const val HANDLE_CLASS = "com.miui.dock.sidebar.RegionSamplingImageView"

    /** 触摸区域倍率的合法上限，防止误填导致整屏被吃掉。 */
    private const val MAX_SCALE = 8

    /** 闲置多久后隐藏小横条。 */
    private const val IDLE_HIDE_MS = 3000L

    /** 连续触摸时重置计时的最小间隔，避免每个 MOVE 都重排任务。 */
    private const val RESET_THROTTLE_MS = 400L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var coverAccessor: Method? = null
    @Volatile private var handleAccessor: Method? = null
    @Volatile private var module: XposedModule? = null

    /** wrapper 实例 -> 首次见到的窗口高度。 */
    private val baseHeights = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    /** wrapper 实例 -> 是否已经包过触摸转发。 */
    private val touchWrapped = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** wrapper 实例 -> 我们装上去的触摸监听，宿主可能顶掉它，需要能重挂。 */
    private val touchListeners = Collections.synchronizedMap(WeakHashMap<Any, View.OnTouchListener>())

    /** wrapper 实例 -> 当前是否处于「原生不处理、由我们转发」的手势中。 */
    private val forwarding = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** wrapper 实例 -> 自绘黑条（触摸面积放大时用，长度 = 原生线长 × 倍率）。 */
    private val ownBars = Collections.synchronizedMap(WeakHashMap<Any, Drawable>())

    /** wrapper 实例 -> 小横条原始 drawable。 */
    private val handleDrawables = Collections.synchronizedMap(WeakHashMap<Any, Drawable>())

    /** wrapper 实例 -> 待执行的闲置隐藏任务。 */
    private val idleTasks = Collections.synchronizedMap(WeakHashMap<Any, Runnable>())

    /** wrapper 实例 -> 上次重置计时的时间。 */
    private val lastResetAt = Collections.synchronizedMap(WeakHashMap<Any, Long>())

    /** 小横条实例 -> 所属 wrapper，供小横条自身触摸时回推状态。 */
    private val handleOwner = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /** 保留 wrapper 弱引用，配置变化时重新应用。 */
    private val wrappers = Collections.synchronizedList(mutableListOf<WeakReference<Any>>())

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        this.module = module
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        HdDebug.log(TAG, "onInit start: pkg=" + param.packageName + " proc=" + processName)
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }

        val loader = param.defaultClassLoader
        val openMethod = SidebarExpandDexDiscovery.findOpenMethod(loader)
        HdDebug.log(TAG, "openMethod=" + openMethod)
        // DexKit 发现失败也要能用：宿主里 SidebarWrapper 就是 com.miui.dock.sidebar.p，
        // 直接按类名兜底（拿不到 open 方法就只挂构造函数）。
        val wrapperClass = openMethod?.parameterTypes?.getOrNull(0)
            ?: runCatching { Class.forName("com.miui.dock.sidebar.p", false, loader) }.getOrNull()
        if (wrapperClass == null) {
            HdDebug.log(TAG, "wrapper class unavailable; handle hook disabled")
            logWarn(module, "normal sidebar open method unavailable")
            return
        }
        HdDebug.log(TAG, "wrapper=" + wrapperClass.name + " viaOpenMethod=" + (openMethod != null))
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

        // 小横条自己被摸到时也要唤醒（收起态下拖动的是面板窗口里的小横条）。
        runCatching {
            val dispatch = handleClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            module.hook(dispatch).intercept { chain ->
                val handle = chain.thisObject
                val wrapper = handleOwner[handle]
                if (wrapper != null) touchActivity(wrapper, handle as View)
                chain.proceed()
            }
        }.onFailure { logWarn(module, "hook handle dispatchTouchEvent failed: $it") }

        // 宿主在闲置/释放面板后会重新下发窗口参数，把我们的高度冲掉——
        // 表现就是「闲置久了放大区域失效，只能滑原生黑条长度」。这里盯住它的下发入口。
        runCatching {
            val l0 = Class.forName("vb.l0", false, loader)
            val update = l0.declaredMethods.firstOrNull { method ->
                method.parameterCount == 2 && method.returnType == Void.TYPE &&
                    method.parameterTypes[0] == View::class.java &&
                    method.parameterTypes[1] == WindowManager.LayoutParams::class.java
            }
            if (update == null) {
                HdDebug.log(TAG, "host updateViewLayout entry not found")
            } else {
                update.isAccessible = true
                module.hook(update).intercept { chain ->
                    val result = chain.proceed()
                    (chain.args.getOrNull(0) as? View)?.let { view -> reassertForCover(view) }
                    result
                }
                HdDebug.log(TAG, "host updateViewLayout hooked")
            }
        }.onFailure { HdDebug.log(TAG, "updateViewLayout hook failed: $it") }

        // 构造后立刻应用一次。
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
        if (openMethod == null) {
            HdDebug.log(TAG, "open method 缺失，只在构造时应用")
            return
        }
        openMethod.isAccessible = true
        module.hook(openMethod).intercept { chain ->
            val result = chain.proceed()
            chain.args.firstOrNull()?.let { wrapper ->
                remember(wrapper)
                applyTo(module, wrapper)
                val handle = handleAccessor?.invoke(wrapper) as? View
                if (handle != null) touchActivity(wrapper, handle)
            }
            result
        }
    }

    override fun onConfigChanged() {
        val current = module ?: return
        val alive = synchronized(wrappers) { wrappers.mapNotNull { it.get() } }
        alive.forEach { wrapper -> runCatching { applyTo(current, wrapper) } }
    }

    private fun remember(wrapper: Any) {
        val list = synchronized(wrappers) {
            wrappers.removeAll { it.get() == null }
            wrappers
        }
        if (list.none { it.get() === wrapper }) list.add(WeakReference(wrapper))
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
        val cover = runCatching { coverAccessor?.invoke(wrapper) as? View }.getOrNull()
        val handle = runCatching { handleAccessor?.invoke(wrapper) as? View }.getOrNull()
        runCatching {
            val wp = cover?.layoutParams as? WindowManager.LayoutParams
            HdDebug.log(
                TAG,
                "applyTo cover=" + (cover != null) + " handle=" + (handle != null) +
                    " scale=" + scaleFactor() + " wH=" + wp?.height +
                    " attached=" + cover?.isAttachedToWindow + " base=" + baseHeights[wrapper],
            )
        }
        if (handle != null) {
            handleOwner[handle] = wrapper
            captureDrawable(wrapper, handle)
        }

        // 触摸面积
        if (cover != null) {
            val scale = scaleFactor()
            runCatching {
                val params = cover.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    val base = baseHeights.getOrPut(wrapper) { params.height }
                    val target = if (scale > 1) base * scale else base
                    if (params.height != target) {
                        params.height = target
                        updateWindowParams(cover, params)
                        HdDebug.log(TAG, "touch area height " + base + " -> " + target + " (x" + scale + ")")
                    }
                }
            }.onFailure { logWarn(module, "resize touch area failed: $it") }
        }

        // 可见小横条不拉伸：本体是 3dp × 66dp 的竖线，装在 32dp × 112dp 的视图里，
        // 面板窗口 wrap_content（收起态 112dp 高），拉伸会被窗口裁掉一截
        // （3 倍时 198dp 只剩 112dp 可见）。要「黑条长度=触摸区域」只能自绘，
        // 见项目笔记里的方案说明。
        if (handle != null) {
            runCatching {
                handle.pivotY = handle.height / 2f
                handle.scaleY = 1f
            }
        }

        // 临时停用：整层触摸转发会把右侧边缘的触摸全部吃掉（用户反馈"右边屏幕都没办法滑动"）。
        // 这里不再安装/重挂转发监听，也不重挂 cover 窗口，完全回到宿主原生触摸行为。
        // 后续要做成"只在水平拖动时才接管"，再放开这段。

        // 黑条本体按用户最终要求：**保持宿主原生长度**，不再自绘也不拉长；
        // 放大的只有触摸区域（cover 窗口高度）。之前自绘过就把它清掉，回到宿主 drawable。
        if (ownBars.remove(wrapper) != null && cover != null) {
            cover.background = null
            HdDebug.log(TAG, "own bar disabled, keep native length")
        }

        // 可见性：开关打开时先显示并起计时；关掉时恢复常显。
        if (handle != null) {
            val idleHide = idleHideEnabled()
            showHandle(wrapper, handle, resetTimer = idleHide)
            if (!idleHide) cancelIdleTask(wrapper)
        }
    }

    /** 宿主的 drawable 只在构造时设一次，抓住它就能反复置空/恢复。 */
    private fun captureDrawable(wrapper: Any, handle: View) {
        if (handleDrawables.containsKey(wrapper)) return
        val drawable = (handle as? ImageView)?.drawable ?: return
        handleDrawables[wrapper] = drawable
    }

    private fun showHandle(wrapper: Any, handle: View, resetTimer: Boolean) {
        mainHandler.post {
            runCatching {
                val own = ownBars[wrapper]
                if (own != null) {
                    val cover = coverAccessor?.invoke(wrapper) as? View
                    if (cover != null && cover.background !== own) {
                        cover.background = own
                        logMsg("own bar shown")
                    }
                } else {
                    val saved = handleDrawables[wrapper]
                    val image = handle as? ImageView
                    if (image != null && saved != null && image.drawable !== saved) {
                        image.setImageDrawable(saved)
                        logMsg("native bar shown")
                    }
                }
            }
            if (resetTimer) resetIdleTimer(wrapper)
        }
    }

    private fun touchActivity(wrapper: Any, handle: View) {
        showHandle(wrapper, handle, resetTimer = true)
    }

    private fun resetIdleTimer(wrapper: Any) {
        val now = SystemClock.uptimeMillis()
        val last = lastResetAt[wrapper] ?: 0L
        if (now - last < RESET_THROTTLE_MS) return
        lastResetAt[wrapper] = now
        mainHandler.post {
            cancelIdleTask(wrapper)
            if (!idleHideEnabled()) return@post
            val task = Runnable { performIdleHide(wrapper) }
            idleTasks[wrapper] = task
            mainHandler.postDelayed(task, IDLE_HIDE_MS)
        }
    }

    private fun cancelIdleTask(wrapper: Any) {
        idleTasks.remove(wrapper)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun performIdleHide(wrapper: Any) {
        idleTasks.remove(wrapper)
        if (!idleHideEnabled()) return
        val handle = runCatching { handleAccessor?.invoke(wrapper) as? View }.getOrNull() ?: return
        val own = ownBars[wrapper]
        if (own != null) {
            val cover = coverAccessor?.invoke(wrapper) as? View
            if (cover != null && cover.background === own) {
                cover.background = null
                logMsg("own bar hidden after ${IDLE_HIDE_MS}ms idle")
            }
        } else {
        val image = handle as? ImageView
        if (image != null && image.drawable != null) {
            image.setImageDrawable(null)
            logMsg("handle bar hidden after ${IDLE_HIDE_MS}ms idle")
        }
        }
        runCatching {
            val c = coverAccessor?.invoke(wrapper) as? View
            val wp = c?.layoutParams as? WindowManager.LayoutParams
            HdDebug.log(
                TAG,
                "idle-hide check cover=" + (c != null) + " attached=" + c?.isAttachedToWindow +
                    " wH=" + wp?.height,
            )
        }
        // 闲置期间宿主可能已经把 cover 摘掉/把参数冲掉，这里顺手补一次
        module?.let { runCatching { applyTo(it, wrapper) } }
    }

    /** 用宿主自己的 WindowManager 更新参数；视图还没 attach 时静默跳过，下次打开再补。 */
    private fun updateWindowParams(view: View, params: WindowManager.LayoutParams) {
        runCatching {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.updateViewLayout(view, params)
        }
    }

    /**
     * cover 窗口被宿主摘掉（闲置/释放面板）之后，放大区域就彻底没有了——只剩面板里那截
     * 真实黑条能滑，滑一次触发打开流程才恢复。这里借宿主自己的窗口助手（`p.o()` 返回的 l0）
     * 把 cover 重新 addView 回去，参数用它当前那份（含我们放大的高度）。
     */
    private fun ensureCoverAttached(wrapper: Any, cover: View) {
        if (cover.isAttachedToWindow) return
        val params = cover.layoutParams as? WindowManager.LayoutParams ?: return
        val helper = runCatching {
            wrapper.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == "o" }
                ?.invoke(wrapper)
        }.getOrNull() ?: return
        val add = runCatching {
            helper.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 2 && method.returnType == Void.TYPE &&
                    method.parameterTypes[0] == View::class.java &&
                    method.parameterTypes[1] == WindowManager.LayoutParams::class.java
            }
        }.getOrNull() ?: return
        runCatching { add.invoke(helper, cover, params) }
            .onSuccess { HdDebug.log(TAG, "cover re-attached by module, h=" + params.height) }
            .onFailure { HdDebug.log(TAG, "cover re-attach failed: " + it) }
    }

    private fun installTouchForwarding(module: XposedModule, wrapper: Any, cover: View, handle: View) {
        val native = cover as? View.OnTouchListener
        val listener = View.OnTouchListener { view, event ->
            // 宿主闲置/重建时会换掉视图，闭包里捕获的 handle 可能已经不在窗口上，
            // 转发出去就等于打空（表现：放大区域滑不动，只有真实黑条管用）。这里每次现取。
            val current = runCatching { handleAccessor?.invoke(wrapper) as? View }.getOrNull()
                ?: handle
            touchActivity(wrapper, current)
            if (forwarding[wrapper] == true) {
                val result = runCatching { current.dispatchTouchEvent(event) }.getOrDefault(false)
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
                    runCatching { current.dispatchTouchEvent(event) }.getOrDefault(false)
                } else {
                    false
                }
            }
        }
        touchListeners[wrapper] = listener
        cover.setOnTouchListener(listener)
        touchWrapped[wrapper] = true
        HdDebug.log(TAG, "cover touch forwarding installed")
    }

    /**
     * 自绘黑条：宿主原生黑条是 3dp × 66dp 的竖线，画在 32dp × 112dp 的视图里、
     * 面板窗口只有 112dp 高，所以拉伸原生视图会被窗口裁掉。这里改成在 cover view
     * （窗口高度 = 触摸区域，我们自己在放大）的 background 上画一条同样粗细、
     * 长度 = 原生线长 × 倍率的圆头竖线：贴着屏幕边、顶端与原声黑条对齐、向下生长，
     * 因此整条都在窗口内，不会被裁。
     */
    private fun buildBarDrawable(cover: View, scale: Int): Drawable {
        val res = cover.resources
        val barWidth = dimen(res, "sidebar_line_width_vertical", 3)
        val lineLength = dimen(res, "sidebar_line_height_vertical", 66)
        val margin = dimen(res, "sidebar_line_margin_start", 6)
        val params = cover.layoutParams as? WindowManager.LayoutParams
        val isLeft = params != null && (params.gravity and Gravity.LEFT) != 0
        // 原生线的顶端：视图高 112dp、线 66dp，居中 → (112-66)/2 = 23dp
        val nativeViewHeight = dimen(res, "sidebar_height_vertical", 112)
        val top = ((nativeViewHeight - lineLength) / 2).coerceAtLeast(0)
        return BarDrawable(
            barWidth = barWidth,
            margin = margin,
            top = top,
            length = lineLength * scale,
            isLeft = isLeft,
        )
    }

    private fun dimen(res: Resources, name: String, fallbackDp: Int): Int {
        val id = res.getIdentifier(name, "dimen", "com.miui.securitycenter")
        val value = if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (value > 0) value else (fallbackDp * res.displayMetrics.density).toInt()
    }

    /** 中性灰（约 67% 不透明）：浅底深底都看得见，又不至于太重。 */
    private class BarDrawable(
        private val barWidth: Int,
        private val margin: Int,
        private val top: Int,
        private val length: Int,
        private val isLeft: Boolean,
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 90, 90, 90) }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val left = if (isLeft) margin.toFloat() else (bounds.width() - margin - barWidth).toFloat()
            rect.set(left, top.toFloat(), left + barWidth, (top + length).toFloat())
            val radius = barWidth / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 宿主重新下发窗口参数后，把我们的高度与触摸转发补回去。 */
    private fun reassertForCover(view: View) {
        val current = module ?: return
        val wrapper = synchronized(handleOwner) {
            handleOwner.entries.firstOrNull { entry ->
                runCatching { coverAccessor?.invoke(entry.value) === view }.getOrDefault(false)
            }?.value
        } ?: return
        HdDebug.log(TAG, "cover params updated by host → re-assert")
        runCatching { applyTo(current, wrapper) }
    }

    /** 日志出口：无条件落盘（module.log 受「调试日志」开关控制，实际是关的，看不到东西）。 */
    private fun logMsg(message: String) {
        HdDebug.log(TAG, message)
    }

    private fun idleHideEnabled(): Boolean =
        ConfigManager.getBoolean(PrefKeys.SIDEBAR_HANDLE_IDLE_HIDE, true)

    private fun scaleFactor(): Int {
        // 临时停用放大：窗口高度保持原生（原因同上）。恢复时改回读 PrefKeys.SIDEBAR_TOUCH_SCALE。
        return 1
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
