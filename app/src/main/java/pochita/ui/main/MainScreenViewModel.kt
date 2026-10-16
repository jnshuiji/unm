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
            initialValue = ModuleConfig(),
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
}
