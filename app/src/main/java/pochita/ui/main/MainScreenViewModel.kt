package pochita.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pochita.data.DataRepository
import pochita.data.DefaultDataRepository
import pochita.data.LsposedStatus
import pochita.data.ModuleConfig

class MainScreenViewModel @JvmOverloads constructor(
    application: Application,
    private val dataRepository: DataRepository = DefaultDataRepository(application),
) : AndroidViewModel(application) {

    val status: StateFlow<LsposedStatus> = dataRepository.lsposedStatus
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LsposedStatus(),
        )

    val config: StateFlow<ModuleConfig> = dataRepository.config
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = dataRepository.currentConfig,
        )


    fun setRuleUrl(url: String) {
        dataRepository.setRuleUrl(url)
    }

    fun updateRule() {
        viewModelScope.launch {
            dataRepository.updateRule()
        }
    }

    fun resetRule() {
        viewModelScope.launch {
            dataRepository.resetRule()
        }
    }

    // Sound source prioritization & toggle handlers
    fun toggleSource(sourceId: String, enable: Boolean) {
        val current = dataRepository.currentConfig.enabledSources.toMutableList()
        if (enable) {
            if (!current.contains(sourceId)) {
                current.add(sourceId)
                dataRepository.setEnabledSources(current)
            }
        } else {
            // Guard: ensure at least one source remains active
            if (current.size > 1 && current.contains(sourceId)) {
                current.remove(sourceId)
                dataRepository.setEnabledSources(current)
            }
        }
    }

    fun moveSourceUp(sourceId: String) {
        val current = dataRepository.currentConfig.enabledSources.toMutableList()
        val index = current.indexOf(sourceId)
        if (index > 0) {
            val temp = current[index]
            current[index] = current[index - 1]
            current[index - 1] = temp
            dataRepository.setEnabledSources(current)
        }
    }

    fun moveSourceDown(sourceId: String) {
        val current = dataRepository.currentConfig.enabledSources.toMutableList()
        val index = current.indexOf(sourceId)
        if (index >= 0 && index < current.size - 1) {
            val temp = current[index]
            current[index] = current[index + 1]
            current[index + 1] = temp
            dataRepository.setEnabledSources(current)
        }
    }

    fun resetSourceOrder() {
        dataRepository.setEnabledSources(pochita.data.PreferenceKeys.DEFAULT_ENABLED_SOURCES)
    }

    // Audio quality & feature toggles
    fun setPreferredQuality(quality: String) {
        dataRepository.setPreferredQuality(quality)
    }

    fun setEnableToast(enabled: Boolean) {
        dataRepository.setEnableToast(enabled)
    }

    fun setEnablePlayerBadge(enabled: Boolean) {
        dataRepository.setEnablePlayerBadge(enabled)
    }

    fun setEnableAntiRoll(enabled: Boolean) {
        dataRepository.setEnableAntiRoll(enabled)
    }
}
