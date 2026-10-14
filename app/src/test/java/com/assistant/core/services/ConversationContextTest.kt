package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationContextTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun conversationSwitchAndLateReplyCannotContaminateAnotherOrReopenedChat() {
        val context = ConversationContext(temporary.root)
        val old = context.bind("first")
        assertTrue(context.remember(old, "What is torque?", "A turning force"))
        val current = context.bind("second", force = true)
        assertTrue(context.snapshot().isEmpty())
        assertFalse(context.remember(old, "late", "old cloud answer"))
        assertTrue(context.remember(current, "What is power?", "Work per time"))
        context.bind("first", force = true)
        assertEquals("What is torque?", context.snapshot().single().first)
        assertFalse(context.accepts(old))
        val reader = ConversationContext(temporary.root); reader.bind("second")
        assertEquals("What is power?", reader.snapshot().single().first)
    }
    @Test fun contextIsBoundedAndPrivateManagementListsAreExcludedFromCloudHistory() {
        val context = ConversationContext(temporary.root)
        val token = context.bind("../../not-a-path")
        repeat(20) { assertTrue(context.remember(token, "question $it", "answer $it")) }
        assertEquals(12, context.snapshot().size)
        assertFalse(context.remember(token, "show saved commands", "private list"))
        assertFalse(context.remember(token, "show capability requests intelligence plan", "private requests"))
        assertTrue(context.remember(token, "Show intelligence plan progress", "public progress report"))
        assertEquals(12, context.snapshot().size)
        assertTrue(temporary.root.listFiles()!!.all { it.parentFile == temporary.root })
    }
}
