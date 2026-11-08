package com.discord.evs.generator.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.discord.evs.generator.App
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.MainActivity
import com.discord.evs.generator.R
import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.regex.Pattern
import androidx.core.graphics.ColorUtils

/**
 * Background update check + optional top-right download control.
 * Long-press FAB → Move / Remove. Settings can still "Check for updates".
 */
object UpdateManager {
    private const val UPDATE_CHANNEL = "evs_update_channel"
    private const val UPDATE_NOTIFY_ID = 5100

    private var hostRef: WeakReference<Activity>? = null
    private var fab: FrameLayout? = null
    private var fabColorAnim: android.animation.ValueAnimator? = null
    private var progressRing: ProgressBar? = null
    private var icon: ImageView? = null
    private var pending: UpdateInfo? = null
    private var downloading = false
    private var downloadJob: Job? = null
    private var apkFile: File? = null

    /** Drop saved APK when already installed or user finished install flow. */
    private fun clearPendingUpdate(context: Context, deleteFile: Boolean = true) {
        val prefs = Prefs(context.applicationContext)
        val path = prefs.pendingUpdateApkPath.trim()
        if (deleteFile && path.isNotEmpty()) {
            try { File(path).delete() } catch (_: Exception) {}
        }
        prefs.pendingUpdateApkPath = ""
        prefs.pendingUpdateApkVersion = ""
        apkFile = null
    }

    /** True if pending saved version is already this running app. */
    private fun isPendingAlreadyInstalled(context: Context): Boolean {
        val ver = Prefs(context.applicationContext).pendingUpdateApkVersion.trim()
        if (ver.isEmpty()) return false
        val current = BuildConfig.VERSION_NAME.trim()
        return ver == current || ver.removePrefix("v") == current.removePrefix("v")
    }

    fun attach(activity: Activity) {
        hostRef = WeakReference(activity)
        ensureFab(activity)
        val prefs = Prefs(activity.applicationContext)
        if (prefs.updateFabRemoved) {
            fab?.visibility = View.GONE
        } else {
            fab?.visibility = View.GONE
            // Restore FAB only if APK is still pending AND not already this version
            if (isPendingAlreadyInstalled(activity)) {
                clearPendingUpdate(activity, deleteFile = true)
            } else {
                val readyPath = prefs.pendingUpdateApkPath.trim()
                if (readyPath.isNotEmpty()) {
                    val f = File(readyPath)
                    if (f.exists() && f.length() > 1024L) {
                        apkFile = f
                        fab?.visibility = View.VISIBLE
                    } else {
                        clearPendingUpdate(activity, deleteFile = false)
                    }
                }
            }
        }
        applyFabPosition(activity, prefs.updateFabPosition)
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            checkAndPresent(activity, silent = true)
        }
    }

    fun detach() {
        downloadJob?.cancel()
        hostRef = null
        fabColorAnim?.cancel(); fabColorAnim = null
        fab = null
        progressRing = null
        icon = null
    }

    /** Manual check from Settings. */
    fun checkNow(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            Toast.makeText(activity, "Loading releases…", Toast.LENGTH_SHORT).show()
            val list = try {
                UpdateChecker.listAllReleases(activity.applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) {
                    Toast.makeText(activity, "No releases found / network error", Toast.LENGTH_LONG).show()
                    // fallback single check
                    checkAndPresent(activity, silent = false)
                } else {
                    showReleaseBrowser(activity, list)
                }
            }
        }
    }

    /** Scrollable list of all tags — install / revamped / not installed. */
    private fun showReleaseBrowser(activity: Activity, releases: List<UpdateInfo>) {
        val localName = BuildConfig.VERSION_NAME.trim().removePrefix("v")
        val localCode = UpdateChecker.parseVersionCode(localName)
        val labels = Array(releases.size) { i ->
            val r = releases[i]
            val sizeMb = if (r.apkSizeBytes > 0) String.format("%.1f MB", r.apkSizeBytes / (1024.0 * 1024.0)) else "?"
            val status = when {
                r.versionCode == localCode && r.isRevamped -> " [REVAMPED — reinstall]"
                r.versionCode == localCode -> " [INSTALLED]"
                r.versionCode > localCode -> " [NEWER]"
                else -> " [OLDER]"
            }
            val head = if (i == 0) "★ LATEST · " else ""
            "$head${r.tagName}$status · $sizeMb"
        }
        AlertDialog.Builder(activity)
            .setTitle("Versions · installed v$localName")
            .setItems(labels) { _, which ->
                val chosen = releases[which]
                confirmInstallChoice(activity, chosen, localCode)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmInstallChoice(activity: Activity, info: UpdateInfo, localCode: Int) {
        val isOlder = info.versionCode < localCode
        val isSame = info.versionCode == localCode
        val title = when {
            isOlder -> "Downgrade to ${info.tagName}?"
            isSame && info.isRevamped -> "Reinstall revamped ${info.tagName}?"
            isSame -> "Reinstall ${info.tagName}?"
            else -> "Update to ${info.tagName}?"
        }
        val msg = buildString {
            if (isOlder) {
                append("You are about to install an older build.

")
                append("• Features from newer versions may be missing
")
                append("• Settings may need to be re-checked
")
                append("• Signature must match or Android will block install

")
            } else if (isSame && info.isRevamped) {
                append("Same version tag, but the APK binary changed (re-release).

")
            } else if (isSame) {
                append("This is the version you already have installed.

")
            } else {
                append("Install the newer build from GitHub Releases.

")
            }
            append("Size: ")
            if (info.apkSizeBytes > 0) append(String.format("%.1f MB", info.apkSizeBytes / (1024.0 * 1024.0)))
            else append("unknown")
            append("

Continue?")
        }
        val positive = if (isOlder) "Continue anyway" else "Download"
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ ->
                pending = info
                startDownload(activity, info)
            }
            .show()
    }

    private suspend fun checkAndPresent(activity: Activity, silent: Boolean): UpdateInfo? {
        val prefs = Prefs(activity.applicationContext)
        val info = try {
            UpdateChecker.checkForUpdate(activity.applicationContext)
        } catch (_: Exception) {
            null
        }
        if (info == null) return null
        pending = info
        try {
            if (info.publishedAt.isNotBlank()) {
                prefs.lastUpdateAssetUpdated = info.publishedAt
            }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
            if (!prefs.updateFabRemoved) {
                fab?.visibility = View.VISIBLE
            }
            if (!silent) {
                showUpdateDialog(activity, info)
            }
        }
        // System notification so users not in the app still see it
        val notifyKey = info.tagName + ":" + info.apkSizeBytes
        val lastKey = prefs.lastNotifiedUpdateTag + ":" + prefs.lastNotifiedUpdateSize
        if (prefs.notifyUpdates && notifyKey != lastKey) {
            postUpdateNotification(activity.applicationContext, info)
            prefs.lastNotifiedUpdateTag = info.tagName
            prefs.lastNotifiedUpdateSize = info.apkSizeBytes
        }
        return info
    }

    private fun ensureFab(activity: Activity) {
        if (fab != null && fab?.parent != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val size = (44 * activity.resources.displayMetrics.density).toInt()
        val margin = (12 * activity.resources.displayMetrics.density).toInt()

        val frame = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin * 3
                marginEnd = margin
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5865F2"))
            }
            // restored rotating accent colors on update FAB
            elevation = 10f
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { onFabClick(activity) }
            setOnLongClickListener {
                showFabMenu(activity, it)
                true
            }
        }

        val ring = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            val h = (4 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(3)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                h,
                Gravity.BOTTOM
            )
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            try {
                progressDrawable?.let { pd ->
                    pd.mutate()
                    pd.colorFilter = android.graphics.PorterDuffColorFilter(
                        Color.parseColor("#FFFFFF"),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
            } catch (_: Exception) {}
        }

        val iv = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                (22 * activity.resources.displayMetrics.density).toInt(),
                (22 * activity.resources.displayMetrics.density).toInt(),
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_update_download)
            setColorFilter(Color.WHITE)
        }

        frame.addView(ring)
        frame.addView(iv)
        root.addView(frame)
        enableFabDrag(activity, frame)

        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val prefs = Prefs(activity.applicationContext)
            val pos = prefs.updateFabPosition
            if (pos.startsWith("TOP")) {
                lp.topMargin = bars.top + margin
            } else {
                lp.bottomMargin = bars.bottom + margin
            }
            v.layoutParams = lp
            insets
        }

        fab = frame
        progressRing = ring
        icon = iv
        applyFabPosition(activity, Prefs(activity.applicationContext).updateFabPosition)
        startFabColorCycle(frame)
    }

    /** Soft rainbow cycle on the update FAB (pre-regression behavior). */
    private fun startFabColorCycle(frame: FrameLayout) {
        fabColorAnim?.cancel()
        val colors = intArrayOf(
            Color.parseColor("#5865F2"), // blurple
            Color.parseColor("#EB459E"), // fuchsia
            Color.parseColor("#ED4245"), // red
            Color.parseColor("#FEE75C"), // yellow
            Color.parseColor("#57F287"), // green
            Color.parseColor("#5865F2"),
        )
        val anim = android.animation.ValueAnimator.ofFloat(0f, (colors.size - 1).toFloat()).apply {
            duration = 3_200L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                val i = f.toInt().coerceIn(0, colors.size - 2)
                val frac = f - i
                val c: Int = ColorUtils.blendARGB(colors[i], colors[i + 1], frac)
                val bg = frame.background
                if (bg is GradientDrawable) {
                    bg.setColor(c)
                } else {
                    frame.setBackgroundColor(c)
                }
            }
        }
        fabColorAnim = anim
        anim.start()
    }

    private fun applyFabPosition(activity: Activity, position: String) {
        val frame = fab ?: return
        val prefs = Prefs(activity.applicationContext)
        val ox = prefs.updateFabOffsetX
        val oy = prefs.updateFabOffsetY
        if (!ox.isNaN() && !oy.isNaN()) {
            frame.x = ox
            frame.y = oy
            return
        }
        val lp = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = (12 * activity.resources.displayMetrics.density).toInt()
        lp.gravity = when (position) {
            "TOP_START" -> Gravity.TOP or Gravity.START
            "BOTTOM_END" -> Gravity.BOTTOM or Gravity.END
            "BOTTOM_START" -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.TOP or Gravity.END
        }
        lp.topMargin = margin * 3
        lp.bottomMargin = margin * 3
        lp.marginStart = margin
        lp.marginEnd = margin
        frame.layoutParams = lp
    }

    private fun enableFabDrag(activity: Activity, frame: FrameLayout) {
        var dX = 0f
        var dY = 0f
        var dragging = false
        frame.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    dragging = false
                    false // allow click / long-click
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val nx = event.rawX + dX
                    val ny = event.rawY + dY
                    if (!dragging) {
                        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
                        if (kotlin.math.abs(nx - v.x) > slop || kotlin.math.abs(ny - v.y) > slop) {
                            dragging = true
                        }
                    }
                    if (dragging) {
                        val parent = v.parent as? ViewGroup
                        val maxX = ((parent?.width ?: 0) - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = ((parent?.height ?: 0) - v.height).toFloat().coerceAtLeast(0f)
                        v.x = nx.coerceIn(0f, maxX)
                        v.y = ny.coerceIn(0f, maxY)
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val prefs = Prefs(activity.applicationContext)
                        prefs.updateFabOffsetX = v.x
                        prefs.updateFabOffsetY = v.y
                        dragging = false
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun showFabMenu(activity: Activity, anchor: View) {
        val prefs = Prefs(activity.applicationContext)
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, "Move (drag me)")
        menu.menu.add(0, 3, 1, "Reset position")
        menu.menu.add(0, 2, 2, "Remove")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    Toast.makeText(activity, "Drag the button anywhere, then release", Toast.LENGTH_SHORT).show()
                    true
                }
                3 -> {
                    prefs.updateFabOffsetX = Float.NaN
                    prefs.updateFabOffsetY = Float.NaN
                    applyFabPosition(activity, prefs.updateFabPosition)
                    Toast.makeText(activity, "Position reset", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    AlertDialog.Builder(activity)
                        .setTitle("Remove update button?")
                        .setMessage("The floating download button will be hidden. You can still check for updates in Settings.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            prefs.updateFabRemoved = true
                            fab?.visibility = View.GONE
                            Toast.makeText(activity, "Update button removed — use Settings → Check for updates", Toast.LENGTH_LONG).show()
                        }
                        .show()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun onFabClick(activity: Activity) {
        if (downloading) {
            val pct = progressRing?.progress ?: 0
            Toast.makeText(
                activity,
                "Your app update is being installed and is $pct% downloaded",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Prefer already-downloaded APK for this pending version
        val prefs = Prefs(activity.applicationContext)
        val ready = resolveReadyApk(activity)
        if (ready != null) {
            showInstallReadyDialog(activity, ready)
            return
        }
        val info = pending ?: return
        showUpdateDialog(activity, info)
    }

    private fun resolveReadyApk(activity: Activity): File? {
        if (isPendingAlreadyInstalled(activity)) {
            clearPendingUpdate(activity, deleteFile = true)
            return null
        }
        val prefs = Prefs(activity.applicationContext)
        val path = prefs.pendingUpdateApkPath.trim()
        val ver = prefs.pendingUpdateApkVersion.trim()
        if (path.isBlank()) return null
        val f = File(path)
        if (!f.exists() || f.length() < 1024L) {
            clearPendingUpdate(activity, deleteFile = false)
            return null
        }
        // If GitHub reports a newer pending, require version match when known
        val want = pending?.versionName?.trim().orEmpty()
        if (want.isNotEmpty() && ver.isNotEmpty() && ver != want) return null
        // Same as running app → discard
        val current = BuildConfig.VERSION_NAME.trim().removePrefix("v")
        if (ver.removePrefix("v") == current) {
            clearPendingUpdate(activity, deleteFile = true)
            return null
        }
        apkFile = f
        return f
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val notes = info.releaseNotes.trim().ifBlank { "Bug fixes and improvements." }
        val sizeLine = if (info.apkSizeBytes > 0L) {
            val mb = info.apkSizeBytes / (1024.0 * 1024.0)
            String.format("APK size: %.1f MB", mb)
        } else ""
        val revamped = if (info.isRevamped) " (Revamped)" else ""
        val header = buildString {
            append("Found a new version of this app (v${info.versionName}$revamped).\n\n")
            append("Current: ${com.discord.evs.generator.BuildConfig.VERSION_NAME}\n")
            append("New: ${info.versionName}$revamped\n")
            if (sizeLine.isNotEmpty()) append("$sizeLine\n")
            append("\nWhat's new:\n")
        }
        val bodySpanned = markdownToSpanned(header + notes)

        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
        val tv = TextView(activity).apply {
            text = bodySpanned
            setTextColor(Color.parseColor("#DCDDDE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, pad / 2, pad, pad)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply {
            addView(tv)
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxH.coerceAtLeast(200)
            )
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setView(scroll)
            .setNegativeButton("Later", null)
            .setPositiveButton("Download") { _, _ -> startDownload(activity, info) }
            .show()
    }

    /**
     * Lightweight GitHub-style markdown → Spanned for the update dialog.
     * Supports: # / ## / ### headers, **bold**, *italic*, - / * bullets, plain newlines.
     */
    private fun markdownToSpanned(src: String): Spanned {
        val sb = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        for ((i, raw) in lines.withIndex()) {
            if (i > 0) sb.append("\n")
            var line = raw
            var relativeSize = 1f
            var boldWhole = false
            when {
                line.startsWith("### ") -> {
                    line = line.removePrefix("### ").trim()
                    relativeSize = 1.15f
                    boldWhole = true
                }
                line.startsWith("## ") -> {
                    line = line.removePrefix("## ").trim()
                    relativeSize = 1.25f
                    boldWhole = true
                }
                line.startsWith("# ") -> {
                    line = line.removePrefix("# ").trim()
                    relativeSize = 1.4f
                    boldWhole = true
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    line = "• " + line.substring(2).trim()
                }
            }
            val start = sb.length
            // Inline **bold** and *italic*
            val inline = SpannableStringBuilder(line)
            applyInlineMarkdown(inline)
            sb.append(inline)
            val end = sb.length
            if (boldWhole && end > start) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (relativeSize != 1f && end > start) {
                sb.setSpan(RelativeSizeSpan(relativeSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun applyInlineMarkdown(sb: SpannableStringBuilder) {
        // **bold**
        val boldPat = Pattern.compile("\\*\\*(.+?)\\*\\*")
        var m = boldPat.matcher(sb)
        while (m.find()) {
            val s = m.start()
            val e = m.end()
            val inner = m.group(1) ?: continue
            sb.replace(s, e, inner)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, s + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            m = boldPat.matcher(sb)
        }
        // *italic* (single asterisks, not already consumed)
        val italPat = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
        m = italPat.matcher(sb)
        while (m.find()) {
            val s = m.start()
            val e = m.end()
            val inner = m.group(1) ?: continue
            sb.replace(s, e, inner)
            sb.setSpan(StyleSpan(Typeface.ITALIC), s, s + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            m = italPat.matcher(sb)
        }
    }

    private fun startDownload(activity: Activity, info: UpdateInfo) {
        if (downloading) return
        // Already on disk?
        val existing = resolveReadyApk(activity)
        if (existing != null && Prefs(activity.applicationContext).pendingUpdateApkVersion == info.versionName) {
            showInstallReadyDialog(activity, existing)
            return
        }
        downloading = true
        progressRing?.apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        icon?.alpha = 0.35f
        val owner = activity as? LifecycleOwner ?: run {
            downloading = false
            return
        }
        downloadJob = owner.lifecycleScope.launch {
            try {
                val file = UpdateDownloader.download(activity, info) { pct ->
                    // onProgress may be from IO; hop to main
                    launch(Dispatchers.Main) {
                        progressRing?.progress = pct
                    }
                }
                withContext(Dispatchers.Main) {
                    downloading = false
                    progressRing?.progress = 100
                    progressRing?.visibility = View.GONE
                    icon?.alpha = 1f
                    apkFile = file
                    val prefs = Prefs(activity.applicationContext)
                    prefs.pendingUpdateApkPath = file.absolutePath
                    prefs.pendingUpdateApkVersion = info.versionName
                    Toast.makeText(activity, "Update downloaded — ready to install", Toast.LENGTH_SHORT).show()
                    showInstallReadyDialog(activity, file)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloading = false
                    progressRing?.visibility = View.GONE
                    icon?.alpha = 1f
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun showInstallReadyDialog(activity: Activity, file: File) {
        if (!file.exists()) {
            Toast.makeText(activity, "Downloaded APK missing", Toast.LENGTH_SHORT).show()
            return
        }
        val ver = Prefs(activity.applicationContext).pendingUpdateApkVersion.ifBlank {
            pending?.versionName ?: "?"
        }
        AlertDialog.Builder(activity)
            .setTitle("Already downloaded")
            .setMessage(
                """Update v$ver is saved on this device.

Tap Install to open the system installer.
Tap Later to keep the file — you can install anytime from the update button."""
            )
            .setNegativeButton("Later", null)
            .setPositiveButton("Install") { _, _ ->
                if (!UpdateInstaller.canRequestPackageInstalls(activity)) {
                    AlertDialog.Builder(activity)
                        .setTitle("Permission needed")
                        .setMessage("Allow install from this app (unknown apps) to install the update.")
                        .setPositiveButton("Open settings") { _, _ ->
                            activity.startActivity(UpdateInstaller.intentUnknownSources(activity))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@setPositiveButton
                }
                val ok = UpdateInstaller.installApk(activity, file)
                if (!ok) {
                    Toast.makeText(activity, "Could not open installer", Toast.LENGTH_LONG).show()
                } else {
                    // User opened system installer — clear sticky "already downloaded"
                    // so after install/reopen we don't keep prompting the same APK.
                    clearPendingUpdate(activity, deleteFile = false)
                    fab?.visibility = View.GONE
                }
            }
            .show()
    }

    private fun promptInstall(activity: Activity, file: File) {
        showInstallReadyDialog(activity, file)
    }


    fun ensureUpdateChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            UPDATE_CHANNEL,
            "App updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when a new in-app version is available — even when the app is closed"
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun postUpdateNotification(context: Context, info: UpdateInfo) {
        try {
            ensureUpdateChannel(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_update", true)
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notes = info.releaseNotes.trim().take(120).ifBlank { "Tap to install and see what's new." }
            val notif = NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(R.drawable.ic_update_download)
                .setContentTitle("EVS Generator — New update available")
                .setContentText("v${info.versionName} ready · Update now & create EVS accounts")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Version ${info.versionName} just dropped.\n\n$notes\n\nTap to open EVS Generator and install."
                ))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(UPDATE_NOTIFY_ID, notif)
        } catch (_: Exception) {
        }
    }
}
