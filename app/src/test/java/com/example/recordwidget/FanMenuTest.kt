package com.example.recordwidget

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FanMenuTest {

    private fun findByDescription(v: View, text: String): View? {
        if (v.contentDescription?.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findByDescription(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test
    fun tappingTheFanButtonOpensTheFan() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val content = a.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0) as FrameLayout
                val before = root.childCount
                val button = findByDescription(root, a.getString(R.string.cd_fan_open))
                assertNotNull("fan button not found", button)
                // the button must be the topmost child, so it gets the touch
                assertEquals("fan button is not on top", root.getChildAt(root.childCount - 1), button)
                assertTrue("fan button not clickable", button!!.isClickable && button.hasOnClickListeners())
                button.performClick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertEquals("fan overlay was not added", before + 1, root.childCount)
                assertNotNull(
                    "fan items not shown",
                    findByDescription(root, a.getString(R.string.fan_add_video))?.takeIf { it.alpha > 0.5f },
                )
            }
        }
    }
}
