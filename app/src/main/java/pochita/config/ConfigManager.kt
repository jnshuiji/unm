package pochita.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CopyOnWriteArrayList
import pochita.common.UnmLogger
import pochita.data.PreferenceKeys
import pochita.rule.RuleManager

/**
 * High-performance, thread-safe configuration manager for the host process and test runners.
 * Supports cross-process live reload via LSPosed RemotePreferences with zero UI stutter.
 */
object ConfigManager {
    private const val TAG = "ConfigManager"

    @Volatile
    private var activePrefs: SharedPreferences? = null

    @Volatile
    private var cachedConfig: UnmConfig = UnmConfig()

    @Volatile
    private var testingOverrideConfig: UnmConfig? = null

    private val changeListeners = CopyOnWriteArrayList<(UnmConfig) -> Unit>()

    /**
     * CRITICAL: Retained as a strong reference for the entire lifetime of the process.
     * Prevents garbage collection by WeakHashMap in RemotePreferences.
     */
    private val strongPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        UnmLogger.i(TAG, "OnSharedPreferenceChanged notification received for key: $key")

        // 1. Reload cached UnmConfig snapshot into volatile memory
        val updated = loadFromPreferences(prefs)
        cachedConfig = updated

        // 2. Hot-reload dynamic QuickJS script if rule script was updated
        if (key == null || key == PreferenceKeys.KEY_CACHED_RULE_SCRIPT) {
            val script = prefs.getString(PreferenceKeys.KEY_CACHED_RULE_SCRIPT, null)
            if (!script.isNullOrBlank()) {
                UnmLogger.i(TAG, "Hot-reloading dynamic QuickJS rule script in host process...")
                try {
                    RuleManager.getAppContext()?.let { ctx ->
                        RuleManager.saveCustomScript(ctx, script)
                    }
                } catch (t: Throwable) {
                    UnmLogger.w(TAG, "Failed to hot reload dynamic rule script: ${t.message}")
                }
            }
        }

        // 3. Dispatch to registered listeners
        for (listener in changeListeners) {
            try {
                listener(updated)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Error in config change listener: ${t.message}", t)
            }
        }
    }

    /**
     * Attaches ConfigManager to an [XposedModule] instance in the host process.
     */
    fun init(module: XposedModule?) {
        val prefs = try {
            module?.getRemotePreferences(PreferenceKeys.PREFS_NAME)
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "module.getRemotePreferences failed: ${t.message}")
            null
        }
        init(prefs)
    }

    /**
     * Attaches ConfigManager to an active [SharedPreferences] instance.
     */
    @Synchronized
    fun init(prefs: SharedPreferences?) {
        activePrefs?.unregisterOnSharedPreferenceChangeListener(strongPreferenceListener)
        activePrefs = prefs
        if (prefs != null) {
            cachedConfig = loadFromPreferences(prefs)
            try {
                prefs.registerOnSharedPreferenceChangeListener(strongPreferenceListener)
                UnmLogger.i(TAG, "ConfigManager registered OnSharedPreferenceChangeListener successfully")
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to register OnSharedPreferenceChangeListener: ${t.message}")
            }
        } else {
            cachedConfig = UnmConfig()
        }
    }

    /**
     * Returns the active configuration snapshot.
     * Guaranteed to never throw and returns immediately (<0.1μs) from volatile memory.
     */
    fun getConfig(): UnmConfig {
        testingOverrideConfig?.let { return it }
        return cachedConfig
    }

    /**
     * Deserializes [UnmConfig] from [prefs] with defensive error handling and fallbacks.
     */
    fun loadFromPreferences(prefs: SharedPreferences?): UnmConfig {
        if (prefs == null) return UnmConfig()

        // 1. Parse sound sources preserving order
        val sourcesRaw = try {
            prefs.getString(PreferenceKeys.KEY_ENABLED_SOURCES, null)
        } catch (_: Throwable) {
            null
        }
        val enabledSources = PreferenceKeys.deserializeSources(sourcesRaw)

        // 2. Parse preferred quality
        val qualityRaw = try {
            prefs.getString(PreferenceKeys.KEY_PREFERRED_QUALITY, null)
        } catch (_: Throwable) {
            null
        }
        val preferredQuality = if (qualityRaw != null && qualityRaw.trim().lowercase() in UnmConfig.SUPPORTED_QUALITIES) {
            qualityRaw.trim().lowercase()
        } else {
            UnmConfig.DEFAULT_PREFERRED_QUALITY
        }

        // 3. Parse boolean switches with ClassCastException protection
        val enableToast = readBooleanDefensive(prefs, PreferenceKeys.KEY_ENABLE_TOAST, UnmConfig.DEFAULT_ENABLE_TOAST)
        val enablePlayerBadge = readBooleanDefensive(prefs, PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, UnmConfig.DEFAULT_ENABLE_PLAYER_BADGE)
        val enableAntiRoll = readBooleanDefensive(prefs, PreferenceKeys.KEY_ENABLE_ANTI_ROLL, UnmConfig.DEFAULT_ENABLE_ANTI_ROLL)

        return UnmConfig(
            enabledSources = enabledSources,
            preferredQuality = preferredQuality,
            enableToast = enableToast,
            enablePlayerBadge = enablePlayerBadge,
            enableAntiRoll = enableAntiRoll,
        )
    }

    /**
     * Serializes [config] into [prefs].
     */
    fun saveToPreferences(prefs: SharedPreferences, config: UnmConfig) {
        prefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, PreferenceKeys.serializeSources(config.enabledSources))
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, config.preferredQuality)
            .putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, config.enableToast)
            .putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, config.enablePlayerBadge)
            .putBoolean(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, config.enableAntiRoll)
            .apply()
    }

    /**
     * Retrieves remote config using standard application Context.
     */
    fun getRemoteConfig(context: Context): UnmConfig {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return loadFromPreferences(prefs)
    }

    /**
     * Subscribes to live configuration changes.
     * Returns an unregister callback.
     */
    fun addOnConfigChangeListener(listener: (UnmConfig) -> Unit): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener) }
    }

    private fun readBooleanDefensive(prefs: SharedPreferences, key: String, default: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, default)
        } catch (_: ClassCastException) {
            try {
                prefs.getString(key, null)?.toBooleanStrictOrNull() ?: default
            } catch (_: Throwable) {
                default
            }
        } catch (_: Throwable) {
            default
        }
    }

    // Testing seams
    fun setConfigForTesting(config: UnmConfig?) {
        testingOverrideConfig = config
    }

    fun resetForTesting() {
        activePrefs?.unregisterOnSharedPreferenceChangeListener(strongPreferenceListener)
        activePrefs = null
        cachedConfig = UnmConfig()
        testingOverrideConfig = null
        changeListeners.clear()
    }
}
