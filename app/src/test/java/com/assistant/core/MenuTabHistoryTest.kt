package com.assistant.core

import org.junit.Assert.*
import org.junit.Test

class MenuTabHistoryTest {
    @Test fun backReturnsThroughVisitedPanelsThenChatWithoutRecordingBackAsAVisit() {
        val history = MenuTabHistory()
        history.visit(1); history.visit(4); history.visit(6)
        assertEquals(4, history.back()); assertEquals(1, history.back())
        assertEquals(0, history.back()); assertEquals(0, history.back())
    }
    @Test fun closingOrSelectingChatClearsThePreviousUtilityTrail() {
        val history = MenuTabHistory(); history.visit(2); history.visit(7); history.close()
        assertEquals(0, history.back())
        history.visit(3); history.visit(0); history.visit(5)
        assertEquals(0, history.back())
    }
    @Test fun recreationRestoresOnlyBoundedSupportedDestinations() {
        val before = MenuTabHistory(); before.visit(3); before.visit(7)
        val restored = MenuTabHistory(before.save())
        assertEquals(7, restored.current); assertEquals(3, restored.back())
        assertEquals(0, MenuTabHistory(intArrayOf(0, 90)).current)
        assertEquals(0, MenuTabHistory(intArrayOf(5, 1)).current)
        assertThrows(IllegalArgumentException::class.java) { restored.visit(8) }
    }
    @Test fun repeatedSelectionsCannotTrapBackAndLongTrailsStayBounded() {
        val history = MenuTabHistory(); history.visit(1); history.visit(1)
        assertEquals(0, history.back())
        repeat(100) { history.visit(it % 7 + 1) }
        assertEquals(32, history.save().size)
        repeat(32) { history.back() }; assertEquals(0, history.current)
    }
}
