package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OnlineAnswersTest {
    @Test fun preciseLocationCommandIsRecognized() {
        assertTrue(OnlineAnswers.wantsDeviceLocation("Use my precise location for the weather"))
        assertFalse(OnlineAnswers.wantsDeviceLocation("What's tomorrow's weather"))
        assertFalse(OnlineAnswers.wantsDeviceLocation("Use my current location for navigation"))
    }
    @Test fun weatherRoutingPreservesDeviceCommands() {
        assertTrue(OnlineAnswers.isWeather("What day is the rain in the forecast next"))
        assertFalse(OnlineAnswers.isWeather("Call Rain"))
        assertFalse(OnlineAnswers.isWeather("Remind me to check the weather"))
    }
    @Test fun rainSkipsExpiredPeriodsAndIncludesProbability() {
        val periods = JSONArray("""[
            {"name":"Friday","startTime":"2026-10-09T06:00:00-04:00","endTime":"2026-10-09T18:00:00-04:00","shortForecast":"Rain","detailedForecast":"Old rain"},
            {"name":"Saturday","startTime":"2026-10-10T06:00:00-04:00","endTime":"2026-10-10T18:00:00-04:00","shortForecast":"Chance Showers","detailedForecast":"A chance of showers.","probabilityOfPrecipitation":{"value":40}}
        ]""")
        val reply = OnlineAnswers.weatherSummary(periods, "Rain next?", "Chesapeake, VA", Instant.parse("2026-10-10T00:00:00Z"))
        assertTrue(reply.contains("Saturday")); assertTrue(reply.contains("40%")); assertFalse(reply.contains("Old rain"))
    }
    @Test fun geminiParsingIgnoresThoughtsAndHandlesEmptyCandidates() {
        assertNull(OnlineAnswers.geminiText(JSONObject("{}")))
        val json = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"Answer"}]}}]}""")
        assertEquals("Answer", OnlineAnswers.geminiText(json))
    }
}
