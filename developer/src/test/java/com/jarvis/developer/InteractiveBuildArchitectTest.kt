package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class InteractiveBuildArchitectTest {
    @Test fun approvedDefaultsArePreserved() {
        val defaults = InteractiveBuildArchitect.defaults()
        assertEquals(InteractiveBuildArchitect.Consultation.COLLABORATIVE, defaults.consultation)
        assertEquals(InteractiveBuildArchitect.Navigation.DRAWER, defaults.navigation)
        assertEquals(InteractiveBuildArchitect.Density.BALANCED, defaults.density)
        assertEquals(InteractiveBuildArchitect.ControlStyle.HYBRID, defaults.controls)
        assertEquals(100, defaults.scalePercent)
        assertTrue(defaults.proactiveRecommendations)
        assertTrue(defaults.adaptiveLayout)
        assertTrue(defaults.preserveJarvisTheme)
    }

    @Test fun responsivePreviewSizesDiffer() {
        assertTrue(InteractiveBuildArchitect.previewWidthDp(InteractiveBuildArchitect.Screen.TABLET) >
            InteractiveBuildArchitect.previewWidthDp(InteractiveBuildArchitect.Screen.PHONE))
    }

    @Test fun approvedScaleCannotBeInvalid() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractiveBuildArchitect.Configuration(scalePercent = 137)
        }
    }

    @Test fun unreviewedChoicesCannotBeApproved() {
        val card = InteractiveBuildArchitect.ConsultationCard(
            "jarvis-developer", "navigation",
            "Select navigation",
            listOf(InteractiveBuildArchitect.Choice(
                "navigation", "Navigation", InteractiveBuildArchitect.ChoiceType.SINGLE_SELECT,
                listOf("drawer", "tabs"), "drawer", "Drawer accommodates more tools"
            ))
        )
        assertEquals("drawer", InteractiveBuildArchitect.validate(
            card, InteractiveBuildArchitect.Selection("navigation", mapOf("navigation" to "drawer"))
        ).values["navigation"])
        assertThrows(IllegalArgumentException::class.java) {
            InteractiveBuildArchitect.validate(card,
                InteractiveBuildArchitect.Selection("navigation", mapOf("navigation" to "unsafe")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InteractiveBuildArchitect.validate(card,
                InteractiveBuildArchitect.Selection("other", mapOf("navigation" to "drawer")))
        }
    }

    @Test fun unknownChoiceKeysAreRejected() {
        val card = InteractiveBuildArchitect.ConsultationCard(
            "jarvis-developer", "scale", "Choose scale",
            listOf(InteractiveBuildArchitect.Choice(
                "scale", "Scale", InteractiveBuildArchitect.ChoiceType.SCALE,
                recommendedValue = "100", rationale = "Maintain readable controls"
            ))
        )
        assertThrows(IllegalArgumentException::class.java) {
            InteractiveBuildArchitect.validate(card,
                InteractiveBuildArchitect.Selection("scale", mapOf("scale" to "100", "execute" to "true")))
        }
    }
}
