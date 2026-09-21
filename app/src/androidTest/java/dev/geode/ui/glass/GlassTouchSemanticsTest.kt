package dev.geode.ui.glass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every control in the glass layer is built on `Modifier.waterTouch`, which used to invoke its
 * onClick straight from `pointerInput`. TalkBack's double-tap, Switch Access and keyboard/D-pad
 * activation all dispatch the onClick *semantics action* instead, so none of them could activate
 * anything: buttons, tab pills, settings switches and the transport bar were announced and inert.
 *
 * [performClick] goes through that same semantics action, so these assertions fail if the
 * semantics block is ever dropped again.
 */
@RunWith(AndroidJUnit4::class)
class GlassTouchSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun glassButtonExposesAClickActionAndInvokesIt() {
        var clicks = 0
        compose.setContent {
            GlassButton(text = "Export", onClick = { clicks++ })
        }

        compose
            .onNodeWithText("Export")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertTrue("onClick should have fired, was $clicks", clicks == 1) }
    }

    @Test
    fun glassBubbleButtonExposesAClickAction() {
        var clicks = 0
        compose.setContent {
            GlassBubbleButton(
                icon = Icons.Filled.Add,
                contentDescription = "Add",
                onClick = { clicks++ },
            )
        }

        compose
            .onNodeWithContentDescription("Add")
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertTrue("onClick should have fired, was $clicks", clicks == 1) }
    }
}
