package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A reviewed recipient is authorization only for one SDK request, never proof of a connected call. */
class ContactCallWorkflow(private val file: PersonalStateFile, private val platform: Platform,
                          private val now: () -> Long = System::currentTimeMillis) {
    data class Recipient(val row: String, val name: String, val number: String)
    data class Lookup(val recipients: List<Recipient>, val permitted: Boolean = true)
    interface Platform {
        fun lookup(query: String): Lookup
        fun current(recipient: Recipient): Boolean
        fun canCall(): Boolean
        fun request(recipient: Recipient, dialOnly: Boolean): Boolean
    }
    data class Plan(val id: String, val createdAt: Long, val query: String, val recipients: List<Recipient>, val selected: Int?)
    enum class Status { DISPATCHING, REQUESTED, REJECTED, UNKNOWN, STALE }
    data class Attempt(val id: String, val recipient: Recipient, val status: Status, val dialOnly: Boolean)
    private data class Saved(val plan: Plan? = null, val last: Attempt? = null)

    fun prepare(query: String): String = file.access {
        require(query.length in 1..160 && query.none(Char::isISOControl))
        val saved = load()
        save(saved.copy(plan = null)) // A new objective invalidates an older recipient approval even if lookup fails.
        val lookup = try { platform.lookup(query) } catch (_: Exception) {
            return@access "Contacts could not be read. Check Contacts permission, or prepare a call to a complete phone number. No call was requested."
        }
        if (!lookup.permitted) return@access "Allow Contacts access in Jarvis Permissions, or prepare a call to a complete phone number. No call was requested."
        val choices = matches(query, lookup.recipients)
        if (choices.isEmpty()) return@access "No matching callable contact was found. Try the full contact name or prepare a call to a complete phone number."
        if (choices.size > 8) return@access "More than eight phone choices matched. Use the full contact name to narrow the lookup; no recipient was selected."
        val plan = Plan(UUID.randomUUID().toString(), now(), query, choices, if (choices.size == 1) 0 else null)
        save(saved.copy(plan = plan))
        render(plan)
    }

    fun pending(): Plan? = file.access { load().plan?.takeIf(::fresh) }
    fun review(): String = pending()?.let(::render) ?: "No current contact-call review. Say 'prepare a call to' followed by the full contact name or number."
    fun select(id: String?, number: Int): String = file.access {
        val saved = load(); val plan = saved.plan?.takeIf(::fresh)
            ?: return@access "The contact review expired. Prepare a new call; nothing was requested."
        if (id != plan.id) return@access "Review the current contact choices before selecting a recipient. No call was requested."
        if (number !in 1..plan.recipients.size) return@access "Choose a listed contact number. No call was requested."
        val changed = plan.copy(id = UUID.randomUUID().toString(), selected = number - 1)
        save(saved.copy(plan = changed))
        render(changed)
    }

    fun approve(expected: Plan): String = file.access {
        val saved = load(); val plan = saved.plan
        if (plan == null || plan != expected || !fresh(plan) || plan.selected == null)
            return@access "The exact recipient review is no longer current. Review or prepare the call again; nothing was requested."
        val recipient = plan.recipients[plan.selected]
        if (!runCatching { platform.current(recipient) }.getOrDefault(false)) {
            save(saved.copy(plan = null))
            return@access "The contact changed or Contacts access is unavailable. Prepare a fresh call to verify its recipient. No call was requested."
        }
        val dialOnly = !runCatching { platform.canCall() }.getOrDefault(false)
        val attempt = Attempt(plan.id, recipient, Status.DISPATCHING, dialOnly)
        save(Saved(last = attempt)) // Consume the approval before the consequential request.
        val status = try { if (platform.request(recipient, dialOnly)) Status.REQUESTED else Status.REJECTED }
            catch (_: Exception) { Status.UNKNOWN }
        save(Saved(last = attempt.copy(status = status)))
        describe(attempt.copy(status = status))
    }
    fun cancel(): String = file.access {
        val saved = load(); save(saved.copy(plan = null))
        "Discarded the contact-call review. Any previously requested call is separate; check the Phone app to end an existing call."
    }
    fun discardReview() { file.access { val saved = load(); if (saved.plan != null) save(saved.copy(plan = null)) } }
    fun status(): String = file.access {
        val saved = load()
        (saved.plan?.takeIf(::fresh)?.let { render(it) + "\n" } ?: "") +
            (saved.last?.let(::describe) ?: "No contact-call request is recorded.")
    }
    private fun fresh(plan: Plan) = now() >= plan.createdAt && now() - plan.createdAt < 120_000
    fun render(plan: Plan): String = if (plan.selected == null)
        "Contact choices:\n" + plan.recipients.mapIndexed { i, r -> "${i + 1}. ${r.name}: ${r.number}" }.joinToString("\n") +
            "\nSay 'choose contact' and its listed number. No call was requested. This review expires in two minutes."
    else plan.recipients[plan.selected].let {
        "Review call to ${it.name}: ${it.number}. Say 'approve contact call' to request this call, or 'cancel contact call'. Without Phone permission, approval opens the dialer for you to place it. Nothing has been requested. This review expires in two minutes."
    }
    private fun describe(attempt: Attempt): String = when (attempt.status) {
        Status.REQUESTED -> if (attempt.dialOnly) "Requested the dialer for ${attempt.recipient.name}. Place the call there; no call connection is verified."
            else "Requested a call to ${attempt.recipient.name}. Check the Phone app; connection or completion is not verified."
        Status.REJECTED -> "Android rejected the ${if (attempt.dialOnly) "dialer" else "call"} request. Check the Phone app and permissions, then prepare a fresh review if needed."
        Status.DISPATCHING, Status.UNKNOWN -> "The last call-request outcome is uncertain. Check the Phone app before preparing another call; Jarvis will not retry automatically."
        Status.STALE -> "The recipient review changed or could not be revalidated. No call was requested from that review."
    }
    private fun PersonalStateFile.Document.load(): Saved {
        val text = read() ?: return Saved()
        val root = JSONObject(text); require(root.getInt("version") == 1)
        fun recipient(value: JSONObject) = Recipient(value.getString("row"), value.getString("name"), value.getString("number")).also {
            require(valid(it))
        }
        val plan = root.optJSONObject("plan")?.let {
            val items = it.getJSONArray("recipients")
            require(items.length() in 1..8)
            Plan(it.getString("id"), it.getLong("createdAt"), it.getString("query"),
                (0 until items.length()).map { index -> recipient(items.getJSONObject(index)) },
                if (it.isNull("selected")) null else it.getInt("selected")).also { p ->
                require(id(p.id) && p.createdAt > 0 && p.query.length in 1..160 && p.query.none(Char::isISOControl))
                require(p.selected == null || p.selected in p.recipients.indices)
                require(p.recipients.distinct().size == p.recipients.size)
            }
        }
        val last = root.optJSONObject("last")?.let {
            Attempt(it.getString("id"), recipient(it.getJSONObject("recipient")), Status.valueOf(it.getString("status")), it.getBoolean("dialOnly"))
                .also { a -> require(id(a.id)) }
        }
        val saved = Saved(plan, last)
        return if (last?.status == Status.DISPATCHING) saved.copy(last = last.copy(status = Status.UNKNOWN)).also { save(it) } else saved
    }
    private fun PersonalStateFile.Document.save(saved: Saved) {
        fun recipient(value: Recipient) = JSONObject().put("row", value.row).put("name", value.name).put("number", value.number)
        val root = JSONObject().put("version", 1)
        saved.plan?.let { root.put("plan", JSONObject().put("id", it.id).put("createdAt", it.createdAt).put("query", it.query)
            .put("recipients", JSONArray().also { array -> it.recipients.forEach { r -> array.put(recipient(r)) } })
            .put("selected", it.selected ?: JSONObject.NULL)) }
        saved.last?.let { root.put("last", JSONObject().put("id", it.id).put("recipient", recipient(it.recipient)).put("status", it.status.name).put("dialOnly", it.dialOnly)) }
        check(write(root.toString())) { "Contact-call checkpoint could not be saved" }
    }
    companion object {
        fun phone(text: String): String? = text.trim().takeIf {
            it.length <= 48 && Regex("\\+?[0-9][0-9 ().-]*").matches(it) && it.count(Char::isDigit) in 3..20
        }
        private fun id(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun valid(value: Recipient) = (value.row == "number" || Regex("[0-9]{1,19}").matches(value.row)) &&
            value.name.length in 1..128 && value.name.none(Char::isISOControl) && phone(value.number) != null
        fun matches(query: String, recipients: List<Recipient>): List<Recipient> {
            val usable = recipients.filter(::valid)
            phone(query)?.let { return listOf(Recipient("number", "Phone number", it)) }
            val normalized = ContactNameMatcher.normalize(query)
            if (normalized.isBlank()) return emptyList()
            val exact = usable.filter { ContactNameMatcher.normalize(it.name) == normalized }
            return (exact.ifEmpty { usable.filter { " ${ContactNameMatcher.normalize(it.name)} ".contains(" $normalized ") } })
                .distinctBy { ContactNameMatcher.normalize(it.name) + ":" + (if (it.number.startsWith("+")) "+" else "") + it.number.filter(Char::isDigit) }
        }
    }
}
