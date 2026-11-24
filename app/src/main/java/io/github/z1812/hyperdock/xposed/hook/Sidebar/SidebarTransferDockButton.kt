package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.HdDebug
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 侧边栏两列模式底部「HyperPortal 中转站」按钮。
 *
 * 原生侧边栏底部只有单个居中的「全部应用」按钮；在两列模式下，将其与新增的
 * 中转站按钮放进同一个水平 [LinearLayout]（[footerRow]）中并排显示，间距与尺寸
 * 对齐上方两列应用图标。
 *
 * 点击中转站按钮时：
 * 1. 先通过 [SidebarCloseHook.closeSidebar] 走原生动画收起侧边栏；
 * 2. 向 `com.android.systemui` 发送显式广播
 *    `com.github.leaf.hyperportal.OPEN_TRANSFER_DOCK`，拉起 HyperPortal 中转站抽屉。
 *
 * 切回单列模式（或关闭开关）时隐藏中转站按钮并清零右边距，底部恢复单个居中按钮。
 */
internal object SidebarTransferDockButton {
    private const val TAG = "HyperDock[TransferDock]"

    private const val ACTION_OPEN_TRANSFER_DOCK = "com.github.leaf.hyperportal.OPEN_TRANSFER_DOCK"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val LAUNCH_DELAY_MS = 45L

    private const val FALLBACK_ITEM_EDGE_DP = 48f
    private const val FOOTER_ROW_TAG = "hyperdock_transfer_dock_footer_row"
    private const val ALL_APPS_SLOT_TAG = "hyperdock_all_apps_slot"
    private const val TRANSFER_SLOT_TAG = "hyperdock_transfer_dock_slot"
    private const val TRANSFER_BTN_TAG = "hyperdock_transfer_dock_button"

    private class FooterHolder(
        val row: LinearLayout,
        val allAppsSlot: FrameLayout,
        val transferSlot: FrameLayout,
        val allAppsView: View,
        val transferButton: ImageView,
        val buttonWidthPx: Int,
        val buttonHeightPx: Int,
    )

    private val holders = Collections.synchronizedMap(WeakHashMap<View, FooterHolder>())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(): Boolean =
        ConfigManager.getBoolean(PrefKeys.SIDEBAR_TRANSFER_DOCK_BUTTON, true)

    fun apply(dock: View, recycler: View, columns: Int) {
        runCatching {
            val holder = holders[dock]?.takeIf {
                it.row.parent != null && it.allAppsView.parent === it.allAppsSlot
            }
                ?: installFooterRow(dock, recycler)?.also { holders[dock] = it }
                ?: return

            val shouldShow = columns > 1 && isEnabled() && holder.allAppsView.visibility == View.VISIBLE
            updateLayout(holder, recycler, shouldShow)
        }.onFailure {
            Log.w(TAG, "apply failed: ${it.message}")
            HdDebug.log("TransferDock", "apply failed: $it")
        }
    }

    private fun installFooterRow(dock: View, recycler: View): FooterHolder? {
        val (parent, allAppsView) = findBottomButtonAndParent(dock, recycler) ?: run {
            HdDebug.log("TransferDock", "bottom all-apps button not found under ${dock.javaClass.simpleName}")
            return null
        }

        // 若已被前一次安装包装过，直接复用，避免重复嵌套。
        if (allAppsView.tag == FOOTER_ROW_TAG && allAppsView is LinearLayout) {
            val allAppsSlot = allAppsView.findViewWithTag<FrameLayout>(ALL_APPS_SLOT_TAG) ?: return null
            val transferSlot = allAppsView.findViewWithTag<FrameLayout>(TRANSFER_SLOT_TAG) ?: return null
            val origBtn = allAppsSlot.getChildAt(0) ?: return null
            val transferBtn = transferSlot.findViewWithTag<ImageView>(TRANSFER_BTN_TAG) ?: return null
            val context = dock.context ?: return null
            val edgePx = dimensionPx(context, "dock_item_edge", FALLBACK_ITEM_EDGE_DP)
            val btnW = origBtn.layoutParams?.width?.takeIf { it > 0 } ?: edgePx
            val btnH = origBtn.layoutParams?.height?.takeIf { it > 0 } ?: edgePx
            return FooterHolder(allAppsView, allAppsSlot, transferSlot, origBtn, transferBtn, btnW, btnH)
        }

        val context = dock.context ?: return null
        val edgePx = dimensionPx(context, "dock_item_edge", FALLBACK_ITEM_EDGE_DP)

        val origLp = allAppsView.layoutParams
        val origWidth = origLp?.width ?: 0
        val origHeight = origLp?.height ?: 0
        val minReasonable = (edgePx * 0.7f).roundToInt()
        val maxReasonable = (edgePx * 1.15f).roundToInt()

        val btnW = if (origWidth in minReasonable..maxReasonable) origWidth else edgePx
        val btnH = if (origHeight in minReasonable..maxReasonable) origHeight else btnW

        val index = parent.indexOfChild(allAppsView)
        if (index < 0) return null

        val row = LinearLayout(context).apply {
            tag = FOOTER_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 外层若是全宽包装容器，把它的上下内边距移到 footerRow 上，避免缩窄到 btnW 时挤压内部图标。
        if (allAppsView is ViewGroup && (origWidth == ViewGroup.LayoutParams.MATCH_PARENT || origWidth > maxReasonable)) {
            row.setPadding(recycler.paddingLeft, allAppsView.paddingTop, recycler.paddingRight, allAppsView.paddingBottom)
            allAppsView.setPadding(0, 0, 0, 0)
        } else {
            row.setPadding(recycler.paddingLeft, 0, recycler.paddingRight, 0)
        }

        // 复用原 LayoutParams 以保留 RelativeLayout / ConstraintLayout / FrameLayout 的定位规则与上下边距。
        val rowLp = origLp ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        rowLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        rowLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (rowLp is ViewGroup.MarginLayoutParams) {
            rowLp.leftMargin = 0
            rowLp.rightMargin = 0
            rowLp.marginStart = 0
            rowLp.marginEnd = 0
        }

        val transferBtn = createTransferDockButton(context, dock, btnW, btnH)
        styleAllAppsButton(allAppsView, context.resources.displayMetrics.density, btnW, btnH)

        // 与上方 GridLayoutManager(2) 一致：两个等宽 weight=1 格各自水平居中放置图标，
        // 保证底部「全部应用」与「中转站」与上方两列应用图标像素级对齐。
        val allAppsSlot = FrameLayout(context).apply {
            tag = ALL_APPS_SLOT_TAG
            clipChildren = false
            clipToPadding = false
        }
        val transferSlot = FrameLayout(context).apply {
            tag = TRANSFER_SLOT_TAG
            clipChildren = false
            clipToPadding = false
        }

        parent.removeViewAt(index)
        allAppsSlot.addView(
            allAppsView,
            FrameLayout.LayoutParams(btnW, btnH, Gravity.CENTER),
        )
        transferSlot.addView(
            transferBtn,
            FrameLayout.LayoutParams(btnW, btnH, Gravity.CENTER),
        )
        row.addView(
            allAppsSlot,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        row.addView(
            transferSlot,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        parent.addView(row, index, rowLp)

        HdDebug.log(
            "TransferDock",
            "installed footer row in ${parent.javaClass.simpleName} at index=$index " +
                "orig=${allAppsView.javaClass.simpleName}(${origWidth}x${origHeight}) " +
                "btn=${btnW}x${btnH} rPad=(${recycler.paddingLeft},${recycler.paddingRight})",
        )
        return FooterHolder(row, allAppsSlot, transferSlot, allAppsView, transferBtn, btnW, btnH)
    }

    private fun updateLayout(holder: FooterHolder, recycler: View, shouldShow: Boolean) {
        val density = holder.allAppsView.resources?.displayMetrics?.density ?: 3f
        styleAllAppsButton(holder.allAppsView, density, holder.buttonWidthPx, holder.buttonHeightPx)

        // 同步 RecyclerView 左右内边距，确保两列中心线与上方应用网格完全重合。
        val padL = recycler.paddingLeft
        val padR = recycler.paddingRight
        if (holder.row.paddingLeft != padL || holder.row.paddingRight != padR) {
            holder.row.setPadding(padL, holder.row.paddingTop, padR, holder.row.paddingBottom)
        }

        val targetVis = if (shouldShow) View.VISIBLE else View.GONE
        if (holder.transferSlot.visibility != targetVis) {
            holder.transferSlot.visibility = targetVis
        }
        if (holder.transferButton.visibility != targetVis) {
            holder.transferButton.visibility = targetVis
        }
    }

    /**
     * 将原生「全部应用」按钮的图标重构为与中转站一致的液态磨砂玻璃圆角方块 + 细线 2×2 方格图标。
     */
    private fun styleAllAppsButton(allAppsView: View, density: Float, widthPx: Int, heightPx: Int) {
        val imageView = findFirstImageView(allAppsView) ?: return
        if (allAppsView is ViewGroup && allAppsView !== imageView) {
            if (allAppsView.background != null) {
                allAppsView.background = null
            }
            if (allAppsView.paddingLeft != 0 || allAppsView.paddingTop != 0 ||
                allAppsView.paddingRight != 0 || allAppsView.paddingBottom != 0
            ) {
                allAppsView.setPadding(0, 0, 0, 0)
            }
            val lp = imageView.layoutParams
            if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                imageView.layoutParams = lp
            }
        }
        if (imageView.background != null) {
            imageView.background = null
        }
        if (imageView.paddingLeft != 0 || imageView.paddingTop != 0 ||
            imageView.paddingRight != 0 || imageView.paddingBottom != 0
        ) {
            imageView.setPadding(0, 0, 0, 0)
        }
        if (imageView.imageTintList != null) {
            imageView.imageTintList = null
        }
        if (imageView.scaleType != ImageView.ScaleType.FIT_CENTER) {
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (imageView.drawable !is AllAppsIconDrawable) {
            imageView.setImageDrawable(AllAppsIconDrawable(density, widthPx, heightPx))
        }
    }

    private fun findFirstImageView(view: View, depth: Int = 0): ImageView? {
        if (view is ImageView) return view
        if (view !is ViewGroup || depth > 4) return null
        for (i in 0 until view.childCount) {
            val hit = findFirstImageView(view.getChildAt(i) ?: continue, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 从 RecyclerView 向上找与它同级的「全部应用」底部按钮。
     * 优先选排在 RecyclerView 之后的、可点击或包含 ImageView 的兄弟节点。
     */
    private fun findBottomButtonAndParent(dock: View, recycler: View): Pair<ViewGroup, View>? {
        var branch: View = recycler
        var depth = 0
        while (depth++ < 12) {
            val parent = branch.parent as? ViewGroup ?: return null
            val branchIndex = parent.indexOfChild(branch)

            // 已经安装过 footerRow 的情况直接返回。
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child.tag == FOOTER_ROW_TAG) return parent to child
            }

            val candidates = mutableListOf<Pair<Int, View>>()
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child === branch) continue
                if (isLikelyBottomButton(child)) {
                    candidates.add(i to child)
                }
            }
            if (candidates.isNotEmpty()) {
                val picked = candidates
                    .filter { it.first > branchIndex }
                    .maxByOrNull { it.first }
                    ?: candidates.maxByOrNull { it.first }
                if (picked != null) return parent to picked.second
            }

            if (parent === dock) break
            branch = parent
        }
        return null
    }

    private fun isLikelyBottomButton(view: View): Boolean {
        val name = view.javaClass.simpleName
        if (name.contains("Background", ignoreCase = true) ||
            name.contains("Blur", ignoreCase = true) ||
            name.contains("Shadow", ignoreCase = true)
        ) {
            return false
        }
        if (view is ImageView) return true
        if (view.isClickable) return true
        if (view is ViewGroup) {
            return containsImageOrClickable(view, 0)
        }
        return false
    }

    private fun containsImageOrClickable(group: ViewGroup, depth: Int): Boolean {
        if (depth > 4) return false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child is ImageView || child.isClickable) return true
            if (child is ViewGroup && containsImageOrClickable(child, depth + 1)) return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTransferDockButton(
        context: Context,
        dock: View,
        widthPx: Int,
        heightPx: Int,
    ): ImageView {
        val dockRef = java.lang.ref.WeakReference(dock)
        return ImageView(context).apply {
            tag = TRANSFER_BTN_TAG
            contentDescription = "HyperPortal 中转站"
            isClickable = true
            isFocusable = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(TransferDockIconDrawable(context.resources.displayMetrics.density, widthPx, heightPx))
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    }
                }
                false
            }
            setOnClickListener {
                openHyperPortalTransferDock(context, dockRef.get())
            }
        }
    }

    private fun openHyperPortalTransferDock(context: Context, dock: View?) {
        val intent = Intent(ACTION_OPEN_TRANSFER_DOCK).apply {
            setPackage(SYSTEMUI_PACKAGE)
        }
        // 在收起侧边栏之前先读取当前 Dock 面板在屏幕上的真实位置与尺寸，
        // 传给 HyperPortal 让中转站面板原地精准对齐。
        if (dock != null && dock.width > 0 && dock.height > 0) {
            val loc = IntArray(2)
            dock.getLocationOnScreen(loc)
            intent.putExtra("dock_left", loc[0])
            intent.putExtra("dock_top", loc[1])
            intent.putExtra("dock_width", dock.width)
            intent.putExtra("dock_height", dock.height)
            val cornerPx = optionalDimensionPx(context, "dock_corner_radius")
                ?: optionalDimensionPx(context, "dock_panel_radius")
            if (cornerPx != null && cornerPx > 0) {
                intent.putExtra("dock_corner_radius", cornerPx)
            }
            HdDebug.log(
                "TransferDock",
                "anchor bounds=(${loc[0]},${loc[1]},${dock.width}x${dock.height}) corner=${cornerPx ?: 0}",
            )
        }
        val closed = SidebarCloseHook.closeSidebar()
        val appContext = context.applicationContext ?: context
        HdDebug.log("TransferDock", "click -> closeSidebar=$closed, broadcasting $ACTION_OPEN_TRANSFER_DOCK")
        mainHandler.postDelayed({
            runCatching {
                appContext.sendBroadcast(intent)
            }.onFailure {
                Log.w(TAG, "send OPEN_TRANSFER_DOCK broadcast failed: ${it.message}")
            }
        }, LAUNCH_DELAY_MS)
    }

    private fun optionalDimensionPx(context: Context, name: String): Int? {
        val resources = context.resources ?: return null
        val id = runCatching { resources.getIdentifier(name, "dimen", context.packageName) }.getOrDefault(0)
        if (id == 0) return null
        return runCatching { resources.getDimensionPixelSize(id) }.getOrNull()?.takeIf { it > 0 }
    }

    private fun dimensionPx(context: Context, name: String, fallbackDp: Float): Int {
        val resources = context.resources ?: return (fallbackDp * 3f).roundToInt()
        val id = runCatching { resources.getIdentifier(name, "dimen", context.packageName) }.getOrDefault(0)
        if (id != 0) {
            runCatching { resources.getDimensionPixelSize(id) }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return (fallbackDp * resources.displayMetrics.density).roundToInt()
    }

    /**
     * 底部功能图标统一磨砂玻璃底座（圆角方块 + 竖向微光渐变 + 亮白细描边），
     * 供「全部应用」与「HyperPortal 中转站」共用，保证两枚按钮视觉完全统一。
     */
    private abstract class GlassDockIconDrawable(
        protected val density: Float,
        private val widthPx: Int,
        private val heightPx: Int,
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(95, 255, 255, 255)
            strokeWidth = (0.75f * density).coerceAtLeast(1f)
        }
        protected val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(242, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private var lastBoundsH = -1
        private var lastSelected = false
        private var isSelectedState = false

        override fun getIntrinsicWidth(): Int = widthPx

        override fun getIntrinsicHeight(): Int = heightPx

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val selectedNow = state.contains(android.R.attr.state_selected)
            if (selectedNow != isSelectedState) {
                isSelectedState = selectedNow
                borderPaint.color = if (selectedNow) {
                    Color.argb(145, 255, 255, 255)
                } else {
                    Color.argb(95, 255, 255, 255)
                }
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val size = minOf(w, h)
            val left = b.left + (w - size) * 0.5f
            val top = b.top + (h - size) * 0.5f
            val inset = borderPaint.strokeWidth * 0.5f
            rect.set(left + inset, top + inset, left + size - inset, top + size - inset)

            if (lastBoundsH != b.height() || lastSelected != isSelectedState) {
                lastBoundsH = b.height()
                lastSelected = isSelectedState
                bgPaint.shader = LinearGradient(
                    left,
                    top,
                    left,
                    top + size,
                    if (isSelectedState) Color.argb(128, 255, 255, 255) else Color.argb(92, 255, 255, 255),
                    if (isSelectedState) Color.argb(86, 224, 236, 255) else Color.argb(58, 224, 236, 255),
                    Shader.TileMode.CLAMP,
                )
            }

            val radius = size * 0.27f
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            val cx = left + size * 0.5f
            val cy = top + size * 0.5f
            glyphPaint.strokeWidth = (size * 0.052f).coerceAtLeast(1.6f * density)
            drawGlyph(canvas, cx, cy, size)
        }

        protected abstract fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float)

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            borderPaint.alpha = alpha
            glyphPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            glyphPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 与中转站图标视觉统一的「全部应用」2×2 细线圆角方格图标。
     */
    private class AllAppsIconDrawable(
        density: Float,
        widthPx: Int,
        heightPx: Int,
    ) : GlassDockIconDrawable(density, widthPx, heightPx) {

        private val cellRect = RectF()

        override fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            // 2×2 圆角方格收窄至约 38% 视觉占比，与右侧中转站图标留白和轻重感一致
            glyphPaint.strokeWidth = (size * 0.044f).coerceAtLeast(1.4f * density)
            val gridSpan = size * 0.34f
            val cellGap = size * 0.075f
            val cellSize = (gridSpan - cellGap) * 0.5f
            val cellRadius = cellSize * 0.28f
            val startX = cx - gridSpan * 0.5f
            val startY = cy - gridSpan * 0.5f
            val step = cellSize + cellGap

            for (row in 0..1) {
                for (col in 0..1) {
                    val x = startX + col * step
                    val y = startY + row * step
                    cellRect.set(x, y, x + cellSize, y + cellSize)
                    canvas.drawRoundRect(cellRect, cellRadius, cellRadius, glyphPaint)
                }
            }
        }
    }

    /**
     * 风格与底部功能区一致的磨砂玻璃圆角方块 + HyperPortal 中转站文件夹双箭头图标。
     */
    private class TransferDockIconDrawable(
        density: Float,
        widthPx: Int,
        heightPx: Int,
    ) : GlassDockIconDrawable(density, widthPx, heightPx) {

        private val folderPath = Path()

        override fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            // 绘制中转站文件夹 + 双向传输箭头（比例与 HyperPortal BuiltInIconDrawable 一致）
            val fw = size * 0.48f
            val fh = size * 0.36f
            val fx = cx - fw * 0.5f
            val fy = cy - fh * 0.5f + size * 0.02f
            val rad = size * 0.065f

            folderPath.reset()
            folderPath.moveTo(fx, fy + rad)
            folderPath.lineTo(fx, fy + fh - rad)
            folderPath.quadTo(fx, fy + fh, fx + rad, fy + fh)
            folderPath.lineTo(fx + fw - rad, fy + fh)
            folderPath.quadTo(fx + fw, fy + fh, fx + fw, fy + fh - rad)
            folderPath.lineTo(fx + fw, fy + rad * 1.5f)
            folderPath.quadTo(fx + fw, fy, fx + fw - rad, fy)
            folderPath.lineTo(fx + fw * 0.55f, fy)
            folderPath.lineTo(fx + fw * 0.42f, fy - size * 0.045f)
            folderPath.lineTo(fx + rad, fy - size * 0.045f)
            folderPath.quadTo(fx, fy - size * 0.045f, fx, fy + rad)
            folderPath.close()
            canvas.drawPath(folderPath, glyphPaint)

            val arrowY1 = cy - size * 0.032f
            canvas.drawLine(cx - size * 0.115f, arrowY1, cx + size * 0.095f, arrowY1, glyphPaint)
            canvas.drawLine(cx + size * 0.035f, arrowY1 - size * 0.04f, cx + size * 0.095f, arrowY1, glyphPaint)
            canvas.drawLine(cx + size * 0.035f, arrowY1 + size * 0.04f, cx + size * 0.095f, arrowY1, glyphPaint)

            val arrowY2 = cy + size * 0.082f
            canvas.drawLine(cx - size * 0.095f, arrowY2, cx + size * 0.115f, arrowY2, glyphPaint)
            canvas.drawLine(cx - size * 0.035f, arrowY2 - size * 0.04f, cx - size * 0.095f, arrowY2, glyphPaint)
            canvas.drawLine(cx - size * 0.035f, arrowY2 + size * 0.04f, cx - size * 0.095f, arrowY2, glyphPaint)
        }
    }
}
