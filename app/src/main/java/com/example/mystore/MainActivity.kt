package com.example.mystore

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class State { NOT_INSTALLED, UPDATE, INSTALLED }

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var installer: SilentInstaller

    private var apps: List<AppEntry> = emptyList()
    private val installing = mutableSetOf<String>()
    private val adapter = AppAdapter()
    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainPage: View
    private var settingsPage: View? = null
    private var lockPage: View? = null

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var grid: GridView
    private lateinit var emptyView: View
    private var settingsTab = 0

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("pkg")?.let { installing.remove(it) }
            adapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        installer = SilentInstaller(this)
        buildUi()
        askNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(InstallResultReceiver.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refreshReceiver, filter)
        }
        installing.clear()
        applyPrivacy()
        updateLock()
        reload()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(refreshReceiver)
        // Lock again whenever the store leaves the screen (not on rotation). The lock
        // overlay is added right now, so it is already in place when the store returns.
        if (!isChangingConfigurations) {
            AppState.unlocked = false
            updateLock()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage != null) closeSettings() else super.onBackPressed()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.askedNotifications
        ) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------------ UI building

    private fun buildUi(openSettings: Boolean = false) {
        p = Themes.palette(this, prefs)
        applySystemBars()

        rootFrame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        mainPage = buildMainPage()
        rootFrame.addView(mainPage, FrameLayout.LayoutParams(MATCH, MATCH))
        settingsPage = null
        lockPage = null
        updateLock()              // lock overlay goes in before the first frame is drawn
        applyPrivacy()
        setContentView(rootFrame)

        refreshListUi()
        if (openSettings) showSettings()
    }

    /** Hide the store from the recent-apps thumbnail while a code is set. */
    private fun applyPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!prefs.lockEnabled)
    }

    private fun isLocked() = prefs.lockEnabled && !AppState.unlocked

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        var flags = window.decorView.systemUiVisibility
        flags = if (p.dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = rippleBg(roundedBg(p.surface, 22, p.border, 1), withAlpha(p.text, 0x1A))
            isClickable = true
            contentDescription = desc
            setOnClickListener { onClick() }
            addView(
                icon(res, p.text),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun buildMainPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header: title + settings button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(4))
        }
        header.addView(
            tv(getString(R.string.app_name), 21f, p.text, true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        header.addView(iconButton(R.drawable.ic_settings, "הגדרות") {
            settingsTab = 0
            showSettings()
        })
        page.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // grid
        val columns = maxOf(3, (resources.displayMetrics.widthPixels /
                resources.displayMetrics.density / 100).toInt())
        grid = GridView(this).apply {
            numColumns = columns
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            setPadding(dp(16), dp(10), dp(16), dp(20))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onAppClick(apps[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onAppLongClick(apps[pos]) }
        }

        // empty state
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(32), dp(32), dp(32), dp(32))
            val ic = icon(R.drawable.ic_store, withAlpha(p.textDim, 0x99))
            addView(ic, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(tv("אין אפליקציות להצגה", 17f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(tv("לא נמצאו אפליקציות בתיקייה:\n${Config.APK_DIRS.joinToString("\n")}", 13f, p.textDim).apply {
                gravity = Gravity.CENTER
            })
            addView(tv("משוך את המסך למטה כדי לרענן", 12f, p.textDim).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }

        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(p.accent)
            setProgressBackgroundColorSchemeColor(p.surface)
            setOnChildScrollUpCallback { _, _ -> grid.canScrollVertically(-1) }
            setOnRefreshListener { reload() }
            addView(FrameLayout(this@MainActivity).apply {
                addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
            })
        }
        page.addView(swipe, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return page
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        bg.execute {
            val result = ApkScanner.scan(this)
            runOnUiThread {
                apps = result
                refreshListUi()
                swipe.isRefreshing = false
            }
        }
    }

    private fun refreshListUi() {
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun stateOf(app: AppEntry): State = try {
        val info = packageManager.getPackageInfo(app.packageName, 0)
        if (ApkScanner.versionOf(info) < app.versionCode) State.UPDATE else State.INSTALLED
    } catch (e: PackageManager.NameNotFoundException) {
        State.NOT_INSTALLED
    }

    private fun onAppClick(app: AppEntry) {
        if (app.packageName in installing) return
        if (stateOf(app) == State.INSTALLED) {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) startActivity(launch) else toast("אין מסך הפעלה לאפליקציה")
            return
        }
        installing.add(app.packageName)
        adapter.notifyDataSetChanged()
        Thread {
            try {
                installer.install(app)
            } catch (e: Exception) {
                runOnUiThread {
                    installing.remove(app.packageName)
                    adapter.notifyDataSetChanged()
                    toast("התקנה נכשלה: ${e.message}")
                }
            }
        }.start()
    }

    private fun onAppLongClick(app: AppEntry): Boolean {
        if (stateOf(app) == State.NOT_INSTALLED) return false
        confirmDialog(p, "הסרת אפליקציה", "להסיר את ${app.label}?", "הסר") {
            try {
                installer.uninstall(app.packageName)
            } catch (e: Exception) {
                toast("הסרה נכשלה: ${e.message}")
            }
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ lock

    private fun updateLock() {
        val locked = isLocked()
        if (locked) {
            if (lockPage == null) {
                val page = buildLockPage()
                lockPage = page
                rootFrame.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        } else {
            lockPage?.let { rootFrame.removeView(it) }
            lockPage = null
        }
        applyContentVisibility()
    }

    /** While locked the store content is not just covered - it is invisible. */
    private fun applyContentVisibility() {
        val locked = isLocked()
        mainPage.visibility = when {
            locked -> View.INVISIBLE
            settingsPage != null -> View.GONE
            else -> View.VISIBLE
        }
        settingsPage?.visibility = if (locked) View.INVISIBLE else View.VISIBLE
    }

    private fun buildLockPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val badge = FrameLayout(this).apply { background = roundedBg(p.accentSoft, 36) }
        badge.addView(
            icon(R.drawable.ic_lock, p.accent),
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        )
        col.addView(badge, LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(tv("החנות נעולה", 24f, p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        col.addView(tv("הזן את הקוד כדי להמשיך", 14f, p.textDim).apply { gravity = Gravity.CENTER })

        lateinit var pad: PinPad
        pad = PinPad(this, p) { pin ->
            if (prefs.checkPin(pin)) {
                AppState.unlocked = true
                updateLock()
            } else {
                pad.clear()
                pad.message("קוד שגוי", true)
                pad.shake()
            }
        }
        col.addView(pad.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        return ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isClickable = true
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    // ------------------------------------------------------------------ settings

    private fun showSettings() {
        settingsPage?.let { rootFrame.removeView(it) }
        val page = buildSettingsPage()
        settingsPage = page
        // index 1: above the main page, below the lock overlay (if any)
        rootFrame.addView(page, 1, FrameLayout.LayoutParams(MATCH, MATCH))
        applyContentVisibility()
    }

    private fun closeSettings() {
        settingsPage?.let { rootFrame.removeView(it) }
        settingsPage = null
        applyContentVisibility()
    }

    private fun buildSettingsPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(32))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        bar.addView(iconButton(R.drawable.ic_back, "חזרה") { closeSettings() })
        bar.addView(tv("הגדרות", 22f, p.text, true).apply { setPadding(dp(14), 0, dp(14), 0) })
        col.addView(bar)

        col.addView(tabBar())

        when (settingsTab) {
            0 -> {
                col.addView(section("מצב תצוגה", displayControl()))
                col.addView(section("ערכת נושא", themeRow()))
            }
            1 -> col.addView(section("נעילת החנות", lockSection()))
            else -> col.addView(section("אודות", aboutContent()))
        }

        col.addView(linked("להכשרת נגנים חייגו 043136429", 13f, Linkify.PHONE_NUMBERS).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isClickable = true
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun linked(t: String, sizeSp: Float, mask: Int): TextView =
        tv(t, sizeSp, p.textDim).apply {
            Linkify.addLinks(this, mask)
            setLinkTextColor(p.accent)
        }

    private fun tabBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("תצוגה", "נעילה", "אודות").forEachIndexed { i, label ->
            val sel = settingsTab == i
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (settingsTab != i) {
                        settingsTab = i
                        showSettings()
                    }
                }
            }
            item.addView(
                tv(label, 15f, if (sel) p.accent else p.textDim, sel)
                    .apply { setPadding(dp(8), dp(10), dp(8), dp(9)) }
            )
            item.addView(
                View(this).apply { background = roundedBg(if (sel) p.accent else Color.TRANSPARENT, 2) },
                LinearLayout.LayoutParams(MATCH, dp(3))
            )
            row.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        wrap.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        wrap.addView(
            View(this).apply { setBackgroundColor(p.border) },
            LinearLayout.LayoutParams(MATCH, dp(1))
        )
        return wrap
    }

    private fun aboutContent(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        col.addView(tv(getString(R.string.app_name), 17f, p.text, true))
        if (ver != null) {
            col.addView(tv("גרסה $ver", 12f, p.textDim).apply { setPadding(0, dp(2), 0, 0) })
        }
        col.addView(tv(
            "לחיצה ארוכה על אפליקציה מותקנת מסירה אותה, ומשיכת המסך כלפי מטה מרעננת את הרשימה.",
            13f, p.textDim
        ).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(
            linked(
                "אנחנו עושים מאמץ לוודא שכל האפליקציות בחנות ללא גישה לווידאו. " +
                        "אם מצאתם משהו שפותח ווידאו אנא דווחו לנו ezerphone0@gmail.com",
                13f, Linkify.EMAIL_ADDRESSES
            ).apply { setPadding(0, dp(14), 0, 0) }
        )
        return col
    }

    private fun section(title: String, content: View): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(tv(title, 13f, p.textDim, true).apply { setPadding(dp(6), dp(10), dp(6), dp(8)) })
        val card = cardView(p, 14)
        card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        wrap.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    private fun displayControl(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(p.surfaceAlt, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("אוטומטי", "בהיר", "כהה").forEachIndexed { i, label ->
            val sel = prefs.displayMode == i
            seg.addView(tv(label, 14f, if (sel) p.onAccent else p.text, sel).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                if (sel) background = roundedBg(p.accent, 11)
                isClickable = true
                setOnClickListener {
                    if (prefs.displayMode != i) {
                        prefs.displayMode = i
                        buildUi(openSettings = true)
                    }
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        col.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(tv("במצב אוטומטי התצוגה עוקבת אחרי הגדרת המכשיר.", 12f, p.textDim)
            .apply { setPadding(dp(4), dp(10), dp(4), 0) })
        return col
    }

    private fun themeRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.accents.forEachIndexed { i, a ->
            val color = if (p.dark) a.dark else a.light
            val selected = prefs.themeIndex == i
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (prefs.themeIndex != i) {
                        prefs.themeIndex = i
                        buildUi(openSettings = true)
                    }
                }
            }
            val swatch = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (selected) setStroke(dp(3), p.text)
                }
            }
            if (selected) {
                swatch.addView(
                    icon(R.drawable.ic_check, Themes.onColor(color)),
                    FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                )
            }
            item.addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)))
            item.addView(tv(a.name, 11f, if (selected) p.text else p.textDim, selected)
                .apply { setPadding(0, dp(6), 0, 0) })
            row.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return row
    }

    private fun lockSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enabled = prefs.lockEnabled
        col.addView(tv(
            if (enabled) "הנעילה פעילה" else "הנעילה כבויה",
            15f, if (enabled) p.success else p.textDim, true
        ))
        col.addView(tv(
            "הקוד (4 עד 8 ספרות) נדרש בכל פתיחה של החנות, ומגן על מסך החנות בלבד.",
            12f, p.textDim
        ).apply { setPadding(0, dp(4), 0, dp(14)) })
        if (!enabled) {
            col.addView(button(p, "הגדר קוד נעילה") { setNewPin() }, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            col.addView(button(p, "שנה קוד", BtnStyle.TONAL) { changePin() }, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(
                button(p, "בטל נעילה", BtnStyle.DANGER) { disableLock() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            )
        }
        return col
    }

    private fun setNewPin() {
        pinDialog(p, "קוד חדש", "בחר קוד של 4 עד 8 ספרות") { first ->
            pinDialog(
                p, "אימות הקוד", "הזן שוב את הקוד",
                verify = { it == first },
                errorText = "הקודים אינם תואמים"
            ) { confirmed ->
                prefs.setPin(confirmed)
                AppState.unlocked = true
                toast("קוד הנעילה הוגדר")
                buildUi(openSettings = true)
            }
        }
    }

    private fun changePin() {
        pinDialog(p, "הקוד הנוכחי", "הזן את הקוד הנוכחי", verify = { prefs.checkPin(it) }) {
            setNewPin()
        }
    }

    private fun disableLock() {
        pinDialog(p, "ביטול נעילה", "הזן את הקוד הנוכחי", verify = { prefs.checkPin(it) }) {
            prefs.clearPin()
            toast("הנעילה בוטלה")
            buildUi(openSettings = true)
        }
    }

    // ------------------------------------------------------------------ grid adapter

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            val busy = app.packageName in installing
            val state = stateOf(app)

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(14), dp(8), dp(12))
                background = rippleBg(roundedBg(p.surface, 20, p.border, 1), withAlpha(p.text, 0x1A))
                alpha = if (busy) 0.55f else 1f
                layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            }

            val iconView = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dpf(11))
                    }
                }
            }
            card.addView(iconView, LinearLayout.LayoutParams(dp(48), dp(48)))

            card.addView(
                tv(app.label, 12.5f, p.text).apply {
                    gravity = Gravity.CENTER
                    minLines = 2
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(6))
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )

            val (pillText, pillFg, pillBg) = when {
                busy -> Triple("מתקין...", p.textDim, p.surfaceAlt)
                state == State.INSTALLED -> Triple("מותקן", p.success, withAlpha(p.success, 0x26))
                state == State.UPDATE -> Triple("עדכון", p.warn, withAlpha(p.warn, 0x26))
                else -> Triple("התקן", p.accent, p.accentSoft)
            }
            card.addView(
                tv(pillText, 11f, pillFg, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = roundedBg(pillBg, 12)
                },
                LinearLayout.LayoutParams(WRAP, WRAP)
            )
            return card
        }
    }
}
