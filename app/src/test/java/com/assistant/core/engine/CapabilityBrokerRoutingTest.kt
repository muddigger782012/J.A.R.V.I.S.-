package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import org.junit.Assert.*
import org.junit.Test

class CapabilityBrokerRoutingTest {
    private val available = CapabilityState(
        standard = true,
        specialAccess = true,
        shizuku = true,
        dhizuku = true,
        deviceOwner = false,
        oemPrivileged = false,
        platformSigned = false
    )
    private val standardOnly = available.copy(specialAccess = false, shizuku = false, dhizuku = false)

    @Test fun standardActionsCanFallBackBeforeAnUnavailableOrUnsupportedPreferredRouteIsAttempted() {
        val actions = listOf(
            ActionRegistry.CREATE_PROJECT, ActionRegistry.WRITE_FILE, ActionRegistry.SHOW_STATUS,
            ActionRegistry.READ_SCREEN, ActionRegistry.ACCESSIBILITY_BACK,
            ActionRegistry.ACCESSIBILITY_HOME, ActionRegistry.ACCESSIBILITY_RECENTS
        )
        for (capability in listOf(ActionRegistry.CAP_SHIZUKU, ActionRegistry.CAP_DHIZUKU, ActionRegistry.CAP_SPECIAL_ACCESS)) {
            for (state in listOf(available, standardOnly)) {
                for (action in actions) {
                    val request = request(action, capability)
                    val harness = Harness()

                    val result = harness.broker.execute(request, state)

                    assertTrue("$action via $capability", result.success)
                    assertEquals(listOf(CapabilityRouting.Route.STANDARD), harness.calls.map { it.route })
                    assertSame(request, harness.calls.single().request)
                    assertSame(state, harness.calls.single().state)
                }
            }
        }
    }

    @Test fun fallbackPreservesTheExactRequestAndAdapterEvidence() {
        val request = request(ActionRegistry.WRITE_FILE, ActionRegistry.CAP_DHIZUKU).copy(
            parameters = mapOf("path" to "project/file.txt", "content" to "original", "confirmed" to true),
            riskLevel = 2
        )
        val evidence = ActionResult(request.id, true, "STANDARD", "File written", "project/file.txt")
        val harness = Harness { route, actual, state ->
            assertEquals(CapabilityRouting.Route.STANDARD, route)
            assertSame(request, actual)
            assertSame(standardOnly, state)
            evidence
        }

        assertSame(evidence, harness.broker.execute(request, standardOnly))
        assertEquals(1, harness.calls.size)
    }

    @Test fun explicitFallbackOptOutPreventsAlternativeExecutionEvenIfStandardCanPerformTheAction() {
        for (capability in listOf(ActionRegistry.CAP_SHIZUKU, ActionRegistry.CAP_DHIZUKU, ActionRegistry.CAP_SPECIAL_ACCESS)) {
            for (state in listOf(available, standardOnly)) {
                val harness = Harness()
                val result = harness.broker.execute(
                    request(ActionRegistry.SHOW_STATUS, capability).copy(allowFallback = false), state
                )

                assertUnavailable(result, harness)
                assertTrue(result.message.contains("Fallback is disabled"))
                assertTrue(result.message.contains("STANDARD"))
            }
        }
    }

    @Test fun declaredStandardUnavailabilityCannotBeBypassedByFallback() {
        for (capability in listOf(ActionRegistry.CAP_STANDARD, ActionRegistry.CAP_SHIZUKU, ActionRegistry.CAP_SPECIAL_ACCESS)) {
            val harness = Harness()
            val result = harness.broker.execute(
                request(ActionRegistry.SHOW_STATUS, capability), available.copy(standard = false)
            )

            assertUnavailable(result, harness)
            assertTrue(result.message.contains("Standard capability unavailable"))
            assertTrue(result.message.contains("Refresh device capabilities"))
        }
    }

    @Test fun confirmedShellWithoutShizukuNeverFallsThroughToStandardOrOtherPrivilegeFlags() {
        val harness = Harness()
        val state = standardOnly.copy(deviceOwner = true, oemPrivileged = true, platformSigned = true)
        val result = harness.broker.execute(ActionRegistry().runShellRequest("id", confirmed = true), state)

        assertUnavailable(result, harness)
        assertTrue(result.message.contains("Shizuku capability unavailable"))
        assertTrue(result.message.contains("start its service"))
        assertTrue(result.message.contains("grant this app permission"))
        assertTrue(result.message.contains("No supported alternative route"))
    }

    @Test fun unsupportedShellAndRebootRoutesDoNotGainAnImplicitPrivilegedFallback() {
        val requests = listOf(
            ActionRegistry().runShellRequest("id", confirmed = true).copy(requiredCapability = ActionRegistry.CAP_DHIZUKU),
            ActionRegistry().rebootDeviceRequest(confirmed = true).copy(requiredCapability = ActionRegistry.CAP_SHIZUKU)
        )
        for (request in requests) {
            val harness = Harness()
            val result = harness.broker.execute(request, available)

            assertUnavailable(result, harness)
            assertTrue(result.message.contains("does not support"))
            assertTrue(result.message.contains("requires"))
        }
    }

    @Test fun supportedPrimaryRoutesRemainUsableWithFallbackDisabledAndStandardUnavailable() {
        val requests = listOf(
            ActionRegistry().runShellRequest("id", confirmed = true) to CapabilityRouting.Route.SHIZUKU,
            ActionRegistry().rebootDeviceRequest(confirmed = true) to CapabilityRouting.Route.DHIZUKU
        )
        for ((request, expectedRoute) in requests) {
            val harness = Harness()
            val result = harness.broker.execute(request.copy(allowFallback = false), available.copy(standard = false))

            assertTrue(result.success)
            assertEquals(listOf(expectedRoute), harness.calls.map { it.route })
        }
    }

    @Test fun deviceOwnerStillSelectsTheExistingDhizukuAdapterForConfirmedReboot() {
        val harness = Harness()
        val state = standardOnly.copy(deviceOwner = true)

        val result = harness.broker.execute(ActionRegistry().rebootDeviceRequest(confirmed = true), state)

        assertTrue(result.success)
        assertEquals(listOf(CapabilityRouting.Route.DHIZUKU), harness.calls.map { it.route })
        assertSame(state, harness.calls.single().state)
    }

    @Test fun directExecutorCallsStillRequireExistingConfirmationBeforeAnyRouteOrFallback() {
        val requests = listOf(
            ActionRegistry().runShellRequest("id", confirmed = false),
            ActionRegistry().rebootDeviceRequest(confirmed = false),
            request(ActionRegistry.WRITE_FILE, ActionRegistry.CAP_SPECIAL_ACCESS).copy(riskLevel = 2)
        )
        for (request in requests) {
            val harness = Harness()
            val result = ActionExecutor(harness.broker).execute(request, available)

            assertFalse(result.success)
            assertEquals("POLICY", result.adapterUsed)
            assertEquals(request.id, result.id)
            assertTrue(result.message.contains("explicit confirmation required"))
            assertTrue(harness.calls.isEmpty())
        }
    }

    @Test fun shizukuAndOemFlagsCannotReplaceTheRebootPolicyRequirement() {
        val harness = Harness()
        val state = standardOnly.copy(shizuku = true, oemPrivileged = true, platformSigned = true)

        val result = harness.broker.execute(ActionRegistry().rebootDeviceRequest(confirmed = true), state)

        assertFalse(result.success)
        assertEquals("POLICY", result.adapterUsed)
        assertTrue(result.message.contains("Dhizuku or device owner required"))
        assertTrue(harness.calls.isEmpty())
    }

    @Test fun failedConsequentialPrimaryResultsAreReturnedWithoutRetryOrSuccessPromotion() {
        val requests = listOf(
            ActionRegistry().runShellRequest("write-something", confirmed = true),
            ActionRegistry().rebootDeviceRequest(confirmed = true)
        )
        for (request in requests) {
            val evidence = ActionResult(request.id, false, request.requiredCapability,
                "Connection lost; outcome unknown", "The action might already have applied")
            val harness = Harness { _, _, _ -> evidence }

            assertSame(evidence, harness.broker.execute(request, available))
            assertEquals(1, harness.calls.size)
            assertEquals(request.requiredCapability, harness.calls.single().route.name)
        }
    }

    @Test fun failedFallbackIsAlsoFinalAndKeepsTheAccessibilityPermissionRemedy() {
        val request = request(ActionRegistry.ACCESSIBILITY_HOME, ActionRegistry.CAP_SHIZUKU)
        val evidence = ActionResult(request.id, false, "ACCESSIBILITY",
            "Accessibility is not enabled. Enable it in Android Accessibility settings.")
        val harness = Harness { _, _, _ -> evidence }

        assertSame(evidence, harness.broker.execute(request, available))
        assertEquals(listOf(CapabilityRouting.Route.STANDARD), harness.calls.map { it.route })
    }

    @Test fun anAdapterExceptionAfterAnAttemptNeverTriggersAnotherRoute() {
        val failure = IllegalStateException("Outcome unknown")
        val requests = listOf(
            ActionRegistry().runShellRequest("write-something", confirmed = true),
            request(ActionRegistry.WRITE_FILE, ActionRegistry.CAP_DHIZUKU)
        )
        for (request in requests) {
            val harness = Harness { _, _, _ -> throw failure }
            val caught = assertThrows(IllegalStateException::class.java) {
                harness.broker.execute(request, available)
            }

            assertSame(failure, caught)
            assertEquals(1, harness.calls.size)
        }
    }

    @Test fun unknownCapabilitiesAndUnimplementedActionsCannotBecomeGuessedRoutes() {
        val requests = listOf(
            request(ActionRegistry.SHOW_STATUS, "ROOT"),
            request("UNIMPLEMENTED_ACTION", ActionRegistry.CAP_SHIZUKU),
            request("UNIMPLEMENTED_ACTION", ActionRegistry.CAP_SPECIAL_ACCESS)
        )
        for (request in requests) {
            val harness = Harness()
            val result = harness.broker.execute(request, available)

            assertUnavailable(result, harness)
            assertEquals(request.id, result.id)
            assertTrue(result.message.contains("Choose"))
        }
    }

    @Test fun standardPrimaryAndCaseInsensitiveCapabilityNamesStillWork() {
        val requests = listOf(
            request(ActionRegistry.SHOW_STATUS, "standard") to CapabilityRouting.Route.STANDARD,
            ActionRegistry().runShellRequest("id", confirmed = true).copy(requiredCapability = "shizuku") to CapabilityRouting.Route.SHIZUKU,
            ActionRegistry().rebootDeviceRequest(confirmed = true).copy(requiredCapability = "dhizuku") to CapabilityRouting.Route.DHIZUKU
        )
        for ((request, expectedRoute) in requests) {
            val harness = Harness()
            assertTrue(harness.broker.execute(request, available).success)
            assertEquals(listOf(expectedRoute), harness.calls.map { it.route })
        }
    }

    private fun request(action: String, capability: String) = ActionRequest(
        id = "routing-test", actionType = action, parameters = emptyMap(),
        requiredCapability = capability, riskLevel = 0
    )

    private fun assertUnavailable(result: ActionResult, harness: Harness) {
        assertFalse(result.success)
        assertEquals("BROKER", result.adapterUsed)
        assertNull(result.output)
        assertTrue(harness.calls.isEmpty())
    }

    private data class Call(val route: CapabilityRouting.Route, val request: ActionRequest, val state: CapabilityState)

    private class Harness(
        execute: (CapabilityRouting.Route, ActionRequest, CapabilityState) -> ActionResult = { route, request, _ ->
            ActionResult(request.id, true, route.name, "Adapter result", "Adapter evidence")
        }
    ) {
        val calls = mutableListOf<Call>()
        val broker = CapabilityBroker { route, request, state ->
            calls += Call(route, request, state)
            execute(route, request, state)
        }
    }
}
