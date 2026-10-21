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
 * - Locates authorNameTag in PlayerActivity without host XML layout dependencies.
 * - Injects or styles a compact pill-shaped badge with theme-adaptive colors and native ripple feedback.
 * - Displays a comprehensive, non-crashing AlertDialog with replacement details and clipboard copy action.
 * - Restores original host view state cleanly on unreplaced songs, track changes, or activity destruction.
 */
object PlayerBadgeManager {
    private const val TAG = "PlayerBadgeManager"
    const val TAG_UNM_BADGE = "UNM_PLAYER_BADGE"
    private const val TAG_ORIGINAL_VISIBILITY = -1001

    /**
     * Injects or updates the dynamic badge in [activity] for [event].
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

                // 2. Locate anchor authorNameTag view
                val authorTagResId = activity.resources.getIdentifier("authorNameTag", "id", activity.packageName)
                val authorTagView = if (authorTagResId != 0) activity.findViewById<View>(authorTagResId) else null
                if (authorTagView == null) {
                    UnmLogger.w(TAG, "authorNameTag view not found in PlayerActivity")
                    return@runOnUiThread
                }

                val parent = authorTagView.parent as? ViewGroup
                val badgeView: TextView = getOrCreateBadgeView(activity, parent, authorTagView) ?: run {
                    UnmLogger.w(TAG, "Unable to obtain or inject badge view")
                    return@runOnUiThread
                }

                // 3. Apply badge text and styling
                val isDark = isDarkTheme(activity)
                styleBadgeView(badgeView, isDark)
                badgeView.text = BadgeFormatter.formatBadgeText(event)
                badgeView.visibility = View.VISIBLE

                // 4. Set click listener to display detail AlertDialog
                badgeView.setOnClickListener {
                    showDetailDialog(activity, event)
                }

            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to update player badge: ${t.message}", t)
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

                val authorTagResId = activity.resources.getIdentifier("authorNameTag", "id", activity.packageName)
                val authorTagView = if (authorTagResId != 0) activity.findViewById<View>(authorTagResId) else null

                val parent = authorTagView?.parent as? ViewGroup
                val injectedBadge = parent?.findViewWithTag<View>(TAG_UNM_BADGE)

                // Hide and clean up injected badge
                if (injectedBadge != null) {
                    injectedBadge.visibility = View.GONE
                    injectedBadge.setOnClickListener(null)
                    if (injectedBadge is TextView) {
                        injectedBadge.text = ""
                    }
                }

                // Restore host authorNameTag visibility if previously hidden
                if (authorTagView != null) {
                    val originalVis = authorTagView.getTag(TAG_ORIGINAL_VISIBILITY) as? Int
                    if (originalVis != null) {
                        authorTagView.visibility = originalVis
                    } else if (injectedBadge == null && authorTagView is TextView) {
                        // Direct decoration fallback cleanup
                        authorTagView.text = ""
                        authorTagView.background = null
                        authorTagView.visibility = View.GONE
                        authorTagView.setOnClickListener(null)
                        authorTagView.isClickable = false
                    }
                }
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Error resetting player badge: ${t.message}", t)
            }
        }
    }

    /**
     * Resolves existing badge view or creates and injects a dedicated TextView.
     */
    private fun getOrCreateBadgeView(activity: Activity, parent: ViewGroup?, anchorView: View): TextView? {
        // Try finding existing injected view first
        val existing = parent?.findViewWithTag<TextView>(TAG_UNM_BADGE)
        if (existing != null) return existing

        // Primary: inject dedicated view into parent container (e.g. artistFollowContainer FrameLayout)
        if (parent != null) {
            val badge = TextView(activity).apply {
                tag = TAG_UNM_BADGE
            }

            if (parent is FrameLayout) {
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dpToPx(activity, 4f).toInt()
                }
                parent.addView(badge, lp)
            } else if (parent is LinearLayout) {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dpToPx(activity, 6f).toInt()
                }
                val index = parent.indexOfChild(anchorView)
                parent.addView(badge, if (index >= 0) index + 1 else parent.childCount, lp)
            } else {
                parent.addView(badge)
            }

            // Hide original authorNameTag while dedicated badge is showing
            if (anchorView.getTag(TAG_ORIGINAL_VISIBILITY) == null) {
                anchorView.setTag(TAG_ORIGINAL_VISIBILITY, anchorView.visibility)
            }
            anchorView.visibility = View.GONE
            return badge
        }

        // Fallback: decorate anchorView directly if it is a TextView
        return anchorView as? TextView
    }

    /**
     * Styles the badge view with compact bounds, rounded drawable, and native ripple.
     */
    private fun styleBadgeView(view: TextView, isDark: Boolean) {
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

        // Background with corner radius
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
     * Displays a styled, comprehensive replacement detail AlertDialog.
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

            // Programmatic layout container
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
