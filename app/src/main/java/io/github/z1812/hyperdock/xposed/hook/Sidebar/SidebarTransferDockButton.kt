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
    private const val LAUNCH_DELAY_MS = 80L

    private const val FALLBACK_ITEM_EDGE_DP = 48f
    private const val FALLBACK_ITEM_PADDING_DP = 12f
    private const val FOOTER_ROW_TAG = "hyperdock_transfer_dock_footer_row"
    private const val TRANSFER_BTN_TAG = "hyperdock_transfer_dock_button"

    private class FooterHolder(
        val row: LinearLayout,
        val allAppsView: View,
        val transferButton: ImageView,
        val buttonWidthPx: Int,
        val buttonHeightPx: Int,
        val gapPx: Int,
    )

    private val holders = Collections.synchronizedMap(WeakHashMap<View, FooterHolder>())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(): Boolean =
        ConfigManager.getBoolean(PrefKeys.SIDEBAR_TRANSFER_DOCK_BUTTON, true)

    fun apply(dock: View, recycler: View, columns: Int) {
        runCatching {
            val holder = holders[dock]?.takeIf { it.row.parent != null && it.allAppsView.parent === it.row }
                ?: installFooterRow(dock, recycler)?.also { holders[dock] = it }
                ?: return

            val shouldShow = columns > 1 && isEnabled() && holder.allAppsView.visibility == View.VISIBLE
            updateLayout(holder, shouldShow)
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
            val origBtn = allAppsView.getChildAt(0) ?: return null
            val transferBtn = allAppsView.findViewWithTag<ImageView>(TRANSFER_BTN_TAG) ?: return null
            val context = dock.context ?: return null
            val edgePx = dimensionPx(context, "dock_item_edge", FALLBACK_ITEM_EDGE_DP)
            val paddingPx = dimensionPx(context, "dock_item_padding", FALLBACK_ITEM_PADDING_DP)
            val btnW = origBtn.layoutParams?.width?.takeIf { it > 0 } ?: edgePx
            val btnH = origBtn.layoutParams?.height?.takeIf { it > 0 } ?: edgePx
            val gapPx = (edgePx + paddingPx - btnW).coerceAtLeast(paddingPx / 2)
            return FooterHolder(allAppsView, origBtn, transferBtn, btnW, btnH, gapPx)
        }

        val context = dock.context ?: return null
        val edgePx = dimensionPx(context, "dock_item_edge", FALLBACK_ITEM_EDGE_DP)
        val paddingPx = dimensionPx(context, "dock_item_padding", FALLBACK_ITEM_PADDING_DP)

        val origLp = allAppsView.layoutParams
        val origWidth = origLp?.width ?: 0
        val origHeight = origLp?.height ?: 0
        val minReasonable = (edgePx * 0.7f).roundToInt()
        val maxReasonable = (edgePx * 1.15f).roundToInt()

        val btnW = if (origWidth in minReasonable..maxReasonable) origWidth else edgePx
        val btnH = if (origHeight in minReasonable..maxReasonable) origHeight else btnW
        val gapPx = (edgePx + paddingPx - btnW).coerceAtLeast(paddingPx / 2)

        val index = parent.indexOfChild(allAppsView)
        if (index < 0) return null

        val row = LinearLayout(context).apply {
            tag = FOOTER_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }

        // 外层若是全宽包装容器，把它的上下内边距移到 footerRow 上，避免缩窄到 btnW 时挤压内部图标。
        if (allAppsView is ViewGroup && (origWidth == ViewGroup.LayoutParams.MATCH_PARENT || origWidth > maxReasonable)) {
            row.setPadding(0, allAppsView.paddingTop, 0, allAppsView.paddingBottom)
            allAppsView.setPadding(0, 0, 0, 0)
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

        val transferBtn = createTransferDockButton(context, btnW, btnH)

        parent.removeViewAt(index)
        row.addView(
            allAppsView,
            LinearLayout.LayoutParams(btnW, btnH).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        row.addView(
            transferBtn,
            LinearLayout.LayoutParams(btnW, btnH).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        parent.addView(row, index, rowLp)

        HdDebug.log(
            "TransferDock",
            "installed footer row in ${parent.javaClass.simpleName} at index=$index " +
                "orig=${allAppsView.javaClass.simpleName}(${origWidth}x${origHeight}) " +
                "btn=${btnW}x${btnH} gap=$gapPx",
        )
        return FooterHolder(row, allAppsView, transferBtn, btnW, btnH, gapPx)
    }

    private fun updateLayout(holder: FooterHolder, shouldShow: Boolean) {
        val targetVis = if (shouldShow) View.VISIBLE else View.GONE
        if (holder.transferButton.visibility != targetVis) {
            holder.transferButton.visibility = targetVis
        }
        val allAppsLp = holder.allAppsView.layoutParams as? LinearLayout.LayoutParams
        val targetMargin = if (shouldShow) holder.gapPx else 0
        if (allAppsLp != null && (allAppsLp.marginEnd != targetMargin || allAppsLp.rightMargin != targetMargin)) {
            allAppsLp.marginEnd = targetMargin
            allAppsLp.rightMargin = targetMargin
            holder.allAppsView.layoutParams = allAppsLp
        }
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
    private fun createTransferDockButton(context: Context, widthPx: Int, heightPx: Int): ImageView {
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
                openHyperPortalTransferDock(context)
            }
        }
    }

    private fun openHyperPortalTransferDock(context: Context) {
        val closed = SidebarCloseHook.closeSidebar()
        val appContext = context.applicationContext ?: context
        val intent = Intent(ACTION_OPEN_TRANSFER_DOCK).apply {
            setPackage(SYSTEMUI_PACKAGE)
        }
        HdDebug.log("TransferDock", "click -> closeSidebar=$closed, broadcasting $ACTION_OPEN_TRANSFER_DOCK")
        mainHandler.postDelayed({
            runCatching {
                appContext.sendBroadcast(intent)
            }.onFailure {
                Log.w(TAG, "send OPEN_TRANSFER_DOCK broadcast failed: ${it.message}")
            }
        }, LAUNCH_DELAY_MS)
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
     * 风格与原生底部四宫格按钮一致的磨砂玻璃圆角方块 + HyperPortal 中转站文件夹双箭头图标。
     */
    private class TransferDockIconDrawable(
        private val density: Float,
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
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(242, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val folderPath = Path()
        private var lastBoundsH = -1

        override fun getIntrinsicWidth(): Int = widthPx

        override fun getIntrinsicHeight(): Int = heightPx

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

            if (lastBoundsH != b.height()) {
                lastBoundsH = b.height()
                bgPaint.shader = LinearGradient(
                    left,
                    top,
                    left,
                    top + size,
                    Color.argb(92, 255, 255, 255),
                    Color.argb(58, 224, 236, 255),
                    Shader.TileMode.CLAMP,
                )
            }

            val radius = size * 0.27f
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            // 绘制中转站文件夹 + 双向传输箭头（比例与 HyperPortal BuiltInIconDrawable 一致）
            val cx = left + size * 0.5f
            val cy = top + size * 0.5f
            glyphPaint.strokeWidth = (size * 0.052f).coerceAtLeast(1.6f * density)

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
}
