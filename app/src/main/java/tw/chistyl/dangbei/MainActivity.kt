package tw.chistyl.dangbei

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var foldersContainer: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var networkLabel: TextView
    private lateinit var desktopSettingsButton: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRefreshTask = object : Runnable {
        override fun run() {
            if (::dateLabel.isInitialized) updateClock()
            if (::networkLabel.isInitialized) updateNetworkStatus()
            uiHandler.postDelayed(this, 30_000L)
        }
    }
    private val blue = Color.rgb(0, 103, 255)
    private val apps = mutableListOf<android.content.pm.ResolveInfo>()
    private val appLabelCache = mutableMapOf<String, String>()
    private val appIconCache = mutableMapOf<String, Drawable.ConstantState>()
    private val pageSize = 12
    private var page = 0
    private var firstFocusOnRender = true
    private var lastFocusedPackage: String? = null
    private var lastFocusedIndex = 0
    private var lastFocusedFolderName: String? = null
    private val prefs by lazy { getSharedPreferences("dangbei_home_prefs", MODE_PRIVATE) }
    private var packageReceiverRegistered = false
    private val packageRefreshTask = Runnable {
        if (::grid.isInitialized) {
            refreshAppsPreservingFocus()
        }
    }
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    val changedPackage = intent.data?.schemeSpecificPart
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    // During an in-place update, PACKAGE_REMOVED is temporary.
                    if (!isReplacing && !changedPackage.isNullOrBlank()) {
                        appLabelCache.remove(changedPackage)
                        appIconCache.remove(changedPackage)
                    }
                    uiHandler.removeCallbacks(packageRefreshTask)
                    // Allow TV package managers to publish the replacement launcher activity.
                    uiHandler.postDelayed(packageRefreshTask, 1_200L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        page = prefs.getInt("last_page", 0).coerceAtLeast(0)
        lastFocusedPackage = prefs.getString("last_focused_package", null)
        lastFocusedIndex = prefs.getInt("last_focused_index", 0).coerceAtLeast(0)
        lastFocusedFolderName = prefs.getString("last_focused_folder", null)
        buildHome()
        loadApps()
        updateClock()
        updateNetworkStatus()
        uiHandler.removeCallbacks(statusRefreshTask)
        uiHandler.postDelayed(statusRefreshTask, 30_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::grid.isInitialized) {
            // HOME may be delivered to an existing singleTask activity.
            // Use the same refresh path as package changes and onResume so
            // focus restoration and empty-list handling stay consistent.
            refreshAppsPreservingFocus()
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // TV system overlays can briefly reveal navigation/status bars after
        // dialogs or system surfaces close. Re-apply immersive mode on return.
        if (hasFocus) hideSystemBars()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildHome() {
        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val bg = ImageView(this).apply {
            setImageResource(R.drawable.bg_launcher_default_new)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "桌面背景"
        }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))

        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x44000000, 0x10000000, 0xAA000000.toInt())
            )
        }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(54f), dp(30f), dp(54f), dp(22f))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "當貝桌面"
        }
        header.addView(brandIcon, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply {
            rightMargin = dp(12f)
        })
        val brand = TextView(this).apply {
            text = "當貝桌面"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        dateLabel = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xFFE8E8E8.toInt())
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }
        header.addView(dateLabel)
        networkLabel = TextView(this).apply {
            text = "檢查網路中"
            textSize = 14f
            setTextColor(0xFFE8E8E8.toInt())
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
            setPadding(dp(22f), 0, 0, 0)
            contentDescription = "網路連線狀態"
        }
        header.addView(networkLabel)
        content.addView(header)

        val sectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(26f), 0, dp(12f))
        }
        val section = TextView(this).apply {
            text = "我的應用"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        sectionRow.addView(section, LinearLayout.LayoutParams(0, -2, 1f))
        pageLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFD9E6F7.toInt())
        }
        sectionRow.addView(pageLabel)
        content.addView(sectionRow)

        grid = GridLayout(this).apply {
            columnCount = 6
            rowCount = 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            // Focused tiles scale beyond their measured bounds; keep the glow
            // visible at the first/last columns and the top/bottom rows.
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        foldersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Folder focus animation also grows beyond the normal tile bounds.
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4f), 0, dp(4f))
        }
        // Allow the folder row to scroll horizontally when users create
        // more folders than can fit across the TV screen.
        val foldersScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        foldersScroll.addView(
            foldersContainer,
            ViewGroup.LayoutParams(-2, -1)
        )
        content.addView(foldersScroll, LinearLayout.LayoutParams(-1, dp(78f)))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(10f), 0, 0)
        }
        val hint = TextView(this).apply {
            text = "方向鍵瀏覽　OK 開啟　長按管理 APP"
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
        }
        footer.addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
        desktopSettingsButton = TextView(this).apply {
            text = "桌面選項"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            background = focusBackground(false)
            setOnFocusChangeListener { v, focused -> v.background = focusBackground(focused) }
            setOnClickListener { showDesktopOptions() }
        }
        footer.addView(desktopSettingsButton)
        content.addView(footer)

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun updateClock() {
        dateLabel.text = SimpleDateFormat("yyyy/MM/dd  E  HH:mm", Locale.TAIWAN).format(Date())
    }

    private fun updateNetworkStatus() {
        val label = if (android.os.Build.VERSION.SDK_INT >= 23) {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            when {
                capabilities == null -> "網路未連線"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 已連線"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有線網路已連線"
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "網路已連線"
                else -> "網路未連線"
            }
        } else {
            @Suppress("DEPRECATION")
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            if (manager.activeNetworkInfo?.isConnected == true) "網路已連線" else "網路未連線"
        }
        networkLabel.text = "●  $label"
    }

    private fun folderMap(): MutableMap<String, String> {
        val json = prefs.getString("app_folders", "{}") ?: "{}"
        val result = mutableMapOf<String, String>()
        try {
            val obj = org.json.JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val folderName = obj.optString(key).trim()
                if (key.isNotBlank() && folderName.isNotBlank() && folderName.length <= 16) {
                    result[key] = folderName
                }
            }
        } catch (_: Exception) {
            // Recover from a damaged preference value without crashing the launcher.
            prefs.edit().remove("app_folders").apply()
        }
        return result
    }

    private fun saveFolderMap(map: Map<String, String>) {
        val obj = org.json.JSONObject()
        map.filter { (pkg, folder) ->
            pkg.isNotBlank() && folder.isNotBlank() && folder.length <= 16
        }.forEach { (pkg, folder) -> obj.put(pkg, folder) }
        prefs.edit().putString("app_folders", obj.toString()).apply()
        pruneFolderFocusPreferences(map)
    }

    /**
     * Remove remembered folder focus entries that no longer match a real
     * folder membership. This prevents deleted/renamed folders and removed
     * apps from leaving stale focus state in SharedPreferences.
     */
    private fun pruneFolderFocusPreferences(assignments: Map<String, String>) {
        val editor = prefs.edit()
        val validFolders = assignments.values.toSet()
        prefs.all.keys.filter { it.startsWith("folder_focus_") }.forEach { key ->
            val folderName = key.removePrefix("folder_focus_")
            val rememberedPackage = prefs.getString(key, null)
            if (!validFolders.contains(folderName) ||
                rememberedPackage.isNullOrBlank() ||
                assignments[rememberedPackage] != folderName
            ) {
                editor.remove(key)
            }
        }
        val lastFolder = prefs.getString("last_focused_folder", null)
        if (!lastFolder.isNullOrBlank() && !validFolders.contains(lastFolder)) {
            editor.remove("last_focused_folder")
            if (lastFocusedFolderName == lastFolder) lastFocusedFolderName = null
        }
        editor.apply()
    }

    private fun moveAppToFolder(packageName: String, folderName: String): Boolean {
        if (packageName.isBlank() || folderName.isBlank() || folderName.length > 16) return false
        if (apps.none { it.activityInfo.packageName == packageName }) return false
        val assignments = folderMap()
        if (assignments[packageName] == folderName) return false

        // Capture the app's desktop position before removing it from the grid.
        // After the move, focus the nearest remaining desktop app rather than
        // leaving focus attached to an item that now lives inside the folder.
        val desktopBefore = desktopApps()
        val previousDesktopIndex = desktopBefore.indexOfFirst {
            it.activityInfo.packageName == packageName
        }.takeIf { it >= 0 } ?: (page * pageSize + lastFocusedIndex)

        assignments[packageName] = folderName
        saveFolderMap(assignments)
        lastFocusedFolderName = folderName
        prefs.edit()
            .putString("last_focused_folder", folderName)
            .putString("folder_focus_$folderName", packageName)
            .apply()

        val desktopAfter = desktopApps()
        if (desktopAfter.isEmpty()) {
            page = 0
            lastFocusedIndex = 0
            lastFocusedPackage = null
        } else {
            val restoredIndex = previousDesktopIndex.coerceIn(0, desktopAfter.lastIndex)
            page = restoredIndex / pageSize
            lastFocusedIndex = restoredIndex % pageSize
            lastFocusedPackage = desktopAfter[restoredIndex].activityInfo.packageName
        }
        firstFocusOnRender = true
        persistDesktopPosition()
        return true
    }

    private fun moveFolderApp(packageName: String, folderName: String, direction: Int): Boolean {
        if (direction != -1 && direction != 1) return false
        val assignments = folderMap()
        if (assignments[packageName] != folderName) return false
        val memberIndices = apps.indices.filter {
            assignments[apps[it].activityInfo.packageName] == folderName
        }
        val currentMemberPosition = memberIndices.indexOfFirst {
            apps[it].activityInfo.packageName == packageName
        }
        val targetMemberPosition = currentMemberPosition + direction
        if (currentMemberPosition < 0 || targetMemberPosition !in memberIndices.indices) {
            return false
        }
        val fromIndex = memberIndices[currentMemberPosition]
        val toIndex = memberIndices[targetMemberPosition]
        val moving = apps[fromIndex]
        apps[fromIndex] = apps[toIndex]
        apps[toIndex] = moving
        saveAppOrder()
        prefs.edit().putString("folder_focus_$folderName", packageName).apply()
        lastFocusedFolderName = folderName
        prefs.edit().putString("last_focused_folder", folderName).apply()
        return true
    }

    private fun desktopApps(): List<android.content.pm.ResolveInfo> {
        val assigned = folderMap().keys
        return apps.filterNot { assigned.contains(it.activityInfo.packageName) }
    }

    /**
     * Refresh the launcher list without losing the user's position.
     * If the focused app was uninstalled or moved into a folder, keep the
     * nearest valid desktop index instead of jumping back to the first tile.
     */
    private fun refreshAppsPreservingFocus() {
        val previousPackage = lastFocusedPackage
        val previousPage = page
        val previousIndex = lastFocusedIndex
        loadApps()

        val current = desktopApps()
        if (current.isEmpty()) {
            page = 0
            lastFocusedIndex = 0
            lastFocusedPackage = null
            renderPage()
            persistDesktopPosition()
            return
        }

        val restoredIndex = previousPackage?.let { pkg ->
            current.indexOfFirst { it.activityInfo.packageName == pkg }
        }?.takeIf { it >= 0 } ?: (
            previousPage.coerceAtLeast(0) * pageSize + previousIndex.coerceAtLeast(0)
        ).coerceIn(0, current.lastIndex)

        page = restoredIndex / pageSize
        lastFocusedIndex = restoredIndex % pageSize
        lastFocusedPackage = current[restoredIndex].activityInfo.packageName
        firstFocusOnRender = true
        renderPage()
        persistDesktopPosition()
    }

    private fun loadApps() {
        val pm = packageManager
        val candidates = mutableListOf<android.content.pm.ResolveInfo>()
        val actions = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        for (intent in actions) {
            val found = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            candidates.addAll(found)
        }

        val unique = candidates
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }

        // Keep cached labels/icons for packages that are still installed.
        // Only evict entries for packages that disappeared from the device.
        val installedPackages = unique.map { it.activityInfo.packageName }.toSet()
        appLabelCache.keys.retainAll(installedPackages)
        appIconCache.keys.retainAll(installedPackages)

        // Keep the saved manual order limited to packages that still have
        // a launcher entry. This prevents stale entries accumulating forever.
        val savedOrderPackages = (prefs.getString("app_order", "") ?: "")
            .split("|").filter { it.isNotBlank() }.distinct()
        val validOrderPackages = savedOrderPackages.filter { installedPackages.contains(it) }
        if (validOrderPackages != savedOrderPackages) {
            prefs.edit().putString("app_order", validOrderPackages.joinToString("|")).apply()
        }

        // Remove folder assignments for apps that have been uninstalled or
        // no longer expose a launcher entry, then prune their remembered focus.
        val savedFolders = folderMap()
        val validFolders = savedFolders.filterKeys { installedPackages.contains(it) }
        if (validFolders.size != savedFolders.size) {
            saveFolderMap(validFolders)
        }

        // Remove hidden-app entries for packages that no longer expose a
        // launcher activity on this device.
        val savedHidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val validHidden = savedHidden.filterTo(mutableSetOf()) {
            it.isNotBlank() && installedPackages.contains(it)
        }
        if (validHidden != savedHidden) {
            prefs.edit().putStringSet("hidden_apps", validHidden).apply()
        }

        unique.forEach { info ->
            val pkg = info.activityInfo.packageName
            if (appLabelCache[pkg].isNullOrBlank()) {
                appLabelCache[pkg] = info.loadLabel(pm).toString()
            }
        }

        val savedOrder = (prefs.getString("app_order", "") ?: "")
            .split("|").filter { it.isNotBlank() }.distinct()
        val orderMap = savedOrder.withIndex().associate { it.value to it.index }
        val hidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        apps.clear()
        apps.addAll(unique
            .filterNot { hidden.contains(it.activityInfo.packageName) }
            .sortedWith(compareBy(
                { orderMap[it.activityInfo.packageName] ?: Int.MAX_VALUE },
                { appLabelCache[it.activityInfo.packageName]?.lowercase(Locale.ROOT) ?: "" }
            )))

        // Folder assignments belong to installed packages, not only visible desktop apps.
        // Hidden apps may still be intentionally retained inside a folder.
        val currentFolders = folderMap()
        if (currentFolders.keys.any { !installedPackages.contains(it) }) {
            currentFolders.keys.removeAll { !installedPackages.contains(it) }
            saveFolderMap(currentFolders)
        }
        val maxPage = ((desktopApps().size - 1).coerceAtLeast(0)) / pageSize
        page = page.coerceIn(0, maxPage)
        renderPage()
    }

    private fun appLabel(info: android.content.pm.ResolveInfo): String {
        val pkg = info.activityInfo.packageName
        return appLabelCache[pkg] ?: info.loadLabel(packageManager).toString().also {
            appLabelCache[pkg] = it
        }
    }

    private fun appIcon(info: android.content.pm.ResolveInfo): Drawable {
        val pkg = info.activityInfo.packageName
        val cached = appIconCache[pkg]
        if (cached != null) {
            return cached.newDrawable(resources).mutate()
        }
        val drawable = info.loadIcon(packageManager)
        drawable.constantState?.let { appIconCache[pkg] = it }
        return drawable
    }

    private fun renderPage() {
        grid.removeAllViews()
        val desktopList = desktopApps()
        val maxPage = if (desktopList.isEmpty()) 0 else (desktopList.size - 1) / pageSize
        page = page.coerceIn(0, maxPage)
        val start = page * pageSize
        val visible = desktopList.drop(start).take(pageSize)
        // Adapt card and icon dimensions to the TV's logical screen width.
        // The source icon background is 360:224, so preserve that ratio.
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val columnWidthDp = ((screenWidthDp - 108f) / 6f).coerceAtLeast(120f)
        // Dangbei's supplied app tile artwork is landscape (360:224).
        // Size the card from the actual icon height plus vertical padding and
        // the one-line app label, so 1080p/720p layouts do not crop the label.
        val iconWidthDp = (columnWidthDp * 0.82f).coerceIn(104f, 160f)
        val iconHeightDp = iconWidthDp * 224f / 360f
        val cardHeightDp = (iconHeightDp + 52f).coerceIn(116f, 176f)
        val cardMarginDp = (columnWidthDp * 0.025f).coerceIn(4f, 9f)
        visible.forEachIndexed { index, info ->
            val pm = packageManager
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                background = getDrawable(R.drawable.bg_app_list_item)
                val cardPadding = (10f * density).toInt()
                setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
                clipToPadding = false
                setTag(info.activityInfo.packageName)
                setOnFocusChangeListener { v, focused ->
                    v.background = getDrawable(
                        if (focused) R.drawable.focus_app_list_item
                        else R.drawable.bg_app_list_item
                    )
                    v.animate().scaleX(if (focused) 1.055f else 1f)
                        .scaleY(if (focused) 1.055f else 1f).setDuration(150).start()
                    v.elevation = if (focused) 12f else 0f
                    if (focused) {
                        lastFocusedPackage = info.activityInfo.packageName
                        lastFocusedIndex = index
                        persistDesktopPosition()
                    }
                }
                setOnClickListener { launchApp(info) }
                setOnLongClickListener { view ->
                    val clip = ClipData.newPlainText("app_package", info.activityInfo.packageName)
                    val shadow = View.DragShadowBuilder(view)
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        view.startDragAndDrop(clip, shadow, info.activityInfo.packageName, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        view.startDrag(clip, shadow, info.activityInfo.packageName, 0)
                    }
                    true
                }
                setOnDragListener { target, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> true
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                            target.alpha = 0.65f
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_EXITED -> {
                            target.alpha = 1f
                            true
                        }
                        android.view.DragEvent.ACTION_DROP -> {
                            target.alpha = 1f
                            val fromPackage = event.localState as? String
                            val toPackage = target.tag as? String
                            if (fromPackage != null && toPackage != null && fromPackage != toPackage) {
                                val assignments = folderMap()
                                val cameFromFolder = assignments.remove(fromPackage) != null
                                if (cameFromFolder) saveFolderMap(assignments)
                                reorderApp(fromPackage, toPackage)
                                if (cameFromFolder) {
                                    lastFocusedPackage = fromPackage
                                    persistDesktopPosition()
                                }
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_ENDED -> {
                            target.alpha = 1f
                            true
                        }
                        else -> true
                    }
                }
            }

            val icon = ImageView(this).apply {
                setImageDrawable(appIcon(info))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = getDrawable(R.drawable.pic_icon_bg)
                val iconPadding = (12f * density).toInt()
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                contentDescription = appLabel(info)
            }
            // The extracted original pic_icon_bg asset is a wide 360:224 tile.
            // Keep the rendered tile close to that source aspect ratio.
            item.addView(icon, LinearLayout.LayoutParams(
                (iconWidthDp * density).toInt(),
                (iconHeightDp * density).toInt()
            ))

            val label = TextView(this).apply {
                text = appLabel(info)
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(0, (7f * density).toInt(), 0, 0)
            }
            item.addView(label, LinearLayout.LayoutParams(-1, -2))

            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = (cardHeightDp * density).toInt()
                columnSpec = GridLayout.spec(index % 6, 1f)
                rowSpec = GridLayout.spec(index / 6)
                val margin = (cardMarginDp * density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(item, lp)
        }

        val pages = ((desktopList.size - 1).coerceAtLeast(0)) / pageSize + 1
        pageLabel.text = if (desktopList.isEmpty()) "尚無可顯示的 APP" else "${page + 1} / $pages"
        renderFolders()
        if (grid.childCount > 0 && (firstFocusOnRender || !grid.hasFocus())) {
            val remembered = visible.indexOfFirst {
                it.activityInfo.packageName == lastFocusedPackage
            }
            val target = if (remembered >= 0) remembered
                else lastFocusedIndex.coerceIn(0, grid.childCount - 1)
            grid.getChildAt(target).requestFocus()
            firstFocusOnRender = false
        } else if (grid.childCount == 0 && (firstFocusOnRender || !grid.hasFocus())) {
            // If every app is hidden or assigned to folders, keep a usable
            // D-pad target instead of leaving the TV remote without focus.
            firstFocusOnRender = false
            if (!focusFirstFolder() && ::desktopSettingsButton.isInitialized) {
                desktopSettingsButton.requestFocus()
            }
        }
    }

    private fun renderFolders() {
        foldersContainer.removeAllViews()
        // Read persisted folder assignments once per render instead of
        // reparsing the same JSON for every folder tile.
        val assignments = folderMap()
        val folderCounts = assignments.values.groupingBy { it }.eachCount()
        val folderNames = folderCounts.keys.sorted()
        if (folderNames.isEmpty()) {
            foldersContainer.visibility = View.GONE
            return
        }
        foldersContainer.visibility = View.VISIBLE
        folderNames.forEach { name ->
            val count = folderCounts[name] ?: 0
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(14f), dp(8f), dp(16f), dp(8f))
                background = getDrawable(R.drawable.icon_menu_folder_nor)
                tag = name
                setOnFocusChangeListener { v, focused ->
                    if (focused) {
                        lastFocusedFolderName = name
                        prefs.edit().putString("last_focused_folder", name).apply()
                    }
                    v.background = getDrawable(
                        if (focused) R.drawable.icon_menu_folder_foc
                        else R.drawable.icon_menu_folder_nor
                    )
                    v.animate().scaleX(if (focused) 1.06f else 1f)
                        .scaleY(if (focused) 1.06f else 1f).setDuration(130).start()
                    v.elevation = if (focused) 8f else 0f
                }
                setOnClickListener { openFolder(name) }
                setOnLongClickListener { showFolderOptions(name); true }
                setOnDragListener { target, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> true
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                            target.alpha = 0.65f
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_EXITED,
                        android.view.DragEvent.ACTION_DRAG_ENDED -> {
                            target.alpha = 1f
                            true
                        }
                        android.view.DragEvent.ACTION_DROP -> {
                            target.alpha = 1f
                            val pkg = event.localState as? String
                            if (pkg != null && moveAppToFolder(pkg, name)) {
                                renderPage()
                            }
                            true
                        }
                        else -> true
                    }
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.file_manager_icon_file_folder)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(38f), dp(38f)))
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), 0, 0, 0)
            }
            val label = TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subLabel = TextView(this).apply {
                text = "$count 個 APP"
                textSize = 11f
                setTextColor(0xFFCFD8E5.toInt())
            }
            textColumn.addView(label)
            textColumn.addView(subLabel)
            tile.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            foldersContainer.addView(tile, LinearLayout.LayoutParams(dp(150f), dp(58f)).apply {
                marginEnd = dp(12f)
            })
        }
    }

    private fun assignToFolder(info: android.content.pm.ResolveInfo) {
        val names = folderMap().values.distinct().sorted()
        val choices = arrayOf("＋ 新增資料夾") + names.toTypedArray()
        AlertDialog.Builder(this).setTitle("加入資料夾")
            .setItems(choices) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply { hint = "輸入資料夾名稱" }
                    AlertDialog.Builder(this).setTitle("建立資料夾")
                        .setView(input)
                        .setPositiveButton("建立") { _, _ ->
                            val name = input.text.toString().trim()
                            when {
                                name.isBlank() -> showMessage("請輸入資料夾名稱")
                                name.length > 16 -> showMessage("資料夾名稱最多 16 個字")
                                folderMap().values.contains(name) ->
                                    showMessage("已有同名資料夾，請直接選擇既有資料夾")
                                else -> {
                                    val map = folderMap()
                                    map[info.activityInfo.packageName] = name
                                    saveFolderMap(map)
                                    refreshAppsPreservingFocus()
                                }
                            }
                        }.setNegativeButton("取消", null).show()
                } else {
                    val map = folderMap()
                    map[info.activityInfo.packageName] = names[which - 1]
                    saveFolderMap(map)
                    refreshAppsPreservingFocus()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun openFolder(name: String, focusPackage: String? = null) {
        val map = folderMap()
        val members = apps.filter { map[it.activityInfo.packageName] == name }
        if (members.isEmpty()) {
            showMessage("資料夾目前沒有應用程式")
            return
        }
        val rememberedFolderPackage = focusPackage
            ?: prefs.getString("folder_focus_$name", null)
        val rememberedIndex = members.indexOfFirst {
            it.activityInfo.packageName == rememberedFolderPackage
        }
        val initialFolderPackage = members[
            rememberedIndex.takeIf { it >= 0 } ?: 0
        ].activityInfo.packageName
        // Repair a stale/missing folder focus record immediately so future
        // openings always restore to an actual member of this folder.
        if (rememberedFolderPackage != initialFolderPackage ||
            prefs.getString("folder_focus_$name", null) != initialFolderPackage
        ) {
            prefs.edit().putString("folder_focus_$name", initialFolderPackage).apply()
        }
        lastFocusedFolderName = name
        prefs.edit().putString("last_focused_folder", name).apply()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(28f), dp(22f), dp(28f), dp(24f))
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(0xF0222935.toInt())
                setStroke(1, 0x66FFFFFF)
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = name
            textSize = 22f
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val count = TextView(this).apply {
            text = "${members.size} 個應用程式"
            textSize = 13f
            setTextColor(0xFFCFD8E5.toInt())
        }
        header.addView(count)
        panel.addView(header)

        lateinit var folderDialog: AlertDialog
        var launchingFromFolder = false
        val appGrid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(20f), 0, dp(4f))
        }
        val folderItems = mutableListOf<View>()
        val folderActionButtons = mutableListOf<View>()
        members.forEachIndexed { index, info ->
            val item = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = getDrawable(R.drawable.bg_app_list_item)
                setOnFocusChangeListener { v, focused ->
                    if (focused) {
                        lastFocusedFolderName = name
                        prefs.edit().putString(
                            "folder_focus_$name", info.activityInfo.packageName
                        ).apply()
                    }
                    v.background = getDrawable(
                        if (focused) R.drawable.focus_app_list_item else R.drawable.bg_app_list_item
                    )
                    v.animate().scaleX(if (focused) 1.055f else 1f)
                        .scaleY(if (focused) 1.055f else 1f).setDuration(140).start()
                    v.elevation = if (focused) 10f else 0f
                }
                setOnClickListener {
                    launchingFromFolder = true
                    folderDialog.dismiss()
                    launchApp(info)
                }
                setOnLongClickListener { view ->
                    val packageName = info.activityInfo.packageName
                    val clip = ClipData.newPlainText("app_package", packageName)
                    val shadow = View.DragShadowBuilder(view)
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        view.startDragAndDrop(clip, shadow, packageName, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        view.startDrag(clip, shadow, packageName, 0)
                    }
                    true
                }
            }
            val icon = ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(66f), dp(66f)))
            val label = TextView(this).apply {
                text = appLabel(info)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(0, dp(8f), 0, 0)
            }
            item.addView(label, LinearLayout.LayoutParams(-1, -2))
            val actions = TextView(this).apply {
                text = "⋮"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(0xFFEAF1FA.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = "更多操作"
                background = focusBackground(false)
                setOnFocusChangeListener { v, focused ->
                    v.background = focusBackground(focused)
                    v.animate().scaleX(if (focused) 1.08f else 1f)
                        .scaleY(if (focused) 1.08f else 1f).setDuration(110).start()
                    v.elevation = if (focused) dp(8f).toFloat() else 0f
                    if (focused) {
                        lastFocusedFolderName = name
                        prefs.edit().putString(
                            "folder_focus_$name", info.activityInfo.packageName
                        ).apply()
                    }
                }
                setOnClickListener {
                    val actionDialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle(appLabel(info))
                        .setItems(arrayOf("開啟 APP", "在資料夾內上移", "在資料夾內下移", "移出資料夾")) { _, action ->
                            val packageName = info.activityInfo.packageName
                            when (action) {
                                0 -> {
                                    launchingFromFolder = true
                                    folderDialog.dismiss()
                                    launchApp(info)
                                }
                                1, 2 -> {
                                    val moved = moveFolderApp(
                                        packageName, name, if (action == 1) -1 else 1
                                    )
                                    if (!moved) {
                                        showMessage(if (action == 1) "已經是資料夾第一個 APP" else "已經是資料夾最後一個 APP")
                                    } else {
                                        folderDialog.dismiss()
                                        uiHandler.post { openFolder(name, packageName) }
                                    }
                                }
                                3 -> {
                                    val updated = folderMap()
                                    updated.remove(packageName)
                                    saveFolderMap(updated)
                                    val remaining = apps.filter {
                                        updated[it.activityInfo.packageName] == name
                                    }
                                    // 優先選取被移除項目原本位置的下一個 APP；若已是最後一個，則選前一個。
                                    val nextIndex = index.coerceAtMost(remaining.lastIndex)
                                    val nextFocus = remaining.getOrNull(nextIndex)
                                        ?.activityInfo?.packageName
                                    folderDialog.dismiss()
                                    renderPage()
                                    if (remaining.isNotEmpty()) {
                                        uiHandler.post { openFolder(name, nextFocus) }
                                    } else showMessage("資料夾已清空，APP 已移回桌面")
                                }
                            }
                        }.setNegativeButton("取消", null).create()
                    actionDialog.setOnDismissListener {
                        // Returning from the app-action list restores focus to
                        // the same APP's overflow control, unless the folder
                        // itself is being closed or rebuilt by an action.
                        if (activeFolderDialog === folderDialog &&
                            folderDialog.isShowing && !launchingFromFolder
                        ) {
                            uiHandler.post {
                                if (actions.isAttachedToWindow && actions.isFocusable) {
                                    actions.requestFocus()
                                } else {
                                    item.requestFocus()
                                }
                            }
                        }
                    }
                    actionDialog.show()
                }
            }
            item.addView(actions, LinearLayout.LayoutParams(dp(42f), dp(34f)))
            folderItems.add(item)
            folderActionButtons.add(actions)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                // Reserve room for icon, label, and the focusable more-actions control.
                height = dp(148f)
                columnSpec = GridLayout.spec(index % 4, 1f)
                rowSpec = GridLayout.spec(index / 4)
                val gap = dp(6f)
                setMargins(gap, gap, gap, gap)
            }
            appGrid.addView(item, lp)
        }
        // Give the TV remote a predictable four-column focus map. Android's
        // default geometric search can otherwise jump to the overflow action
        // or skip a tile when the final row is incomplete.
        folderItems.indices.forEach { index ->
            val left = if (index % 4 > 0) index - 1 else -1
            val right = if (index % 4 < 3 && index + 1 < folderItems.size) index + 1 else -1
            val up = index - 4
            val down = index + 4
            val item = folderItems[index]
            item.nextFocusLeftId = if (left >= 0) folderItems[left].id else item.id
            item.nextFocusRightId = if (right >= 0) folderItems[right].id else item.id
            item.nextFocusUpId = if (up >= 0) folderItems[up].id else item.id
            item.nextFocusDownId = if (down < folderItems.size) folderItems[down].id else item.id
            val actions = folderActionButtons[index]
            actions.nextFocusLeftId = if (left >= 0) folderItems[left].id else item.id
            actions.nextFocusRightId = if (right >= 0) folderItems[right].id else item.id
            actions.nextFocusUpId = if (up >= 0) folderItems[up].id else item.id
            actions.nextFocusDownId = if (down < folderItems.size) folderItems[down].id else item.id
        }
        panel.addView(appGrid)

        val hint = TextView(this).apply {
            text = "OK 開啟　長按移出　返回關閉"
            textSize = 12f
            setTextColor(0xFFB8C4D4.toInt())
            setPadding(dp(2f), dp(12f), 0, 0)
        }
        panel.addView(hint)

        folderDialog = AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("關閉", null).create()
        folderDialog.setOnShowListener {
            folderDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            folderDialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            val focusIndex = members.indexOfFirst {
                it.activityInfo.packageName == initialFolderPackage
            }.takeIf { it >= 0 } ?: 0
            appGrid.getChildAt(focusIndex)?.requestFocus()
        }
        folderDialog.setOnDismissListener {
            if (activeFolderDialog === folderDialog) {
                activeFolderDialog = null
            }
            if (!launchingFromFolder) {
                val folderTile = (0 until foldersContainer.childCount)
                    .map { foldersContainer.getChildAt(it) }
                    .firstOrNull { it.tag == name && it.isFocusable }
                if (folderTile != null) {
                    folderTile.requestFocus()
                } else {
                    // The folder may have been emptied or removed while its
                    // dialog was open. Never leave TV focus on a detached view.
                    val desktop = desktopApps()
                    if (desktop.isNotEmpty()) {
                        val targetIndex = lastFocusedPackage?.let { pkg ->
                            desktop.indexOfFirst { it.activityInfo.packageName == pkg }
                        }?.takeIf { it >= 0 } ?: (
                            page * pageSize + lastFocusedIndex
                        ).coerceIn(0, desktop.lastIndex)
                        page = targetIndex / pageSize
                        lastFocusedIndex = targetIndex % pageSize
                        lastFocusedPackage = desktop[targetIndex].activityInfo.packageName
                        firstFocusOnRender = true
                        renderPage()
                        persistDesktopPosition()
                    } else if (::desktopSettingsButton.isInitialized) {
                        desktopSettingsButton.requestFocus()
                    }
                }
            }
        }
        activeFolderDialog = folderDialog
        folderDialog.show()
    }

    private fun showFolderOptions(name: String) {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("開啟資料夾", "修改名稱", "刪除資料夾")) { _, which ->
                when (which) {
                    0 -> openFolder(name)
                    1 -> {
                        val input = EditText(this).apply { setText(name); selectAll() }
                        AlertDialog.Builder(this).setTitle("修改資料夾名稱").setView(input)
                            .setPositiveButton("儲存") { _, _ ->
                                val newName = input.text.toString().trim()
                                when {
                                    newName.isBlank() -> showMessage("資料夾名稱不可空白")
                                    newName.length > 16 -> showMessage("資料夾名稱最多 16 個字")
                                    newName != name && folderMap().values.contains(newName) ->
                                        showMessage("已有同名資料夾，請使用其他名稱")
                                    else -> {
                                        val map = folderMap()
                                        map.entries.filter { it.value == name }
                                            .forEach { it.setValue(newName) }
                                        saveFolderMap(map)
                                        lastFocusedFolderName = newName
                                        prefs.edit()
                                            .putString("last_focused_folder", newName)
                                            .apply()
                                        renderPage()
                                    }
                                }
                            }.setNegativeButton("取消", null).show()
                    }
                    2 -> {
                        val memberCount = folderMap().values.count { it == name }
                        AlertDialog.Builder(this)
                            .setTitle("刪除資料夾")
                            .setMessage("確定刪除「$name」嗎？\n資料夾內的 $memberCount 個 APP 會移回桌面，不會解除安裝。")
                            .setPositiveButton("刪除") { _, _ ->
                                val map = folderMap()
                                map.entries.removeAll { it.value == name }
                                saveFolderMap(map)
                                renderPage()
                                if (!focusFirstFolder() && ::desktopSettingsButton.isInitialized) {
                                    desktopSettingsButton.requestFocus()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun saveAppOrder() {
        val orderedPackages = apps.map { it.activityInfo.packageName }
            .filter { it.isNotBlank() }
            .distinct()
        prefs.edit().putString("app_order", orderedPackages.joinToString("|")).apply()
    }

    private fun hideApp(info: android.content.pm.ResolveInfo) {
        val hidden = (prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()).toMutableSet()
        hidden.add(info.activityInfo.packageName)
        prefs.edit().putStringSet("hidden_apps", hidden).apply()
        refreshAppsPreservingFocus()
    }

    private fun showHiddenApps() {
        val hidden = (prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()).toMutableSet()
        if (hidden.isEmpty()) {
            showMessage("目前沒有隱藏的 APP")
            return
        }
        val pm = packageManager
        val installed = mutableListOf<android.content.pm.ResolveInfo>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        )
        val found = mutableListOf<android.content.pm.ResolveInfo>()
        intents.forEach { intent ->
            val matches = if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            found.addAll(matches)
        }
        installed.addAll(found.filter { hidden.contains(it.activityInfo.packageName) }
            .distinctBy { it.activityInfo.packageName })
        if (installed.isEmpty()) {
            prefs.edit().remove("hidden_apps").apply()
            showMessage("隱藏清單中的 APP 已不存在")
            return
        }
        val labels = installed.map { it.loadLabel(pm).toString() }.toTypedArray()
        AlertDialog.Builder(this).setTitle("顯示隱藏的 APP")
            .setItems(labels) { _, which ->
                val restoredPackage = installed[which].activityInfo.packageName
                hidden.remove(restoredPackage)
                prefs.edit().putStringSet("hidden_apps", hidden).apply()
                loadApps()
                val refreshed = desktopApps()
                val restoredIndex = refreshed.indexOfFirst {
                    it.activityInfo.packageName == restoredPackage
                }
                if (restoredIndex >= 0) {
                    page = restoredIndex / pageSize
                    lastFocusedIndex = restoredIndex % pageSize
                    lastFocusedPackage = restoredPackage
                    firstFocusOnRender = true
                    renderPage()
                    persistDesktopPosition()
                }
            }.setNegativeButton("關閉", null).show()
    }

    private fun reorderApp(fromPackage: String, toPackage: String) {
        val from = apps.indexOfFirst { it.activityInfo.packageName == fromPackage }
        val to = apps.indexOfFirst { it.activityInfo.packageName == toPackage }
        if (from < 0 || to < 0 || from == to) return
        val item = apps.removeAt(from)
        apps.add(to, item)
        saveAppOrder()
        val displayIndex = desktopApps().indexOfFirst {
            it.activityInfo.packageName == fromPackage
        }
        page = displayIndex.coerceAtLeast(0) / pageSize
        lastFocusedPackage = fromPackage
        lastFocusedIndex = displayIndex.coerceAtLeast(0) % pageSize
        firstFocusOnRender = true
        renderPage()
    }

    private fun moveApp(info: android.content.pm.ResolveInfo, direction: Int) {
        val from = apps.indexOfFirst { it.activityInfo.packageName == info.activityInfo.packageName }
        val to = (from + direction).coerceIn(0, apps.lastIndex)
        if (from < 0 || from == to) return
        val item = apps.removeAt(from)
        apps.add(to, item)
        saveAppOrder()
        val displayIndex = desktopApps().indexOfFirst { it.activityInfo.packageName == info.activityInfo.packageName }
        page = (displayIndex.coerceAtLeast(0)) / pageSize
        lastFocusedPackage = info.activityInfo.packageName
        lastFocusedIndex = (displayIndex.coerceAtLeast(0)) % pageSize
        firstFocusOnRender = true
        renderPage()
    }

    private fun launchApp(info: android.content.pm.ResolveInfo) {
        val pkg = info.activityInfo.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(pkg, info.activityInfo.name)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (launchIntent.resolveActivity(packageManager) == null) {
                showMessage("此 APP 目前無法啟動，請重新整理桌面")
                uiHandler.removeCallbacks(packageRefreshTask)
                uiHandler.postDelayed(packageRefreshTask, 300L)
                return
            }
            startActivity(launchIntent)
        } catch (_: SecurityException) {
            showMessage("系統限制啟動此 APP")
        } catch (_: Exception) {
            showMessage("無法啟動此 APP，請稍後再試")
            uiHandler.removeCallbacks(packageRefreshTask)
            uiHandler.postDelayed(packageRefreshTask, 300L)
        }
    }

    private fun appCardBackground(focused: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (focused) intArrayOf(0xFF243F60.toInt(), 0xFF15263D.toInt())
            else intArrayOf(0xCC182536.toInt(), 0xCC101A29.toInt())
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setStroke(if (focused) 3 else 1,
                if (focused) 0xFF65B8FF.toInt() else 0x44FFFFFF)
        }

    private fun appIconPlate(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF263B55.toInt(), 0xFF17263A.toInt())
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setStroke(1, 0x33FFFFFF)
        }

    private fun focusBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            if (focused) {
                setColor(0x553B8CFF)
                setStroke(4, blue)
            } else {
                setColor(0x33202B3D)
                setStroke(1, 0x55FFFFFF)
            }
        }

    private fun menuAction(
        title: String,
        description: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            background = focusBackground(false)
            setOnFocusChangeListener { v, focused ->
                v.background = focusBackground(focused)
                v.animate().scaleX(if (focused) 1.025f else 1f)
                    .scaleY(if (focused) 1.025f else 1f).setDuration(110).start()
            }
            setOnClickListener { onClick() }
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
        }
        val detail = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFFBFCBDA.toInt())
            setPadding(0, dp(4f), 0, 0)
        }
        row.addView(heading)
        row.addView(detail)
        return row
    }

    private fun showMenuPanel(titleText: String, actions: List<Triple<String, String, () -> Unit>>) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(22f), dp(24f), dp(20f))
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0xF0222935.toInt())
                setStroke(1, 0x66FFFFFF)
            }
        }
        val title = TextView(this).apply {
            text = titleText
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(4f), 0, dp(4f), dp(16f))
        }
        panel.addView(title)
        actions.forEachIndexed { index, action ->
            panel.addView(menuAction(action.first, action.second) {
                menuDialog?.dismiss()
                action.third()
            }, LinearLayout.LayoutParams(-1, dp(64f)).apply {
                bottomMargin = if (index == actions.lastIndex) 0 else dp(8f)
            })
        }
        menuDialog = AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("關閉", null).create()
        menuDialog?.setOnShowListener {
            menuDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            menuDialog?.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.42f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            (panel.getChildAt(1) as? View)?.requestFocus()
        }
        menuDialog?.show()
    }

    private var menuDialog: AlertDialog? = null
    private var activeFolderDialog: AlertDialog? = null

    private fun openHomeSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val roleManager = getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                ) {
                    startActivity(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                    return
                }
            } catch (_: Exception) {
                // Some TV firmware exposes HOME settings but blocks the role request UI.
            }
        }
        if (startSystemActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))) return
        if (startSystemActivity(Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))) return
        showMessage("此電視未提供直接切換預設桌面的設定頁。請至 Android TV 設定 → 應用程式 → 預設應用程式 → 主畫面，選擇「當貝桌面重建版」。")
    }

    private fun startSystemActivity(intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(packageManager) == null) return false
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showDesktopOptions() {
        showMenuPanel("桌面設定", listOf(
            Triple("重新掃描 APP", "重新讀取電視已安裝的應用程式") {
                refreshAppsPreservingFocus()
            },
            Triple("管理隱藏 APP", "查看並還原已隱藏的應用程式") { showHiddenApps() },
            Triple("設為預設桌面", "開啟 Android TV 的 HOME 啟動器選擇") {
                openHomeSettings()
            },
            Triple("系統設定", "開啟 Android 系統設定") {
                if (!startSystemActivity(Intent(android.provider.Settings.ACTION_SETTINGS))) {
                    showMessage("此電視未提供可直接開啟的系統設定頁。")
                }
            },
            Triple("關於", "查看重建版本與參考來源") {
                showMessage("當貝桌面重建版 ${BuildConfig.VERSION_NAME}\\n以當貝桌面 4.1.7 為參考")
            }
        ))
    }

    private fun showAppOptions(info: android.content.pm.ResolveInfo) {
        val appName = appLabel(info)
        showMenuPanel(appName, listOf(
            Triple("開啟 APP", "啟動此應用程式") { launchApp(info) },
            Triple("加入資料夾", "將 APP 收納至資料夾") { assignToFolder(info) },
            Triple("移至前一位", "在桌面排列中向前移動") { moveApp(info, -1) },
            Triple("移至後一位", "在桌面排列中向後移動") { moveApp(info, 1) },
            Triple("從桌面隱藏", "隱藏圖示，不會解除安裝 APP") { hideApp(info) },
            Triple("應用程式資訊", "開啟 Android 系統 APP 資訊頁") {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:${info.activityInfo.packageName}")
                if (!startSystemActivity(intent)) {
                    showMessage("無法開啟此 APP 的系統資訊頁")
                }
            },
            Triple("解除安裝 APP", "交由 Android 系統確認解除安裝") {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.parse("package:${info.activityInfo.packageName}")
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    showMessage("此 APP 不支援由桌面解除安裝，請至系統設定管理")
                }
            }
        ))
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("確定", null).show()
    }

    private fun focusFirstFolder(): Boolean {
        for (i in 0 until foldersContainer.childCount) {
            val child = foldersContainer.getChildAt(i)
            if (child.visibility == View.VISIBLE && child.isFocusable &&
                child.tag == lastFocusedFolderName) return child.requestFocus()
        }
        for (i in 0 until foldersContainer.childCount) {
            val child = foldersContainer.getChildAt(i)
            if (child.visibility == View.VISIBLE && child.isFocusable) return child.requestFocus()
        }
        return false
    }

    private fun focusLastAppInColumn(column: Int): Boolean {
        if (grid.childCount == 0) return false
        val candidates = (0 until grid.childCount)
            .filter { it % 6 == column.coerceIn(0, 5) }
        val target = candidates.lastOrNull() ?: grid.childCount - 1
        return grid.getChildAt(target).requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                val focusedIndex = grid.indexOfChild(currentFocus)
                if (focusedIndex >= 0) {
                    val info = desktopApps().getOrNull(page * pageSize + focusedIndex)
                    if (info != null) showAppOptions(info) else showDesktopOptions()
                } else {
                    showDesktopOptions()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val index = grid.indexOfChild(currentFocus)
                val isRightEdge = index >= 0 &&
                    (index % 6 == 5 || index == grid.childCount - 1)
                if (isRightEdge && (page + 1) * pageSize < desktopApps().size) {
                    // A held remote key emits repeat events; turn a page only once per press.
                    if ((event?.repeatCount ?: 0) > 0) return true
                    val column = index % 6
                    val row = index / 6
                    val nextPageCount = (desktopApps().size - (page + 1) * pageSize)
                        .coerceIn(0, pageSize)
                    val targetRow = if (row == 1 && nextPageCount > 6) 1 else 0
                    val targetIndex = (targetRow * 6 + column)
                        .coerceAtMost(nextPageCount - 1).coerceAtLeast(0)
                    page++
                    lastFocusedIndex = targetIndex
                    lastFocusedPackage = desktopApps().getOrNull(page * pageSize + targetIndex)
                        ?.activityInfo?.packageName
                    firstFocusOnRender = true
                    renderPage()
                    persistDesktopPosition()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val index = grid.indexOfChild(currentFocus)
                if (index >= 0 && index % 6 == 0 && page > 0) {
                    // Avoid skipping several pages when the user holds LEFT.
                    if ((event?.repeatCount ?: 0) > 0) return true
                    val row = index / 6
                    val desktop = desktopApps()
                    val previousPage = page - 1
                    // Count items on the destination page, not the current page.
                    // The current page may be the short final page.
                    val previousPageCount = (desktop.size - previousPage * pageSize)
                        .coerceIn(0, pageSize)
                    val targetRow = if (row == 1 && previousPageCount > 6) 1 else 0
                    val targetIndex = (targetRow * 6 + 5)
                        .coerceAtMost(previousPageCount - 1)
                        .coerceAtLeast(0)
                    page = previousPage
                    lastFocusedIndex = targetIndex
                    lastFocusedPackage = desktop.getOrNull(page * pageSize + targetIndex)
                        ?.activityInfo?.packageName
                    firstFocusOnRender = true
                    renderPage()
                    persistDesktopPosition()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val focusedFolderIndex = (0 until foldersContainer.childCount)
                    .firstOrNull { foldersContainer.getChildAt(it) == currentFocus }
                if (focusedFolderIndex != null && ::desktopSettingsButton.isInitialized) {
                    // Keep the folder-to-footer route deterministic on TV remotes.
                    desktopSettingsButton.requestFocus()
                    return true
                }
                val index = grid.indexOfChild(currentFocus)
                if (index >= 6 || (index >= 0 && index == grid.childCount - 1)) {
                    if (focusFirstFolder()) return true
                    if (::desktopSettingsButton.isInitialized) {
                        desktopSettingsButton.requestFocus()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val folderIndex = (0 until foldersContainer.childCount)
                    .firstOrNull { foldersContainer.getChildAt(it) == currentFocus }
                if (folderIndex != null) {
                    // Moving UP from a folder returns to the app grid in the
                    // previously used column, instead of jumping to column one.
                    focusLastAppInColumn(lastFocusedIndex % 6)
                    return true
                }
                if (currentFocus == desktopSettingsButton) {
                    if (!focusFirstFolder()) focusLastAppInColumn(0)
                    return true
                }
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (grid.hasFocus() && desktopApps().size > pageSize) {
                    if ((event?.repeatCount ?: 0) > 0) return true
                    val next = page + 1
                    if (next * pageSize < desktopApps().size) {
                        page = next
                        lastFocusedIndex = 0
                        lastFocusedPackage = desktopApps().getOrNull(page * pageSize)
                            ?.activityInfo?.packageName
                        firstFocusOnRender = true
                        renderPage()
                        persistDesktopPosition()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (grid.hasFocus() && page > 0) {
                    if ((event?.repeatCount ?: 0) > 0) return true
                    page--
                    lastFocusedIndex = (desktopApps().size - page * pageSize - 1)
                        .coerceIn(0, pageSize - 1)
                    lastFocusedPackage = desktopApps().getOrNull(page * pageSize + lastFocusedIndex)
                        ?.activityInfo?.packageName
                    firstFocusOnRender = true
                    renderPage()
                    persistDesktopPosition()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    activeFolderDialog?.isShowing == true -> activeFolderDialog?.dismiss()
                    menuDialog?.isShowing == true -> menuDialog?.dismiss()
                    else -> showDesktopOptions()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerPackageReceiver() {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageChangeReceiver, filter)
        }
        packageReceiverRegistered = true
    }

    private fun unregisterPackageReceiver() {
        if (!packageReceiverRegistered) return
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        packageReceiverRegistered = false
        uiHandler.removeCallbacks(packageRefreshTask)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        registerPackageReceiver()
        if (::grid.isInitialized) {
            // Returning from another app uses the same focus-preserving refresh
            // path as package install/update broadcasts.
            updateClock()
            updateNetworkStatus()
            refreshAppsPreservingFocus()
            uiHandler.removeCallbacks(statusRefreshTask)
            uiHandler.postDelayed(statusRefreshTask, 30_000L)
        }
    }

    private fun persistDesktopPosition() {
        prefs.edit()
            .putInt("last_page", page.coerceAtLeast(0))
            .putString("last_focused_package", lastFocusedPackage)
            .putInt("last_focused_index", lastFocusedIndex.coerceAtLeast(0))
            .apply()
    }

    override fun onPause() {
        persistDesktopPosition()
        uiHandler.removeCallbacks(statusRefreshTask)
        unregisterPackageReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statusRefreshTask)
        unregisterPackageReceiver()
        activeFolderDialog = null
        menuDialog = null
        super.onDestroy()
    }
}
