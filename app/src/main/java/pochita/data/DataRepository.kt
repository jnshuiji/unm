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
import pochita.source.RuleManager

data class LsposedStatus(
    val isActivated: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
)

data class ModuleConfig(
    val ruleUrl: String = "https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js",
    val lastUpdateStatus: String = "就绪 (内置规则)",
)

interface DataRepository {
    val lsposedStatus: Flow<LsposedStatus>
    val config: Flow<ModuleConfig>
    fun setRuleUrl(url: String)
    suspend fun updateRule(): String
    suspend fun resetRule(): String
}

class DefaultDataRepository(context: Context) : DataRepository {
    private val appContext: Context = context.applicationContext ?: context
    private val _status = MutableStateFlow(LsposedStatus())
    override val lsposedStatus: Flow<LsposedStatus> = _status.asStateFlow()

    private val prefs: SharedPreferences = appContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(
        ModuleConfig(
            ruleUrl = prefs.getString("rule_url", "https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js") ?: "https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js",
        )
    )
    override val config: Flow<ModuleConfig> = _config.asStateFlow()

    init {
        RuleManager.init(appContext)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                _status.value = LsposedStatus(
                    isActivated = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    apiVersion = service.apiVersion,
                )
            }

            override fun onServiceDied(service: XposedService) {
                _status.value = LsposedStatus(isActivated = false)
            }
        })
    }

    override fun setRuleUrl(url: String) {
        prefs.edit { putString("rule_url", url) }
        _config.value = _config.value.copy(ruleUrl = url)
    }

    override suspend fun updateRule(): String = withContext(Dispatchers.IO) {
        val url = _config.value.ruleUrl
        val result = RuleManager.updateRuleFromUrl(appContext, url)
        if (result.isSuccess) {
            val scriptFile = java.io.File(appContext.filesDir, "unm_rule.js")
            if (scriptFile.exists()) {
                val script = scriptFile.readText(Charsets.UTF_8)
                prefs.edit { putString("cached_rule_script", script) }
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
            prefs.edit { remove("cached_rule_script") }
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
