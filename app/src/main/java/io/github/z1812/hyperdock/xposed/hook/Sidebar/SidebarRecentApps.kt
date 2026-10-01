package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「最近打开应用」的数据来源。
 *
 * 在 `:ui` 进程里查 `UsageStatsManager` 的 `ACTIVITY_RESUMED` 事件（安全中心是系统应用，
 * 持有 PACKAGE_USAGE_STATS），按最后一次启动时间倒序，过滤掉自己、桌面/系统界面、
 * 不可启动的包，以及与常用应用重复的包。图标按包名缓存。
 */
internal object SidebarRecentApps {

    data class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private const val LOOK_BACK_MS = 7L * 24 * 3600 * 1000
    private val iconCache = Collections.synchronizedMap(WeakHashMap<String, Drawable>())
    private val labelCache = Collections.synchronizedMap(WeakHashMap<String, String>())

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: List<Entry> = emptyList()
    @Volatile private var cachedKey = ""

    fun load(context: Context, limit: Int, exclude: Set<String>): List<Entry> {
        if (limit <= 0) return emptyList()
        val key = "$limit|" + exclude.sorted().joinToString(",")
        val now = System.currentTimeMillis()
        if (key == cachedKey && now - cachedAt < 20_000L) return cached
        val entries = runCatching { query(context, limit, exclude) }.getOrDefault(emptyList())
        cachedKey = key
        cachedAt = now
        cached = entries
        return entries
    }

    private fun query(context: Context, limit: Int, exclude: Set<String>): List<Entry> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val end = System.currentTimeMillis()
        val events = manager.queryEvents(end - LOOK_BACK_MS, end) ?: return emptyList()
        val lastUsed = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName ?: continue
            lastUsed[pkg] = maxOf(lastUsed[pkg] ?: 0L, event.timeStamp)
        }
        val packageManager = context.packageManager
        val selfPackage = context.packageName
        return lastUsed.entries
            .asSequence()
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { pkg ->
                pkg != selfPackage && !exclude.contains(pkg) && isLaunchable(packageManager, pkg)
            }
            .take(limit)
            .map { pkg -> Entry(pkg, label(packageManager, pkg), icon(packageManager, pkg)) }
            .toList()
    }

    private fun isLaunchable(packageManager: android.content.pm.PackageManager, pkg: String): Boolean =
        runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    private fun label(packageManager: android.content.pm.PackageManager, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val value = runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        labelCache[pkg] = value
        return value
    }

    private fun icon(packageManager: android.content.pm.PackageManager, pkg: String): Drawable? {
        iconCache[pkg]?.let { return it }
        val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        iconCache[pkg] = drawable
        return drawable
    }
}
