package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CriticalOperationalKnowledgeTest {
    private val snapshot = LiveCapabilityRegistry.Snapshot(DeviceReport.App("3.6.519", 519), true,
        DeviceReport.Accessibility.OFF, false, false, true, null, true, false, "gemini-2.5-flash",
        IntelligenceRouting.Provider.GEMINI, true, null, null)
    @Test fun naturalSelfKnowledgeQuestionsResolveToMeasuredTopicsWithoutExecutingAnAction() {
        for (question in listOf("What version are you currently on?", "Which build am I running?", "Tell me your installed release"))
            assertEquals(LiveCapabilityRegistry.Topic.VERSION, LiveCapabilityRegistry.topic(question))
        for (question in listOf("Can you read my screen?", "Do you understand my display?", "What screen access do you support?"))
            assertEquals(LiveCapabilityRegistry.Topic.SCREEN, LiveCapabilityRegistry.topic(question))
        for (question in listOf("What new features do you come with?", "What new features are available?", "Any new capabilities?"))
            assertEquals(LiveCapabilityRegistry.Topic.FEATURES, LiveCapabilityRegistry.topic(question))
        assertNull(LiveCapabilityRegistry.topic("read my screen"))
        assertNull(LiveCapabilityRegistry.topic("confirm screen action"))
        assertNull(LiveCapabilityRegistry.topic("update app"))
        assertNull(LiveCapabilityRegistry.topic("How do I build a shed?"))
        assertNull(LiveCapabilityRegistry.topic("What version of Python should I install?"))
        assertNull(LiveCapabilityRegistry.topic("What does a release mean?"))
        assertEquals(LiveCapabilityRegistry.Topic.VERSION, LiveCapabilityRegistry.topic("what about now?", "which build are you running?"))
    }
    @Test fun installedVersionAndDisabledFeaturesNeverDependOnConfiguredKeysOrFeatureRequests() {
        val version = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.VERSION, snapshot)
        assertTrue(version.contains("3.6.519")); assertTrue(version.contains("build 519"))
        val screen = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.SCREEN, snapshot)
        assertTrue(screen.contains("includes Accessibility screen reading")); assertTrue(screen.contains("Enable Jarvis"))
        assertFalse(screen.contains("connected now")); assertTrue(screen.contains("No screen content was read"))
        val connected = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.SCREEN,
            snapshot.copy(accessibility = DeviceReport.Accessibility.REMOTE_CONNECTED))
        assertTrue(connected.contains("connected now")); assertTrue(connected.contains("consequential actions require current approval"))
        val unavailable = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.VERSION, snapshot.copy(version = null))
        assertTrue(unavailable.contains("could not be read")); assertFalse(unavailable.contains("3.6.519"))
        val voice = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.VOICE, snapshot)
        assertTrue(voice.contains("no fresh live observation")); assertFalse(voice.contains("wake listening"))
    }
    @Test fun tomorrowAndSundayForecastsSelectTheirCalendarPeriodsBeforeRainFiltering() {
        val periods = JSONArray()
        for ((offset, name) in listOf("Friday", "Saturday", "Sunday", "Monday").withIndex()) {
            val day = 23 + offset
            periods.put(JSONObject().put("name", name).put("startTime", "2026-10-${day}T06:00:00-04:00")
                .put("endTime", "2026-10-${day}T18:00:00-04:00").put("shortForecast", if (offset == 0) "Rain" else "Clear")
                .put("detailedForecast", "$name conditions").put("probabilityOfPrecipitation", JSONObject().put("value", if (offset == 0) 90 else 0)))
        }
        val now = Instant.parse("2026-10-23T08:00:00Z")
        val sunday = OnlineAnswers.weatherSummary(periods, "Will it rain Sunday?", "Example, VA", now)
        assertTrue(sunday.contains("Sunday conditions")); assertTrue(sunday.contains("2026-10-25"))
        assertFalse(sunday.contains("Friday conditions")); assertFalse(sunday.contains("Saturday conditions"))
        val tomorrow = OnlineAnswers.weatherSummary(periods, "Weather tomorrow", "Example, VA", now)
        assertTrue(tomorrow.contains("Saturday conditions")); assertFalse(tomorrow.contains("Friday conditions"))
        val next = OnlineAnswers.weatherSummary(periods, "Weather next Sunday", "Example, VA", Instant.parse("2026-10-25T08:00:00Z"))
        assertTrue(next.contains("2026-11-01")); assertTrue(next.contains("no forecast is available"))
        assertFalse(next.contains("Sunday conditions"))
    }
    @Test fun correctionAndShortFollowUpsKeepTheWeatherDateButUnrelatedConversationClearsIt() {
        val history = listOf("What is the forecast?", "No, Sunday please")
        assertEquals("Will it rain then? for sunday", ForecastDates.contextual("Will it rain then?", history))
        assertEquals("weather for next sunday", ForecastDates.contextual("I meant next Sunday", history))
        assertNull(ForecastDates.contextual("Sunday?", listOf("What is the forecast?", "Which version are you?")))
        assertNull(ForecastDates.contextual("Remind me about the weather Sunday", history))
        assertNull(ForecastDates.contextual("Sunday; send a message", history))
        assertEquals(LocalDate.parse("2026-10-25"), ForecastDates.target("Friday, no I meant Sunday", LocalDate.parse("2026-10-23")))
    }
}
