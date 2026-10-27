package com.assistant.core

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 8 reapplies themed gradient attributes; a literal-only radius is lost there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class HudGradientCompatibilityTest {
    @Test fun everyPaletteRetainsItsTintAndOriginalRadiusWhenThemeIsReapplied() {
        for (name in ThemePreferences.ids) {
            val context: Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), ThemePreferences.style(name))
            val drawable = ContextCompat.getDrawable(context, R.drawable.jarvis_pulse_overlay) as GradientDrawable
            repeat(2) { drawable.applyTheme(context.theme) }
            assertEquals(name, 420f * context.resources.displayMetrics.density, drawable.gradientRadius, 0.1f)
            val tint = TypedValue()
            assertTrue(context.theme.resolveAttribute(R.attr.jarvis_pulse_tint, tint, true))
            assertEquals(name, tint.data, drawable.colors!!.first())
        }
    }
}
