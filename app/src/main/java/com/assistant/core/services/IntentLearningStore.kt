package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * Persistent on-device intent learning.
 *
 * Stores only normalized utterance -> intent examples and generic entity hints.
 * Personal lessons stay on this installation. Curated generic examples can later
 * be bundled into the APK as seed data without copying private user content.
 */
class IntentLearningStore(context: Context) {
    data class Match(
        val intent: String,
        val confidence: Double,
        val entities: Map<String, String>,
        val source: String
    )

    private data class Example(
        val utterance: String,
        val intent: String,
        val entities: Map<String, String>,
        val successes: Int,
        val failures: Int,
        val source: String
    )

    private val prefs = context.getSharedPreferences("jarvis_intent_learning_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun learn(
        utterance: String,
        intent: String,
        entities: Map<String, String> = emptyMap(),
        source: String = "validated_fallback"
    ) {
        val normalized = normalize(utterance)
        if (normalized.isBlank() || intent.isBlank()) return
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.utterance == normalized && it.intent == intent }
        val previous = all.getOrNull(index)
        val next = Example(
            normalized,
            intent,
            entities.filterValues { it.isNotBlank() },
            (previous?.successes ?: 0) + 1,
            previous?.failures ?: 0,
            source
        )
        if (index >= 0) all[index] = next else all += next
        save(all.takeLast(MAX_EXAMPLES))
    }

    @Synchronized
    fun recordFailure(utterance: String, intent: String) {
        val normalized = normalize(utterance)
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.utterance == normalized && it.intent == intent }
        if (index < 0) return
        val old = all[index]
        all[index] = old.copy(failures = old.failures + 1)
        save(all)
    }

    fun classify(utterance: String): Match? {
        val query = normalize(utterance)
        if (query.isBlank()) return null
        var best: Example? = null
        var bestScore = 0.0
        for (example in load()) {
            val lexical = tokenSimilarity(query, example.utterance)
            val reliability = (example.successes + 1.0) / (example.successes + example.failures + 2.0)
            val score = lexical * (0.75 + 0.25 * reliability)
            if (score > bestScore) {
                bestScore = score
                best = example
            }
        }
        val winner = best ?: return null
        return if (bestScore >= LOCAL_CONFIDENCE_THRESHOLD) {
            Match(winner.intent, bestScore, winner.entities, winner.source)
        } else null
    }

    fun count(): Int = load().size

    private fun normalize(text: String): String = text.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val aa = a.split(' ').filter(String::isNotBlank).toSet()
        val bb = b.split(' ').filter(String::isNotBlank).toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val intersection = aa.intersect(bb).size.toDouble()
        val union = aa.union(bb).size.toDouble()
        val jaccard = intersection / max(1.0, union)
        val prefix = if (a.startsWith(b) || b.startsWith(a)) 0.12 else 0.0
        return (jaccard + prefix).coerceAtMost(1.0)
    }

    private fun load(): List<Example> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val entitiesObject = o.optJSONObject("entities") ?: JSONObject()
                    val entities = buildMap {
                        entitiesObject.keys().forEach { key -> put(key, entitiesObject.optString(key)) }
                    }
                    add(Example(
                        o.getString("utterance"),
                        o.getString("intent"),
                        entities,
                        o.optInt("successes", 1),
                        o.optInt("failures", 0),
                        o.optString("source", "local")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(examples: List<Example>) {
        val array = JSONArray()
        examples.forEach { e ->
            array.put(JSONObject().apply {
                put("utterance", e.utterance)
                put("intent", e.intent)
                put("entities", JSONObject(e.entities))
                put("successes", e.successes)
                put("failures", e.failures)
                put("source", e.source)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "examples"
        private const val MAX_EXAMPLES = 2000
        const val LOCAL_CONFIDENCE_THRESHOLD = 0.78
    }
}
