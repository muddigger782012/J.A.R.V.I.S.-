package com.assistant.core.services

/**
 * Central capability catalog for J.A.R.V.I.S.
 *
 * Every interaction surface (voice, text, background service, future cloud AI,
 * Android assistant role) should resolve through the same orchestrator and
 * capability/policy pipeline rather than implementing its own assistant logic.
 */
object AssistantCapabilityCatalog {
    enum class Domain {
        CONVERSATION,
        TIME_DATE,
        PHONE_CALLS,
        MESSAGING,
        ALARMS_TIMERS,
        CALENDAR_REMINDERS,
        MEDIA,
        NAVIGATION,
        APP_LAUNCH,
        NOTIFICATIONS,
        CONTACTS,
        DEVICE_SETTINGS,
        SMART_HOME,
        SEARCH_KNOWLEDGE,
        SCREEN_CONTEXT,
        LOCK_SCREEN,
        PROJECTS,
        SHIZUKU,
        DHIZUKU,
        SYSTEM_STATUS
    }

    data class Capability(
        val domain: Domain,
        val requiresConfirmation: Boolean = false,
        val mayRequireUnlock: Boolean = false,
        val requiresRuntimePermission: Boolean = false,
        val description: String
    )

    val all: List<Capability> = listOf(
        Capability(Domain.CONVERSATION, description = "Natural multi-turn conversation and follow-up context"),
        Capability(Domain.TIME_DATE, description = "Time, date and day queries"),
        Capability(Domain.PHONE_CALLS, true, true, true, "Place and manage phone calls through Android-supported APIs"),
        Capability(Domain.MESSAGING, true, true, true, "Compose/send messages through supported apps and Android intents"),
        Capability(Domain.ALARMS_TIMERS, false, false, false, "Create alarms and timers"),
        Capability(Domain.CALENDAR_REMINDERS, true, true, true, "Calendar events and reminder workflows"),
        Capability(Domain.MEDIA, description = "Playback, pause, skip and media-session controls"),
        Capability(Domain.NAVIGATION, description = "Launch navigation and destination intents"),
        Capability(Domain.APP_LAUNCH, description = "Open installed apps and supported deep links"),
        Capability(Domain.NOTIFICATIONS, false, true, true, "Read and act on notifications when access is granted"),
        Capability(Domain.CONTACTS, false, true, true, "Resolve contacts for calls, messages and contextual requests"),
        Capability(Domain.DEVICE_SETTINGS, true, true, false, "Adjust supported device settings through standard or privileged adapters"),
        Capability(Domain.SMART_HOME, true, true, false, "Provider/plugin based smart-home actions"),
        Capability(Domain.SEARCH_KNOWLEDGE, description = "Knowledge and web-backed answers through configured providers"),
        Capability(Domain.SCREEN_CONTEXT, false, true, true, "Context from the current screen when explicitly enabled"),
        Capability(Domain.LOCK_SCREEN, true, true, false, "Lock-screen-safe assistant actions and gated sensitive actions"),
        Capability(Domain.PROJECTS, description = "Local project creation and coding workflows"),
        Capability(Domain.SHIZUKU, true, true, false, "Privileged Android operations through Shizuku"),
        Capability(Domain.DHIZUKU, true, true, false, "Device-owner delegated operations through Dhizuku"),
        Capability(Domain.SYSTEM_STATUS, description = "Capability and system status")
    )
}
