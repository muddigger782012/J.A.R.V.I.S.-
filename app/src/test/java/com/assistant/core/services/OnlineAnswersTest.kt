package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OnlineAnswersTest {
    @Test fun fiveDayForecastIncludesAllFiveCalendarDays() {
        val periods = JSONArray()
        for (day in 10..16) {
            periods.put(JSONObject().put("name", "Day $day").put("startTime", "2026-10-${day}T06:00:00-04:00")
                .put("endTime", "2026-10-${day}T18:00:00-04:00").put("shortForecast", "Clear").put("detailedForecast", "Forecast $day."))
        }
        val answer = OnlineAnswers.weatherSummary(periods, "What's the forecast for the next 5 days", "Elizabeth City, NC", Instant.parse("2026-10-10T04:42:00Z"))
        for (day in 11..15) assertTrue(answer.contains("Forecast $day."))
        assertFalse(answer.contains("Forecast 10.")); assertFalse(answer.contains("Forecast 16."))
    }
    @Test fun providerErrorsExposeCodesWithoutSecrets() {
        val json = JSONObject("""{"error":{"status":"INVALID_ARGUMENT","message":"secret key should never be displayed","details":[{"reason":"API_KEY_INVALID"}]}}""")
        val text = OnlineAnswers.geminiError(400, json)
        assertTrue(text.contains("API_KEY_INVALID"))
        assertTrue(text.contains("HTTP 400"))
        assertFalse(text.contains("secret key"))
    }
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
