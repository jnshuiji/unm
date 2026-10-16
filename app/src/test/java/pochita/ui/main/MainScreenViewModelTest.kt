package pochita.ui.main

import android.app.Application
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pochita.data.DataRepository
import pochita.data.LsposedStatus
import pochita.data.ModuleConfig

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
}

private class FakeDataRepository(
  private val initialStatus: LsposedStatus = LsposedStatus(),
) : DataRepository {
  override val lsposedStatus: Flow<LsposedStatus> = flowOf(initialStatus)
  override val config: Flow<ModuleConfig> = flowOf(ModuleConfig())
  override fun setRuleUrl(url: String) {}
  override suspend fun updateRule(): String = "ok"
  override suspend fun resetRule(): String = "ok"
}
