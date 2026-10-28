package com.assistant.core.adapters

import com.assistant.core.models.ActionResult

/** Android accepting a global action is observable; it is not proof of its final screen state. */
internal object AccessibilityActionResult {
    fun perform(id: String, label: String, request: () -> Boolean): ActionResult = try {
        if (request()) ActionResult(id, true, "ACCESSIBILITY", "$label action accepted by Android.")
        else ActionResult(id, false, "ACCESSIBILITY", "$label action was not accepted by Android. Check Accessibility access and the current screen.")
    } catch (_: Exception) {
        ActionResult(id, false, "ACCESSIBILITY", "$label could not be verified. Check Accessibility access before trying again.")
    }
}
