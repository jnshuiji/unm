package pochita.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pochita.rule.RuleManager

data class LsposedStatus(
    val isActivated: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
)

data class ModuleConfig(
    val ruleUrl: String = PreferenceKeys.DEFAULT_RULE_URL,
    val lastUpdateStatus: String = "就绪 (内置规则)",
    val enabledSources: List<String> = PreferenceKeys.DEFAULT_ENABLED_SOURCES,
    val preferredQuality: String = PreferenceKeys.DEFAULT_PREFERRED_QUALITY,
    val enableToast: Boolean = PreferenceKeys.DEFAULT_ENABLE_TOAST,
    val enablePlayerBadge: Boolean = PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE,
    val enableAntiRoll: Boolean = PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL,
)

interface DataRepository {
    val lsposedStatus: Flow<LsposedStatus>
    val config: Flow<ModuleConfig>
    val currentConfig: ModuleConfig get() = ModuleConfig()
    fun setRuleUrl(url: String)
    suspend fun updateRule(): String
    suspend fun resetRule(): String

    // Phase 2 Milestone 1 accessors with default empty bodies for backward compatibility
    fun setEnabledSources(sources: List<String>) {}
    fun setPreferredQuality(quality: String) {}
    fun setEnableToast(enabled: Boolean) {}
    fun setEnablePlayerBadge(enabled: Boolean) {}
    fun setEnableAntiRoll(enabled: Boolean) {}
}

class DefaultDataRepository(context: Context) : DataRepository {
    private val appContext: Context = context.applicationContext ?: context
    private val _status = MutableStateFlow(LsposedStatus())
    override val lsposedStatus: Flow<LsposedStatus> = _status.asStateFlow()

    private val localPrefs: SharedPreferences = appContext.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var remoteService: XposedService? = null

    private val _config = MutableStateFlow(
        ModuleConfig(
            ruleUrl = localPrefs.getString(PreferenceKeys.KEY_RULE_URL, PreferenceKeys.DEFAULT_RULE_URL) ?: PreferenceKeys.DEFAULT_RULE_URL,
            enabledSources = PreferenceKeys.deserializeSources(localPrefs.getString(PreferenceKeys.KEY_ENABLED_SOURCES, null)),
            preferredQuality = localPrefs.getString(PreferenceKeys.KEY_PREFERRED_QUALITY, PreferenceKeys.DEFAULT_PREFERRED_QUALITY) ?: PreferenceKeys.DEFAULT_PREFERRED_QUALITY,
            enableToast = localPrefs.getBoolean(PreferenceKeys.KEY_ENABLE_TOAST, PreferenceKeys.DEFAULT_ENABLE_TOAST),
            enablePlayerBadge = localPrefs.getBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE),
            enableAntiRoll = localPrefs.getBoolean(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL),
        )
    )
    override val config: Flow<ModuleConfig> = _config.asStateFlow()
    override val currentConfig: ModuleConfig get() = _config.value

    init {
        RuleManager.init(appContext)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                remoteService = service
                _status.value = LsposedStatus(
                    isActivated = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    apiVersion = service.apiVersion,
                )
                // Proactively push existing local configuration to LSPosed framework daemon
                syncLocalToRemote(service)
            }

            override fun onServiceDied(service: XposedService) {
                remoteService = null
                _status.value = LsposedStatus(isActivated = false)
            }
        })
    }

    /**
     * Synchronizes all locally persisted preferences into LSPosed RemotePreferences.
     */
    private fun syncLocalToRemote(service: XposedService) {
        try {
            val remotePrefs = service.getRemotePreferences(PreferenceKeys.PREFS_NAME)
            val editor = remotePrefs.edit()
            for ((key, value) in localPrefs.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.apply()
        } catch (_: Throwable) {}
    }

    /**
     * Unified preference mutation applying changes locally and pushing remotely if service is active.
     */
    private fun writePreference(mutation: (SharedPreferences.Editor) -> Unit) {
        // 1. Persist locally
        localPrefs.edit { mutation(this) }

        // 2. Push remotely to LSPosed daemon
        try {
            remoteService?.getRemotePreferences(PreferenceKeys.PREFS_NAME)?.let { remotePrefs ->
                val editor = remotePrefs.edit()
                mutation(editor)
                editor.apply()
            }
        } catch (_: Throwable) {}
    }

    override fun setRuleUrl(url: String) {
        writePreference { it.putString(PreferenceKeys.KEY_RULE_URL, url) }
        _config.value = _config.value.copy(ruleUrl = url)
    }

    override fun setEnabledSources(sources: List<String>) {
        val serialized = PreferenceKeys.serializeSources(sources)
        writePreference { it.putString(PreferenceKeys.KEY_ENABLED_SOURCES, serialized) }
        _config.value = _config.value.copy(enabledSources = sources)
    }

    override fun setPreferredQuality(quality: String) {
        writePreference { it.putString(PreferenceKeys.KEY_PREFERRED_QUALITY, quality) }
        _config.value = _config.value.copy(preferredQuality = quality)
    }

    override fun setEnableToast(enabled: Boolean) {
        writePreference { it.putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, enabled) }
        _config.value = _config.value.copy(enableToast = enabled)
    }

    override fun setEnablePlayerBadge(enabled: Boolean) {
        writePreference { it.putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, enabled) }
        _config.value = _config.value.copy(enablePlayerBadge = enabled)
    }

    override fun setEnableAntiRoll(enabled: Boolean) {
        writePreference { it.putBoolean(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, enabled) }
        _config.value = _config.value.copy(enableAntiRoll = enabled)
    }

    override suspend fun updateRule(): String = withContext(Dispatchers.IO) {
        val url = _config.value.ruleUrl
        val result = RuleManager.updateRuleFromUrl(appContext, url)
        if (result.isSuccess) {
            val scriptFile = java.io.File(appContext.filesDir, "unm_rule.js")
            if (scriptFile.exists()) {
                val script = scriptFile.readText(Charsets.UTF_8)
                writePreference { it.putString(PreferenceKeys.KEY_CACHED_RULE_SCRIPT, script) }
            }
            val msg = "更新成功 (大小: ${result.getOrNull()} 字节)"
            _config.value = _config.value.copy(lastUpdateStatus = msg)
            msg
        } else {
            val msg = "更新失败: ${result.exceptionOrNull()?.message}"
            _config.value = _config.value.copy(lastUpdateStatus = msg)
            msg
        }
    }

    override suspend fun resetRule(): String = withContext(Dispatchers.IO) {
        val result = RuleManager.resetToDefault(appContext)
        if (result.isSuccess) {
            writePreference { it.remove(PreferenceKeys.KEY_CACHED_RULE_SCRIPT) }
            val msg = "已恢复为内置规则 (${result.getOrNull()} 字节)"
            _config.value = _config.value.copy(lastUpdateStatus = msg)
            msg
        } else {
            val msg = "重置失败: ${result.exceptionOrNull()?.message}"
            _config.value = _config.value.copy(lastUpdateStatus = msg)
            msg
        }
    }
}
