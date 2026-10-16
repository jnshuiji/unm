package pochita.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [pochita.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent {
      MainScreenContent(
        status = pochita.data.LsposedStatus(
          isActivated = true,
          frameworkName = "LSPosed",
          frameworkVersion = "1.9.3",
          apiVersion = 102,
        ),
        config = pochita.data.ModuleConfig(),
        onRuleUrlChanged = {},
        onUpdateRuleClicked = {},
        onResetRuleClicked = {},
      )
    }
  }

  @Test
  fun elements_exist() {
    composeTestRule.onNodeWithText("动态规则配置").assertExists()
    composeTestRule.onNodeWithText("立即同步").assertExists()
    composeTestRule.onNodeWithText("恢复内置").assertExists()
  }
}
