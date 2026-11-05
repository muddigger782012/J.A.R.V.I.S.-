package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class WeatherPlaceQueryTest {
    @Test fun requestsExplicitChesapeakeInsteadOfUsingSavedElizabethCity() {
        for (q in listOf(
            "what's the weather supposed to be like in Chesapeake Virginia today",
            "weather in Chesapeake, VA",
            "forecast for Chesapeake Virginia tomorrow"
        )) {
            assertEquals(WeatherPlaceQuery.Place("Chesapeake", "Virginia"), WeatherPlaceQuery.parse(q))
        }
        assertEquals(WeatherPlaceQuery.Place("Elizabeth City", "North Carolina"),
            WeatherPlaceQuery.parse("weather for Elizabeth City, NC tonight"))
    }
    @Test fun unspecifiedCityContinuesToUseSavedWeatherMode() {
        for (q in listOf("weather today", "forecast for tonight", "will it rain tomorrow",
            "open Chrome", "how to repair my engine")) {
            assertNull(q, WeatherPlaceQuery.parse(q))
        }
    }
    @Test fun requiresVerifiedCityAndStateWithoutSilentSubstitution() {
        val chesapeake = WeatherPlaceQuery.Candidate("Chesapeake", "Virginia", "US", 36.77, -76.28)
        val elizabeth = WeatherPlaceQuery.Candidate("Elizabeth City", "North Carolina", "US", 36.29, -76.23)
        assertEquals(chesapeake, WeatherPlaceQuery.choose(
            WeatherPlaceQuery.Place("Chesapeake", "Virginia"), listOf(elizabeth, chesapeake)))
        assertNull(WeatherPlaceQuery.choose(WeatherPlaceQuery.Place("Chesapeake", "North Carolina"),
            listOf(chesapeake, elizabeth)))
        assertNull(WeatherPlaceQuery.choose(WeatherPlaceQuery.Place("Springfield", null),
            listOf(WeatherPlaceQuery.Candidate("Springfield", "Illinois", "US", 39.78, -89.64),
                WeatherPlaceQuery.Candidate("Springfield", "Missouri", "US", 37.20, -93.29))))
    }
    @Test fun userCorrectingWrongCityRequeriesNamedPlaceWithSameDate() {
        val history = listOf("what's the weather supposed to be like in Elizabeth City North Carolina today")
        assertEquals("weather in Chesapeake, Virginia today",
            ForecastDates.contextual("I said I wanted to know about Chesapeake Virginia", history))
        assertEquals(WeatherPlaceQuery.Place("Chesapeake", "Virginia"),
            WeatherPlaceQuery.parse(ForecastDates.contextual(
                "I said I wanted to know about Chesapeake Virginia", history).orEmpty()))
    }
    @Test fun weatherCityCorrectionDoesNotRewriteUnrelatedDeviceCommands() {
        assertNull(ForecastDates.contextual("I said turn the music down", listOf("weather today")))
        assertNull(WeatherPlaceQuery.correctedLocation("open my browser"))
    }
}
