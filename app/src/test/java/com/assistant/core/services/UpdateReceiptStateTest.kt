package com.assistant.core.services

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateReceiptStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val basename = "12345678-abcd-1234-abcd-123456789abc.apk"

    @Test fun bothChannelsRoundTripTheirMetadataSelectedArtifactAndPrivateCacheName() {
        for (channel in UpdateChannel.entries) {
            val original = receipt(channel)
            val raw = UpdateReceiptState.encode(original)
            val restored = UpdateReceiptState.decode(raw, temporary.root, channel)
            assertNotNull(restored)
            assertEquals(original, restored)
            assertFalse("Receipt restoration must not create or claim verification of an APK", restored!!.file.exists())
            assertFalse(raw.contains("Authorization"))
            assertFalse(raw.contains("token"))
        }
    }

    @Test fun oppositeChannelAndPathTraversalNeverRestoreAnInstallableReceipt() {
        val raw = UpdateReceiptState.encode(receipt(UpdateChannel.JARVIS))
        assertNull(UpdateReceiptState.decode(raw, temporary.root, UpdateChannel.BETA))
        for (name in listOf("../jarvis-$basename", "/tmp/jarvis-$basename", "beta-$basename", "jarvis-$basename.part", "jarvis-latest.apk", "jarvis-../../$basename")) {
            val state = JSONObject(raw).put("fileName", name).toString()
            assertNull(name, UpdateReceiptState.decode(state, temporary.root, UpdateChannel.JARVIS))
        }
    }

    @Test fun malformedMetadataAlteredSourceOrMissingArtifactDoesNotRestore() {
        val raw = UpdateReceiptState.encode(receipt(UpdateChannel.JARVIS))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("metadata") }, { it.put("receiptSchemaVersion", 2) },
            { it.put("receiptSchemaVersion", "1") }, { it.put("selectedAbi", "x86_64") },
            { it.getJSONObject("metadata").put("applicationId", UpdateChannel.BETA.applicationId) },
            { it.getJSONObject("metadata").getJSONArray("artifacts").getJSONObject(0).put("url", "https://attacker.example/apk") },
            { it.getJSONObject("metadata").put("versionCode", -1) }
        )
        for (mutate in mutations) {
            val state = JSONObject(raw)
            mutate(state)
            assertNull(UpdateReceiptState.decode(state.toString(), temporary.root, UpdateChannel.JARVIS))
        }
        for (state in listOf("{", "$raw trailing", raw + '\u0000', "[".repeat(100) + "0" + "]".repeat(100), " ".repeat(UpdatePolicy.MAX_METADATA_BYTES + 1))) {
            assertNull(UpdateReceiptState.decode(state, temporary.root, UpdateChannel.JARVIS))
        }
    }

    private fun receipt(channel: UpdateChannel): UpdateDownloadResult.Downloaded {
        val artifact = UpdateArtifact("arm64-v8a", channel.artifactUrl("arm64-v8a"), 100,
            "ab".repeat(32), "cd".repeat(32), 26)
        val metadata = UpdateMetadata(channel, channel.applicationId, 109, "3.4.109", "a".repeat(40), listOf(artifact))
        return UpdateDownloadResult.Downloaded(File(temporary.root, "${channel.id}-$basename"), UpdateCandidate(metadata, artifact))
    }
}
