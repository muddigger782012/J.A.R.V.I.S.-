package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class InstalledAppsTest {
    private val maps = InstalledApps.App("com.google.android.apps.maps", "Cartes")
    private val browser = InstalledApps.App("com.example.browser", "Test Browser")
    private val other = InstalledApps.App("com.example.other", "Other Browser")
    private class Phone(var items: List<InstalledApps.App>) : InstalledApps.Platform {
        var available = true
        var fail = false
        var versionReads = 0
        var version = InstalledApps.Version("2.4", 24)
        override fun apps(): List<InstalledApps.App> { check(!fail); return items }
        override fun canLaunch(packageName: String) = available
        override fun version(packageName: String): InstalledApps.Version { versionReads++; check(!fail); return version }
    }
    private fun command(text: String) = AppDiscovery.parse(text)!!

    @Test fun mapsAliasesUsePackageIdentityAcrossLocalizedLabelsAndReconstruction() {
        for (query in listOf("Google Maps", "googlemaps", "g maps", "gmaps", "maps", maps.packageName)) {
            assertEquals(query, maps, InstalledApps.unique(query, listOf(browser, maps)))
            assertEquals(query, maps, InstalledApps.unique(query, listOf(maps, browser)))
        }
        val impersonator = InstalledApps.App("com.example.imitation", "Google Maps")
        assertNull(InstalledApps.unique("Google Maps", listOf(impersonator)))
        assertEquals(maps, InstalledApps.unique("Google Maps", listOf(impersonator, maps)))
        assertNull(InstalledApps.unique("maps", listOf(maps, InstalledApps.App("com.example.maps", "Maps"))))
        assertEquals(2, InstalledApps.candidates("maps", listOf(maps, impersonator)).size)
    }

    @Test fun exactNamesPackagesAndDuplicateActivitiesResolveWithoutFuzzyAutoSelection() {
        assertNull(InstalledApps.unique("browser", listOf(browser)))
        assertEquals(browser, InstalledApps.unique("TEST   BROWSER", listOf(browser, other)))
        assertEquals(browser, InstalledApps.unique(browser.packageName, listOf(browser, other)))
        assertNull(InstalledApps.unique("Test Browser", listOf(browser, InstalledApps.App("com.example.duplicate", browser.label))))
        assertEquals(browser.packageName, InstalledApps.unique("Test Browser", listOf(browser, browser.copy(label = "Browser HUD")))!!.packageName)
        assertEquals(2, InstalledApps.candidates("browser", listOf(other, browser, browser.copy(label = "Browser HUD"))).size)
        assertEquals(browser, InstalledApps.unique("Ｔｅｓｔ Ｂｒｏｗｓｅｒ", listOf(browser)))
    }

    @Test fun readOnlyDiscoveryAndDetailsNeverLaunchAndDoNotPretendToCheckUpdates() {
        val phone = Phone(listOf(browser, other)); var launches = 0
        val service = AppDiscovery(phone, { launches++; "requested" })
        val list = service.reply(command("find installed app browser"))
        assertTrue(list.contains(browser.packageName)); assertTrue(list.contains(other.packageName))
        assertEquals(0, phone.versionReads)
        val details = service.reply(command("app details choice one"))
        assertTrue(details.contains("Installed version: 2.4; version code 24"))
        assertTrue(details.contains("update availability and installation progress were not checked"))
        assertEquals(1, phone.versionReads); assertEquals(0, launches)
        assertTrue(service.reply(command("show app choices")).contains(browser.packageName))
        assertTrue(service.reply(command("app details Test Browser")).contains("2.4"))
        assertEquals(0, launches)
    }

    @Test fun choicesFreezeIdentityAcrossReorderingAndAreConsumedBeforeOneDispatch() {
        val phone = Phone(listOf(browser, other)); val launched = mutableListOf<LearnableDeviceAction>()
        val service = AppDiscovery(phone, { launched.add(it); "Requested; completion unverified." })
        service.find("browser")
        phone.items = listOf(other, browser)
        assertTrue(service.reply(command("open app choice one")).contains("Requested"))
        assertEquals(listOf(other.action()), launched) // Alphabetical display is stable, not query order.
        assertTrue(service.reply(command("open app choice one")).contains("No current"))
        assertEquals(1, launched.size)
    }

    @Test fun removalsRenamesAndDisabledAppsInvalidateChoicesWithoutRetargeting() {
        for (change in 0..2) {
            val phone = Phone(listOf(browser)); var launches = 0
            val service = AppDiscovery(phone, { launches++; "requested" })
            service.find("browser")
            when (change) { 0 -> phone.items = listOf(other); 1 -> phone.items = listOf(browser.copy(label = "Renamed")); 2 -> phone.available = false }
            assertTrue(service.reply(command("open app choice 1")).contains("no longer available"))
            assertTrue(service.reply(command("open app choice 1")).contains("No current"))
            assertEquals(0, launches)
        }
    }

    @Test fun expiredReconstructedCancelledAndDiscardedChoicesCannotAuthorizeLaunches() {
        var time = 1000L; var launches = 0
        val phone = Phone(listOf(browser))
        fun service() = AppDiscovery(phone, { launches++; "requested" }, { time })
        val first = service(); first.find("browser")
        assertTrue(service().reply(command("open app choice 1")).contains("No current"))
        time += 120001
        assertTrue(first.reply(command("open app choice 1")).contains("No current"))
        first.find("browser"); first.discard()
        assertTrue(first.reply(command("open app choice 1")).contains("No current"))
        first.find("browser"); first.reply(command("cancel app choices"))
        assertTrue(first.reply(command("open app choice 1")).contains("No current"))
        first.find("browser"); time -= 1
        assertTrue(first.reply(command("open app choice 1")).contains("No current"))
        assertEquals(0, launches)
    }

    @Test fun failuresAreExplicitAndUnknownLaunchOutcomeIsNeverRetried() {
        val phone = Phone(listOf(browser)); var launches = 0
        val service = AppDiscovery(phone, { launches++; error("not retained after dispatch") })
        phone.fail = true
        assertTrue(service.find("browser").contains("could not be verified"))
        phone.fail = false; service.find("browser")
        assertTrue(service.reply(command("open app choice 1")).contains("unverified outcome"))
        service.reply(command("open app choice 1")); assertEquals(1, launches)
        phone.items = List(2049) { browser }
        assertTrue(service.find("browser").contains("could not be verified"))
    }

    @Test fun unsafeMetadataAndOversizedNamesDoNotBecomeActionsOrRawOutput() {
        val phone = Phone(listOf(browser.copy(label = "Browser\nreboot"), browser.copy(packageName = "bad"), browser))
        assertEquals(listOf(browser), InstalledApps.candidates("browser", phone.items))
        val service = AppDiscovery(phone, { error("must not launch") })
        phone.version = InstalledApps.Version("bad\nmetadata", 24)
        assertTrue(service.reply(command("app details Test Browser")).contains("Installed version: unavailable"))
        phone.version = InstalledApps.Version("bad", -1)
        assertTrue(service.reply(command("app details Test Browser")).contains("could not be verified"))
        assertFalse(InstalledApps.validQuery("a".repeat(101)))
    }

    @Test fun malformedControlsAreReservedBeforeAliasesSearchOrProviderRouting() {
        for (text in listOf("open app choice", "open app choice 0", "open app choice 6", "open app choice one then reboot",
            "app details choice six", "find installed app", "app details", "find installed app Maps then search docs",
            "show app choices then open browser", "find installed app " + "a".repeat(101))) {
            assertEquals(text, AppDiscovery.Kind.INVALID, command(text).kind)
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
        }
        for (text in listOf("what apps do you like", "open Google Maps", "yes", "first one", "help me find installed apps"))
            assertNull(text, AppDiscovery.parse(text))
        assertEquals(AppDiscovery.Kind.FIND, command("Please find installed app Maps.").kind)
        assertEquals(AppDiscovery.Kind.DETAILS_CHOICE, command("app details choice two").kind)
    }
}
