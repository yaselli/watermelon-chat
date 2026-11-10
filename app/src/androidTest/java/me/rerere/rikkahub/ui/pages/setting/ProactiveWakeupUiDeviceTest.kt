package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProactiveWakeupUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun optInCanBeEnabledAndAPendingAppointmentCanBeCancelled() {
        val assistant = Assistant(name = "测试角色")
        val task =
            ProactiveWakeup(
                "task",
                assistant.id.toString(),
                "chat",
                System.currentTimeMillis() + 3_600_000,
                "Asia/Shanghai",
                "到点问问今天过得怎么样",
            )
        var setting by
            mutableStateOf(ProactiveMessageSetting(enabled = true, aiWakeups = listOf(task)))
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
                MaterialTheme {
                    Column(Modifier.testTag("wakeups").verticalScroll(rememberScrollState())) {
                        ProactiveWakeupSettingsCard(setting, listOf(assistant), true) { change ->
                            setting = change(setting)
                        }
                    }
                }
            }
        }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(setting.allowAiWakeups) }
        compose.onNodeWithText(task.reason).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ProactiveWakeupStatus.Cancelled, setting.aiWakeups.single().status)
        }
        compose.onNodeWithText("用户已取消", substring = true).assertDoesNotExist()
        scrollTo("历史记录（1）")
        compose.onNodeWithText("历史记录（1）").performClick()
        scrollTo("用户已取消", substring = true)
        compose.onNodeWithText("用户已取消", substring = true).assertIsDisplayed()
    }

    @Test
    fun endedAppointmentsAreCollapsedAndClearingHistoryKeepsAllActiveAppointments() {
        val assistant = Assistant(name = "测试角色")
        val base = ProactiveWakeup(
            "pending", assistant.id.toString(), "chat", System.currentTimeMillis() + 3_600_000,
            "Asia/Shanghai", "待执行约定",
        )
        val pending = base
        val running = base.copy(id = "running", reason = "正在执行约定", status = ProactiveWakeupStatus.Running)
        val ended = listOf(
            base.copy(id = "sent", reason = "已发送约定", status = ProactiveWakeupStatus.Sent),
            base.copy(id = "failed", reason = "失败约定", status = ProactiveWakeupStatus.Failed),
            base.copy(id = "cancelled", reason = "取消约定", status = ProactiveWakeupStatus.Cancelled),
        )
        var setting by mutableStateOf(ProactiveMessageSetting(
            enabled = true, allowAiWakeups = true, aiWakeups = listOf(pending, running) + ended,
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
                MaterialTheme {
                    Column(Modifier.testTag("wakeups").verticalScroll(rememberScrollState())) {
                        ProactiveWakeupSettingsCard(setting, listOf(assistant), true) { change ->
                            setting = change(setting)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(pending.reason).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(running.reason).performScrollTo().assertIsDisplayed()
        ended.forEach { compose.onNodeWithText(it.reason).assertDoesNotExist() }
        scrollTo("历史记录（3）")
        compose.onNodeWithText("历史记录（3）").performClick()
        ended.forEach {
            scrollTo(it.reason)
            compose.onNodeWithText(it.reason).assertIsDisplayed()
        }
        scrollTo("清空历史")
        compose.onNodeWithText("清空历史").performClick()
        compose.runOnIdle { assertEquals(listOf(pending, running), setting.aiWakeups) }
        compose.onNodeWithText("历史记录（3）").assertDoesNotExist()
        ended.forEach { compose.onNodeWithText(it.reason).assertDoesNotExist() }
        compose.onNodeWithText(pending.reason).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(running.reason).performScrollTo().assertIsDisplayed()
    }

    private fun scrollTo(text: String, substring: Boolean = false) {
        compose.onNodeWithTag("wakeups").performScrollToNode(hasText(text, substring = substring))
    }
}
