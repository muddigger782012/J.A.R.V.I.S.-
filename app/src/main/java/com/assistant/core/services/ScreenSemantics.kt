package com.assistant.core.services

import java.util.Locale

/** Android-provided semantics are selection hints, never authorization or executable instructions. */
object ScreenSemantics {
    enum class Role(val label: String) {
        BUTTON("button"), SWITCH("switch"), CHECKBOX("checkbox"), RADIO_BUTTON("radio button"),
        TEXT_FIELD("text field"), MENU_ITEM("menu item"), LINK("link"),
        SCROLL_AREA("scroll area"), CONTROL("control")
    }
    fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun role(className: String, editable: Boolean, scrollable: Boolean): Role = when {
        editable || className.endsWith("EditText") -> Role.TEXT_FIELD
        className.endsWith("CheckBox") -> Role.CHECKBOX
        className.endsWith("RadioButton") -> Role.RADIO_BUTTON
        className.endsWith("Switch") || className.endsWith("SwitchCompat") || className.endsWith("ToggleButton") -> Role.SWITCH
        className.endsWith("Button") -> Role.BUTTON
        className.endsWith("MenuItemView") -> Role.MENU_ITEM
        scrollable -> Role.SCROLL_AREA
        else -> Role.CONTROL
    }
    fun namedRole(value: String): Role? = Role.values().firstOrNull { it.label == clean(value).replace("check box", "checkbox") }
    fun matches(control: ScreenControls.Control, command: ScreenControls.Command): Boolean =
        clean(control.label) == clean(command.label) && (command.role == null || command.role == control.role) &&
            (command.contextLabel.isBlank() || clean(control.contextLabel) == clean(command.contextLabel))
    fun describe(control: ScreenControls.Control): String = listOfNotNull(
        control.role.label,
        control.contextLabel.takeIf { it.isNotBlank() }?.let { "under $it" },
        if (!control.enabled) "disabled" else null,
        control.checked?.let { if (it) "checked" else "not checked" },
        if (control.selected) "selected" else null
    ).joinToString(", ")
}
