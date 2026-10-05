package com.wecare.roadguard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.wecare.roadguard.ui.GuardedField
import com.wecare.roadguard.ui.LocalTypingBlocked
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TypingProtectionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun drivingTransitionDisablesExistingInputAndRestoresAfterStop() {
        val blocked = mutableStateOf(false)
        val text = mutableStateOf("")
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalTypingBlocked provides blocked.value) {
            GuardedField(text.value, { text.value = it }, "Message")
        } } }
        compose.onNodeWithText("Message").performTextInput("Draft")
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText("Message").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("Draft", text.value); blocked.value = false }
        compose.onNodeWithText("Message").assertIsEnabled().performTextInput(" kept")
        compose.runOnIdle { assertEquals("Draft kept", text.value) }
    }
}
