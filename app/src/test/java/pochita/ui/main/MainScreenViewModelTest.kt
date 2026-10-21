package pochita.ui.main

import android.app.Application
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pochita.data.DataRepository
import pochita.data.LsposedStatus
import pochita.data.ModuleConfig
import pochita.data.PreferenceKeys

class MainScreenViewModelTest {
    @Test
    fun status_initialState() = runTest {
        val viewModel = MainScreenViewModel(Application(), FakeDataRepository())
        assertEquals(false, viewModel.status.first().isActivated)
    }

    @Test
    fun status_activatedState() = runTest {
        val fakeRepo = FakeDataRepository(LsposedStatus(isActivated = true, frameworkName = "LSPosed", apiVersion = 102))
        val viewModel = MainScreenViewModel(Application(), fakeRepo)
        val state = viewModel.status.filter { it.isActivated }.first()
        assertEquals(true, state.isActivated)
        assertEquals("LSPosed", state.frameworkName)
        assertEquals(102, state.apiVersion)
    }

    @Test
    fun testToggleSource_enablesSource() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("bodian")))
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.toggleSource("kuwo", true)
        val config = viewModel.config.first { it.enabledSources.contains("kuwo") }
        assertEquals(listOf("bodian", "kuwo"), config.enabledSources)
    }

    @Test
    fun testToggleSource_disablesSource() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("bodian", "kuwo", "migu")))
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.toggleSource("kuwo", false)
        val config = viewModel.config.first { !it.enabledSources.contains("kuwo") }
        assertEquals(listOf("bodian", "migu"), config.enabledSources)
    }

    @Test
    fun testToggleSource_guardMinOneSource() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("bodian")))
        val viewModel = MainScreenViewModel(Application(), repo)

        // Attempting to disable the only remaining source must be guarded
        viewModel.toggleSource("bodian", false)
        val config = viewModel.config.first()
        assertEquals(listOf("bodian"), config.enabledSources)
    }

    @Test
    fun testMoveSourceUp_swapsWithPrevious() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("bodian", "kuwo", "migu")))
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.moveSourceUp("kuwo")
        val config = viewModel.config.first { it.enabledSources[0] == "kuwo" }
        assertEquals(listOf("kuwo", "bodian", "migu"), config.enabledSources)
    }

    @Test
    fun testMoveSourceDown_swapsWithNext() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("bodian", "kuwo", "migu")))
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.moveSourceDown("kuwo")
        val config = viewModel.config.first { it.enabledSources[2] == "kuwo" }
        assertEquals(listOf("bodian", "migu", "kuwo"), config.enabledSources)
    }

    @Test
    fun testResetSourceOrder_restoresDefaults() = runTest {
        val repo = FakeDataRepository(initialConfig = ModuleConfig(enabledSources = listOf("kugou")))
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.resetSourceOrder()
        val config = viewModel.config.first { it.enabledSources == PreferenceKeys.DEFAULT_ENABLED_SOURCES }
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, config.enabledSources)
    }

    @Test
    fun testSetPreferredQuality() = runTest {
        val repo = FakeDataRepository()
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.setPreferredQuality("flac")
        val config = viewModel.config.first { it.preferredQuality == "flac" }
        assertEquals("flac", config.preferredQuality)
    }

    @Test
    fun testSetFeedbackAndAntiRollToggles() = runTest {
        val repo = FakeDataRepository()
        val viewModel = MainScreenViewModel(Application(), repo)

        viewModel.setEnableToast(false)
        viewModel.setEnablePlayerBadge(false)
        viewModel.setEnableAntiRoll(false)

        val config = viewModel.config.first { !it.enableToast && !it.enablePlayerBadge && !it.enableAntiRoll }
        assertEquals(false, config.enableToast)
        assertEquals(false, config.enablePlayerBadge)
        assertEquals(false, config.enableAntiRoll)
    }
}

private class FakeDataRepository(
    initialStatus: LsposedStatus = LsposedStatus(),
    initialConfig: ModuleConfig = ModuleConfig(),
) : DataRepository {
    private val _status = MutableStateFlow(initialStatus)
    override val lsposedStatus: Flow<LsposedStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow(initialConfig)
    override val config: Flow<ModuleConfig> = _config.asStateFlow()
    override val currentConfig: ModuleConfig get() = _config.value

    override fun setRuleUrl(url: String) {
        _config.value = _config.value.copy(ruleUrl = url)
    }

    override suspend fun updateRule(): String = "ok"
    override suspend fun resetRule(): String = "ok"

    override fun setEnabledSources(sources: List<String>) {
        _config.value = _config.value.copy(enabledSources = sources)
    }

    override fun setPreferredQuality(quality: String) {
        _config.value = _config.value.copy(preferredQuality = quality)
    }

    override fun setEnableToast(enabled: Boolean) {
        _config.value = _config.value.copy(enableToast = enabled)
    }

    override fun setEnablePlayerBadge(enabled: Boolean) {
        _config.value = _config.value.copy(enablePlayerBadge = enabled)
    }

    override fun setEnableAntiRoll(enabled: Boolean) {
        _config.value = _config.value.copy(enableAntiRoll = enabled)
    }
}
