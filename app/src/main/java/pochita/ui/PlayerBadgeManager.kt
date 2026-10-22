package pochita.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import pochita.common.UnmLogger
import pochita.config.ConfigManager
import pochita.model.ReplaceEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-performance, zero-XML programmatic UI manager for NetEase PlayerActivity badge & detail dialog.
 *
 * Responsibilities:
 * - Multi-tier anchor resolution (authorsContainer -> authorName -> authorNameTag -> songName)
 *   providing 100% layout compatibility across phones and tablets (e.g. Xiaomi Pad).
 * - Injects or updates a compact pill-shaped badge with theme-adaptive colors and native ripple feedback.
 * - Displays a comprehensive, non-crashing AlertDialog with replacement details and clipboard copy action.
 * - Displays official native badge [官方·原版] for tracks playing natively from NetEase without replacement.
 * - Restores original host view state cleanly on unreplaced songs, track changes, or activity destruction.
 */
object PlayerBadgeManager {
    private const val TAG = "PlayerBadgeManager"
    const val TAG_UNM_BADGE = "UNM_PLAYER_BADGE"
    private const val TAG_ORIGINAL_VISIBILITY = -1001

    /**
     * Injects or updates the dynamic replacement badge in [activity] for [event].
     * Dispatches safely to the Android main thread.
     */
    fun updateBadge(activity: Activity, event: ReplaceEvent?) {
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                // 1. Config toggle guard & event validation
                val config = ConfigManager.getConfig()
                if (!config.enablePlayerBadge || event == null) {
                    resetBadge(activity)
                    return@runOnUiThread
                }

                // 2. Locate or create badge view via multi-tier anchor resolution
                val badgeView = getOrCreateBadgeView(activity) ?: run {
                    UnmLogger.w(TAG, "Unable to obtain or inject badge view in PlayerActivity")
                    return@runOnUiThread
                }

                // 3. Apply replaced badge text and styling
                val isDark = isDarkTheme(activity)
                styleReplacedBadgeView(badgeView, isDark)
                badgeView.text = BadgeFormatter.formatPillBadgeText(event)
                badgeView.visibility = View.VISIBLE

                // 4. Set click listener to display replacement detail AlertDialog
                badgeView.setOnClickListener {
                    showDetailDialog(activity, event)
                }
                UnmLogger.i(TAG, "Updated replacement badge: text='${badgeView.text}' for songId=${event.songId}")

            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to update player badge: ${t.message}", t)
            }
        }
    }

    /**
     * Displays a native [官方·原版] badge for playable NetEase tracks that do not require replacement.
     */
    fun showNativeBadge(activity: Activity, title: String, artist: String) {
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                val config = ConfigManager.getConfig()
                if (!config.enablePlayerBadge) {
                    resetBadge(activity)
                    return@runOnUiThread
                }

                val badgeView = getOrCreateBadgeView(activity) ?: run {
                    UnmLogger.w(TAG, "Unable to obtain or inject native badge view in PlayerActivity")
                    return@runOnUiThread
                }

                val isDark = isDarkTheme(activity)
                styleNativeBadgeView(badgeView, isDark)
                badgeView.text = "[官方·原版]"
                badgeView.visibility = View.VISIBLE

                badgeView.setOnClickListener {
                    showNativeDetailDialog(activity, title, artist)
                }
                UnmLogger.i(TAG, "Displayed native [官方·原版] badge for '$title' - '$artist'")

            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to show native badge: ${t.message}", t)
            }
        }
    }

    /**
     * Restores host view state and hides the injected badge.
     */
    fun resetBadge(activity: Activity) {
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                val injectedBadge = activity.window?.decorView?.findViewWithTag<View>(TAG_UNM_BADGE)
                if (injectedBadge != null) {
                    injectedBadge.visibility = View.GONE
                    injectedBadge.setOnClickListener(null)
                    if (injectedBadge is TextView) {
                        injectedBadge.text = ""
                    }
                }

                // Restore host authorNameTag visibility if previously hidden
                val authorTagView = findViewByStringId(activity, "authorNameTag")
                if (authorTagView != null) {
                    val originalVis = authorTagView.getTag(TAG_ORIGINAL_VISIBILITY) as? Int
                    if (originalVis != null) {
                        authorTagView.visibility = originalVis
                    }
                }
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Error resetting player badge: ${t.message}", t)
            }
        }
    }

    private data class AnchorInfo(
        val container: ViewGroup,
        val insertIndex: Int,
        val anchorView: View? = null
    )

    /**
     * Robust view lookup by string resource name with recursive decorView fallback.
     */
    fun findViewByStringId(activity: Activity, idName: String): View? {
        val resId = activity.resources.getIdentifier(idName, "id", activity.packageName)
        if (resId != 0) {
            val v = activity.findViewById<View>(resId)
            if (v != null) return v
        }
        return findViewByEntryName(activity.window?.decorView, idName)
    }

    private fun findViewByEntryName(view: View?, targetName: String): View? {
        if (view == null) return null
        try {
            if (view.id != View.NO_ID) {
                val name = view.resources.getResourceEntryName(view.id)
                if (name == targetName) return view
            }
        } catch (_: Throwable) {}
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewByEntryName(view.getChildAt(i), targetName)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Multi-tier resolution for anchor layout container:
     * - Tier 1: authorsContainer (LinearLayout) immediately adjacent to authorName (Phone & Tablet)
     * - Tier 2: authorName's parent ViewGroup
     * - Tier 3: authorNameTag's parent ViewGroup
     * - Tier 4: songName's parent ViewGroup
     */
    private fun findAnchor(activity: Activity): AnchorInfo? {
        // Tier 1: authorsContainer LinearLayout
        val authorsContainer = findViewByStringId(activity, "authorsContainer") as? ViewGroup
        if (authorsContainer != null) {
            val authorName = findViewByStringId(activity, "authorName")
            val idx = if (authorName != null) authorsContainer.indexOfChild(authorName) else -1
            val insertIdx = if (idx >= 0) idx + 1 else authorsContainer.childCount
            UnmLogger.d(TAG, "Anchor Tier 1 matched: authorsContainer found, insertIdx=$insertIdx")
            return AnchorInfo(authorsContainer, insertIdx, authorName)
        }

        // Tier 2: authorName parent
        val authorName = findViewByStringId(activity, "authorName")
        val parent2 = authorName?.parent as? ViewGroup
        if (parent2 != null) {
            val idx = parent2.indexOfChild(authorName)
            val insertIdx = if (idx >= 0) idx + 1 else parent2.childCount
            UnmLogger.d(TAG, "Anchor Tier 2 matched: authorName.parent found, insertIdx=$insertIdx")
            return AnchorInfo(parent2, insertIdx, authorName)
        }

        // Tier 3: authorNameTag parent
        val authorTag = findViewByStringId(activity, "authorNameTag")
        val parent3 = authorTag?.parent as? ViewGroup
        if (parent3 != null) {
            val idx = parent3.indexOfChild(authorTag)
            val insertIdx = if (idx >= 0) idx + 1 else parent3.childCount
            UnmLogger.d(TAG, "Anchor Tier 3 matched: authorNameTag.parent found, insertIdx=$insertIdx")
            return AnchorInfo(parent3, insertIdx, authorTag)
        }

        // Tier 4: songName parent
        val songName = findViewByStringId(activity, "songName")
        val parent4 = songName?.parent as? ViewGroup
        if (parent4 != null) {
            val idx = parent4.indexOfChild(songName)
            val insertIdx = if (idx >= 0) idx + 1 else parent4.childCount
            UnmLogger.d(TAG, "Anchor Tier 4 matched: songName.parent found, insertIdx=$insertIdx")
            return AnchorInfo(parent4, insertIdx, songName)
        }

        UnmLogger.w(TAG, "No anchor view found in PlayerActivity view hierarchy")
        return null
    }

    /**
     * Resolves existing badge view or creates and injects a dedicated TextView.
     */
    private fun getOrCreateBadgeView(activity: Activity): TextView? {
        val decorView = activity.window?.decorView
        val existing = decorView?.findViewWithTag<TextView>(TAG_UNM_BADGE)
        if (existing != null) return existing

        val anchor = findAnchor(activity) ?: return null
        val parent = anchor.container

        val badge = TextView(activity).apply {
            tag = TAG_UNM_BADGE
        }

        when (parent) {
            is LinearLayout -> {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dpToPx(activity, 8f).toInt()
                }
                val safeIndex = anchor.insertIndex.coerceIn(0, parent.childCount)
                parent.addView(badge, safeIndex, lp)
            }
            is FrameLayout -> {
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dpToPx(activity, 6f).toInt()
                }
                parent.addView(badge, lp)
            }
            else -> {
                parent.addView(badge)
            }
        }

        // Defensively disable clipping up the hierarchy so pill badge is never cut off
        disableClippingRecursively(parent)
        UnmLogger.i(TAG, "Injected badge view into container ${parent.javaClass.simpleName} at index ${anchor.insertIndex}")

        return badge
    }

    private fun disableClippingRecursively(view: ViewGroup) {
        var current: ViewGroup? = view
        var depth = 0
        while (current != null && depth < 4) {
            current.clipChildren = false
            current.clipToPadding = false
            current = current.parent as? ViewGroup
            depth++
        }
    }

    /**
     * Styles the badge view for replaced tracks (NetEase Red / Accent theme).
     */
    private fun styleReplacedBadgeView(view: TextView, isDark: Boolean) {
        val context = view.context
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        view.typeface = Typeface.DEFAULT_BOLD
        view.includeFontPadding = false
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxWidth = dpToPx(context, 180f).toInt()
        view.gravity = Gravity.CENTER

        val padH = dpToPx(context, 6f).toInt()
        val padV = dpToPx(context, 2.5f).toInt()
        view.setPadding(padH, padV, padH, padV)

        // Palette
        val bgColor = if (isDark) Color.argb(45, 255, 255, 255) else Color.argb(20, 0, 0, 0)
        val strokeColor = if (isDark) Color.argb(90, 255, 255, 255) else Color.argb(80, 211, 58, 49)
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#D33A31")

        view.setTextColor(textColor)

        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 4f)
            setColor(bgColor)
            setStroke(dpToPx(context, 0.8f).toInt(), strokeColor)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val rippleColor = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            view.background = RippleDrawable(rippleColor, shape, null)
        } else {
            view.background = shape
        }
    }

    /**
     * Styles the badge view for native NetEase tracks (Subtle Slate Gray theme).
     */
    private fun styleNativeBadgeView(view: TextView, isDark: Boolean) {
        val context = view.context
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        view.typeface = Typeface.DEFAULT_BOLD
        view.includeFontPadding = false
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxWidth = dpToPx(context, 180f).toInt()
        view.gravity = Gravity.CENTER

        val padH = dpToPx(context, 6f).toInt()
        val padV = dpToPx(context, 2.5f).toInt()
        view.setPadding(padH, padV, padH, padV)

        // Palette: subtle gray / slate tone
        val bgColor = if (isDark) Color.argb(35, 255, 255, 255) else Color.argb(18, 0, 0, 0)
        val strokeColor = if (isDark) Color.argb(60, 255, 255, 255) else Color.argb(60, 100, 100, 100)
        val textColor = if (isDark) Color.argb(200, 255, 255, 255) else Color.parseColor("#555555")

        view.setTextColor(textColor)

        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 4f)
            setColor(bgColor)
            setStroke(dpToPx(context, 0.8f).toInt(), strokeColor)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val rippleColor = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
            view.background = RippleDrawable(rippleColor, shape, null)
        } else {
            view.background = shape
        }
    }

    /**
     * Displays a styled replacement detail AlertDialog.
     */
    fun showDetailDialog(activity: Activity, event: ReplaceEvent) {
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val isDark = isDarkTheme(activity)
            val themeResId = if (isDark) {
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            } else {
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            }

            val builder = AlertDialog.Builder(activity, themeResId)
            builder.setTitle("🎵 云村换源详情")

            val pad = dpToPx(activity, 20f).toInt()
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
            }

            val primaryTextColor = if (isDark) Color.WHITE else Color.parseColor("#222222")

            fun addSection(title: String, lines: List<String>) {
                val titleView = TextView(activity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#EC4141"))
                    setPadding(0, dpToPx(activity, 8f).toInt(), 0, dpToPx(activity, 4f).toInt())
                }
                container.addView(titleView)

                for (line in lines) {
                    val lineView = TextView(activity).apply {
                        text = line
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTextColor(primaryTextColor)
                        setPadding(0, dpToPx(activity, 2f).toInt(), 0, dpToPx(activity, 2f).toInt())
                    }
                    container.addView(lineView)
                }
            }

            val format = BadgeFormatter.resolveFormat(event.br)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))

            addSection("原曲信息", listOf(
                "曲名: 《${event.originalTitle}》",
                "歌手: ${event.originalArtist}"
            ))

            addSection("换源匹配", listOf(
                "曲名: 《${event.matchedTitle}》",
                "歌手: ${event.matchedArtist}"
            ))

            addSection("音源详情", listOf(
                "音源平台: ${BadgeFormatter.formatSourceDisplayName(event.matchedSource)}",
                "规格码率: ${event.br / 1000} kbps ($format)",
                "歌曲 ID: ${event.songId}",
                "替换时间: $timeStr"
            ))

            val scrollView = ScrollView(activity).apply {
                addView(container)
            }
            builder.setView(scrollView)

            builder.setPositiveButton("我知道了", null)
            builder.setNeutralButton("复制信息") { _, _ ->
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("UNM Detail", BadgeFormatter.formatClipboardText(event)))
                    Toast.makeText(activity, "已复制换源详情到剪贴板", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    UnmLogger.w(TAG, "Failed to copy details to clipboard: ${t.message}")
                }
            }

            builder.show()
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to show detail dialog: ${t.message}", t)
        }
    }

    /**
     * Displays an official native audio information AlertDialog.
     */
    fun showNativeDetailDialog(activity: Activity, title: String, artist: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val isDark = isDarkTheme(activity)
            val themeResId = if (isDark) {
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            } else {
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            }

            val builder = AlertDialog.Builder(activity, themeResId)
            builder.setTitle("🎵 播放音源详情")

            val pad = dpToPx(activity, 20f).toInt()
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
            }

            val primaryTextColor = if (isDark) Color.WHITE else Color.parseColor("#222222")

            fun addSection(header: String, lines: List<String>) {
                val titleView = TextView(activity).apply {
                    text = header
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#388E3C"))
                    setPadding(0, dpToPx(activity, 8f).toInt(), 0, dpToPx(activity, 4f).toInt())
                }
                container.addView(titleView)

                for (line in lines) {
                    val lineView = TextView(activity).apply {
                        text = line
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTextColor(primaryTextColor)
                        setPadding(0, dpToPx(activity, 2f).toInt(), 0, dpToPx(activity, 2f).toInt())
                    }
                    container.addView(lineView)
                }
            }

            addSection("当前歌曲", listOf(
                "曲名: 《$title》",
                "歌手: ${artist.ifBlank { "未知歌手" }}"
            ))

            addSection("音源状态", listOf(
                "音频来源: 网易云官方原版",
                "换源状态: 官方播放 (无需换源)",
                "说明: 本歌曲在网易云音乐拥有完整的官方播放版权，音质正常，无需通过 UNM 换源替换，正在为您播放官方原生音频流。"
            ))

            val scrollView = ScrollView(activity).apply {
                addView(container)
            }
            builder.setView(scrollView)
            builder.setPositiveButton("我知道了", null)
            builder.show()
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to show native detail dialog: ${t.message}", t)
        }
    }

    private fun isDarkTheme(context: Context): Boolean {
        return try {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            true
        }
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
