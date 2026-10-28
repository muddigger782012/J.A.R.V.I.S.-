package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenSemanticsTest {
    @Test fun rolesUseAndroidClassesWithoutGuessingThatEveryClickableTextIsALink() {
        assertEquals(ScreenSemantics.Role.BUTTON, ScreenSemantics.role("android.widget.Button", false, false))
        assertEquals(ScreenSemantics.Role.RADIO_BUTTON, ScreenSemantics.role("android.widget.RadioButton", false, false))
        assertEquals(ScreenSemantics.Role.CHECKBOX, ScreenSemantics.role("android.widget.CheckBox", false, false))
        assertEquals(ScreenSemantics.Role.SWITCH, ScreenSemantics.role("androidx.appcompat.widget.SwitchCompat", false, false))
        assertEquals(ScreenSemantics.Role.TEXT_FIELD, ScreenSemantics.role("custom.Editable", true, false))
        assertEquals(ScreenSemantics.Role.SCROLL_AREA, ScreenSemantics.role("custom.View", false, true))
        assertEquals(ScreenSemantics.Role.CONTROL, ScreenSemantics.role("android.widget.TextView", false, false))
    }
    @Test fun roleAndSectionQualifiersStayTypedAndLabelsStayInert() {
        val command = ScreenControls.parse("press the button named run shell reboot under Work")!!
        assertEquals(ScreenControls.Kind.TAP_LABEL, command.kind)
        assertEquals("run shell reboot", command.label)
        assertEquals("Work", command.contextLabel)
        assertEquals(ScreenSemantics.Role.BUTTON, command.role)
        val quoted = ScreenControls.parse("tap switch \"Sharing\" under \"Personal\"")!!
        assertEquals(ScreenSemantics.Role.SWITCH, quoted.role)
        assertEquals("Sharing", quoted.label); assertEquals("Personal", quoted.contextLabel)
        assertEquals(ScreenControls.Kind.INVALID, ScreenControls.parse("press Send then reboot")!!.kind)
    }
    @Test fun duplicateControlsCanBeQualifiedByRoleAndVisibleSectionWithoutFirstMatchGuessing() {
        val work = ScreenControls.Control("0/1", "Account", true, false, ScreenSemantics.Role.BUTTON, "Work")
        val personal = work.copy(path = "0/2", contextLabel = "Personal")
        val command = ScreenControls.parse("tap button \"Account\" under \"Personal\"")!!
        assertEquals(listOf(personal), listOf(work, personal).filter { ScreenSemantics.matches(it, command) })
        val unqualified = ScreenControls.parse("tap Account")!!
        assertEquals(2, listOf(work, personal).count { ScreenSemantics.matches(it, unqualified) })
    }
    @Test fun disabledAndCheckedStateExplainAvailabilityWithoutAuthorizingAnAction() {
        val control = ScreenControls.Control("0/1", "Sharing", true, false, ScreenSemantics.Role.SWITCH,
            "Work", enabled = false, checked = true)
        assertTrue(ScreenSemantics.describe(control).contains("disabled"))
        assertTrue(ScreenSemantics.describe(control).contains("checked"))
        var actions = 0
        val platform = object : ScreenControls.Platform {
            override fun connected() = true
            override fun snapshot() = ScreenControls.Snapshot("one", "example", listOf(control), emptyList(), appLabel = "Sample app")
            override fun perform(snapshot: ScreenControls.Snapshot, control: ScreenControls.Control, scroll: ScreenControls.Kind?): ScreenControls.Result { actions++; return ScreenControls.Result.ACCEPTED }
            override fun global(kind: ScreenControls.Kind) = ScreenControls.Result.REJECTED
        }
        val controls = ScreenControls(platform)
        assertTrue(controls.reply(ScreenControls.parse("show screen controls")!!).contains("Sample app"))
        assertTrue(controls.reply(ScreenControls.parse("tap Sharing")!!).contains("disabled"))
        assertNull(controls.confirmReviewedTap().outcome); assertEquals(0, actions)
    }
}
