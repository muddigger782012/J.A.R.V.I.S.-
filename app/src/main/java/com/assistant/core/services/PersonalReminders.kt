package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable authorization and delivery journal. Platform calls are short and serialized with disk state. */
class PersonalReminders(private val file: PersonalStateFile, private val platform: Platform,
                        private val observe: (String, WorkflowDiagnostics.Code) -> Unit = { _, _ -> },
                        private val now: () -> Long = System::currentTimeMillis) {
    interface Platform {
        fun notificationsAvailable(): Boolean
        /** Replacing an alarm with the same ID must be idempotent. Delivery may be delayed. */
        fun schedule(id: String, dueAt: Long): Boolean
        fun cancel(id: String): Boolean
        fun post(record: Record): Delivery
    }
    enum class Delivery { POSTED, BLOCKED, UNKNOWN }
    enum class Status { SCHEDULING, SCHEDULED, SCHEDULE_FAILED, DELIVERING, POSTED, USER_CONFIRMED, BLOCKED, DELIVERY_UNKNOWN, CANCELLED, MISSED }
    data class Plan(val id: String, val title: String, val created: Long, val dueAt: Long,
                    val research: ResearchBriefs.Snapshot? = null)
    data class Record(val number: Int, val plan: Plan, val status: Status)
    /** A new review token is distinct from the stable alarm ID of a replaced reminder. */
    data class Review(val plan: Plan, val replacing: Record? = null)
    data class Result(val message: String, val record: Record? = null)
    private data class State(val next: Int = 1, val pending: Plan? = null, val records: List<Record> = emptyList(), val replacing: Record? = null)

    fun prepare(title: String, seconds: Int, research: ResearchBriefs.Snapshot? = null): Plan = file.access {
        require(validTitle(title) && seconds in 1..86400)
        val time = now().also { require(it >= 0) }
        val plan = Plan(UUID.randomUUID().toString(), title.trim(), time, Math.addExact(time, seconds.toLong() * 1000), research)
        check(write(encode(decode(read()).copy(pending = plan, replacing = null)))) { "Reminder review could not be saved." }
        plan
    }
    fun pending(): Plan? = review()?.plan
    fun review(): Review? = file.access { decode(read()).activeReview() }
    fun prepareChange(number: Int, seconds: Int): Review? = file.access {
        require(number > 0 && seconds in 1..86400)
        val state = recoverInterrupted(this, decode(read()))
        val before = state.records.firstOrNull { it.number == number && changeable(it) }
        if (before == null) {
            check(write(encode(state.copy(pending = null, replacing = null))))
            return@access null
        }
        val time = now().also { require(it >= 0) }
        val plan = Plan(UUID.randomUUID().toString(), before.plan.title, time, Math.addExact(time, seconds.toLong() * 1000), before.plan.research)
        check(write(encode(state.copy(pending = plan, replacing = before)))) { "Reminder change review could not be saved." }
        Review(plan, before)
    }
    fun revise(expected: Review, seconds: Int?): Review? = file.access {
        val state = decode(read())
        if (state.activeReview() != expected) return@access null
        if (seconds == null || seconds !in 1..86400) {
            check(write(encode(state.copy(pending = null, replacing = null))))
            return@access null
        }
        val time = now().also { require(it >= 0) }
        val plan = expected.plan.copy(id = UUID.randomUUID().toString(), created = time,
            dueAt = Math.addExact(time, seconds.toLong() * 1000))
        check(write(encode(state.copy(pending = plan)))) { "Reminder correction could not be saved." }
        Review(plan, expected.replacing)
    }
    fun discardPending(): Boolean = file.access {
        val state = decode(read())
        state.pending == null || write(encode(state.copy(pending = null, replacing = null)))
    }
    fun records(): List<Record> = file.access { recoverInterrupted(this, decode(read())).records }
    fun research(number: Int): String = file.access {
        val record = decode(read()).records.firstOrNull { it.number == number }
        record?.plan?.research?.report ?: if (record == null) "No reminder with that number is saved. Say 'review reminders'."
            else "Reminder $number has no saved research attached. No lookup was started."
    }

    fun approve(expected: Plan, replacing: Record? = null): Result = file.access {
        val state = recoverInterrupted(this, decode(read()))
        if (state.activeReview() != Review(expected, replacing)) return@access Result("The reminder review changed or expired. Request and review it again; no alarm was registered by this approval.")
        if (!platform.notificationsAvailable()) {
            diagnostic(expected.id, WorkflowDiagnostics.Code.NOTIFICATIONS_BLOCKED)
            return@access Result("Allow Jarvis notifications in notification settings, then review and approve the reminder again. " +
                (if (replacing == null) "No alarm was registered; " else "This approval did not change the existing approved alarm; ") +
                "the pending review still expires after two minutes.")
        }
        if (replacing == null && (state.records.size >= MAX_RECORDS || state.next == Int.MAX_VALUE)) return@access Result("Reminder history is full. Review reminders, then clear completed reminder history before approving a new one.")
        val record = if (replacing == null) Record(state.next, expected, Status.SCHEDULING)
            else replacing.copy(plan = expected.copy(id = replacing.plan.id), status = Status.SCHEDULING)
        val authorized = if (replacing == null) state.copy(next = state.next + 1, pending = null, replacing = null, records = state.records + record)
            else state.with(record).copy(pending = null, replacing = null)
        if (!write(encode(authorized))) return@access Result("Could not confirm reminder storage. Review reminders before trying again.")
        register(this, authorized, record)
    }

    fun cancel(number: Int): Result = file.access {
        val state = recoverInterrupted(this, decode(read()))
        val record = state.records.firstOrNull { it.number == number } ?: return@access Result("No reminder with that number is saved. Say 'review reminders'.")
        if (record.status in setOf(Status.POSTED, Status.USER_CONFIRMED)) return@access Result("That reminder notification was already posted or confirmed by you. Cancellation cannot undo what was shown.", record)
        if (record.status == Status.DELIVERY_UNKNOWN) return@access Result("That reminder's delivery is uncertain. It will not be repeated automatically; check notifications before creating another.", record)
        val cancelled = record.copy(status = Status.CANCELLED)
        if (!write(encode(state.with(cancelled)))) return@access Result("Could not confirm cancellation. Review reminders before relying on it.")
        val removed = runCatching { platform.cancel(record.plan.id) }.getOrDefault(false)
        Result(if (removed) "Cancelled reminder $number. Already shown notifications or completed tasks are not undone."
            else "Saved cancellation for reminder $number. Jarvis will suppress later delivery even though Android alarm removal could not be confirmed.", cancelled)
    }

    fun retry(number: Int): Result = file.access {
        val state = recoverInterrupted(this, decode(read()))
        val record = state.records.firstOrNull { it.number == number } ?: return@access Result("No reminder with that number is saved. Say 'review reminders'.")
        if (record.status !in setOf(Status.BLOCKED, Status.SCHEDULE_FAILED)) return@access Result("Only a blocked notification or unconfirmed alarm registration can be retried. Posted or uncertain notifications are not repeated.", record)
        if (now() - record.plan.dueAt > DAY) return@access Result("That reminder is more than a day overdue. Request a new reminder with a current time.", record)
        if (!platform.notificationsAvailable()) {
            diagnostic(record.plan.id, WorkflowDiagnostics.Code.NOTIFICATIONS_BLOCKED)
            return@access Result("Allow Jarvis notifications in notification settings, then retry reminder $number.", record)
        }
        val starting = record.copy(status = Status.SCHEDULING)
        val authorized = state.with(starting)
        if (!write(encode(authorized))) return@access Result("Could not confirm reminder storage. Review reminders before retrying.")
        register(this, authorized, starting)
    }

    fun confirmShown(number: Int): Result = file.access {
        val state = recoverInterrupted(this, decode(read()))
        val record = state.records.firstOrNull { it.number == number } ?: return@access Result("No reminder with that number is saved. Say 'review reminders'.")
        if (record.status != Status.DELIVERY_UNKNOWN) return@access Result("Only an uncertain delivery needs this result report. No alarm or outcome was changed.", record)
        val confirmed = record.copy(status = Status.USER_CONFIRMED)
        if (!write(encode(state.with(confirmed)))) return@access Result("Could not save your reminder result report. No alarm was run.")
        Result("Recorded that you saw reminder $number. This is your report, not device verification or proof the task was completed.", confirmed)
    }

    /** Safe after boot, package update or app start: the same alarm identity replaces itself. */
    fun restore() = file.access {
        var state = recoverInterrupted(this, decode(read()))
        for (record in state.records.toList()) {
            if (record.status !in setOf(Status.SCHEDULING, Status.SCHEDULED)) continue
            if (now() - record.plan.dueAt > DAY) {
                val missed = record.copy(status = Status.MISSED)
                if (!write(encode(state.with(missed)))) break
                state = state.with(missed)
                diagnostic(record.plan.id, WorkflowDiagnostics.Code.MISSED)
            } else {
                register(this, state, record)
                state = decode(read())
            }
        }
    }

    /** A receiver carries only an ID. Titles and approval always come from private storage. */
    fun deliver(id: String): Result = file.access {
        val state = recoverInterrupted(this, decode(read()))
        val record = state.records.firstOrNull { it.plan.id == id } ?: return@access Result("No approved reminder matches this alarm.")
        if (record.status !in setOf(Status.SCHEDULING, Status.SCHEDULED)) return@access Result("This alarm is no longer eligible for delivery.", record)
        if (now() < record.plan.dueAt) return@access register(this, state, record)
        if (now() - record.plan.dueAt > DAY) {
            val missed = record.copy(status = Status.MISSED)
            check(write(encode(state.with(missed))))
            diagnostic(record.plan.id, WorkflowDiagnostics.Code.MISSED)
            return@access Result("Reminder was more than a day overdue and was not posted.", missed)
        }
        val delivering = record.copy(status = Status.DELIVERING)
        if (!write(encode(state.with(delivering)))) return@access Result("Could not confirm the delivery checkpoint; no notification was requested.")
        val outcome = runCatching { platform.post(record) }.getOrDefault(Delivery.UNKNOWN)
        val finished = record.copy(status = when (outcome) {
            Delivery.POSTED -> Status.POSTED
            Delivery.BLOCKED -> Status.BLOCKED
            Delivery.UNKNOWN -> Status.DELIVERY_UNKNOWN
        })
        if (!write(encode(state.with(finished)))) {
            diagnostic(record.plan.id, WorkflowDiagnostics.Code.DELIVERY_UNKNOWN)
            return@access Result("Notification delivery could not be recorded. Check notifications; no automatic repeat is allowed.", delivering.copy(status = Status.DELIVERY_UNKNOWN))
        }
        diagnostic(record.plan.id, when (outcome) {
            Delivery.POSTED -> WorkflowDiagnostics.Code.NOTIFICATION_POSTED
            Delivery.BLOCKED -> WorkflowDiagnostics.Code.NOTIFICATIONS_BLOCKED
            Delivery.UNKNOWN -> WorkflowDiagnostics.Code.DELIVERY_UNKNOWN
        })
        Result(when (outcome) {
            Delivery.POSTED -> "Reminder notification was posted; reading it or completing the task is not verified."
            Delivery.BLOCKED -> "Reminder notifications are blocked. Enable them and explicitly retry this reminder."
            Delivery.UNKNOWN -> "Notification delivery is uncertain. Check notifications before creating another reminder."
        }, finished)
    }

    fun clearCompleted(): Int = file.access {
        val state = recoverInterrupted(this, decode(read()))
        val kept = state.records.filterNot { it.status in setOf(Status.POSTED, Status.USER_CONFIRMED, Status.CANCELLED, Status.MISSED) }
        val removedChangeTarget = state.replacing?.let { before -> kept.none { it.number == before.number } } == true
        check(write(encode(state.copy(records = kept, pending = if (removedChangeTarget) null else state.pending,
            replacing = if (removedChangeTarget) null else state.replacing))))
        state.records.size - kept.size
    }

    private fun register(document: PersonalStateFile.Document, state: State, record: Record): Result {
        val accepted = runCatching { platform.schedule(record.plan.id, maxOf(record.plan.dueAt, Math.addExact(now(), 1000))) }.getOrDefault(false)
        val updated = record.copy(status = if (accepted) Status.SCHEDULED else Status.SCHEDULE_FAILED)
        if (!document.write(encode(state.with(updated)))) return Result("Android alarm registration returned, but its checkpoint could not be confirmed. Review reminders before trying again.", record)
        diagnostic(record.plan.id, if (accepted) WorkflowDiagnostics.Code.ALARM_REGISTERED else WorkflowDiagnostics.Code.ALARM_UNCONFIRMED)
        return Result(if (accepted) "Registered reminder ${record.number} with Android. Delivery is approximate and may be delayed by power saving; notification visibility and task completion are not yet verified."
            else "Alarm registration for reminder ${record.number} could not be confirmed. Say 'retry reminder ${record.number}', or use your calendar reminder provider.", updated)
    }
    private fun recoverInterrupted(document: PersonalStateFile.Document, state: State): State {
        if (state.records.none { it.status == Status.DELIVERING }) return state
        val recovered = state.copy(records = state.records.map { if (it.status == Status.DELIVERING) it.copy(status = Status.DELIVERY_UNKNOWN) else it })
        check(document.write(encode(recovered))) { "Could not protect interrupted reminder delivery." }
        state.records.filter { it.status == Status.DELIVERING }.forEach { diagnostic(it.plan.id, WorkflowDiagnostics.Code.DELIVERY_UNKNOWN) }
        return recovered
    }
    private fun diagnostic(id: String, code: WorkflowDiagnostics.Code) { runCatching { observe(id, code) } }
    private fun State.with(record: Record) = copy(records = records.map { if (it.plan.id == record.plan.id) record else it })
    private fun fresh(plan: Plan) = now() - plan.created in 0..120_000 && now() < plan.dueAt
    private fun changeable(record: Record) = record.status in setOf(Status.SCHEDULED, Status.SCHEDULE_FAILED)
    private fun State.activeReview(): Review? = pending?.takeIf { fresh(it) }?.let { plan ->
        if (replacing == null || (changeable(replacing) && records.any { it == replacing })) Review(plan, replacing) else null
    }

    private fun decode(raw: String?): State {
        if (raw == null) return State()
        val json = JSONObject(raw)
        val version = json.getInt("version"); require(version in 1..2)
        fun plan(item: JSONObject) = Plan(item.getString("id"), item.getString("title"), item.getLong("created"), item.getLong("dueAt"),
            if (item.has("researchReport")) ResearchBriefs.Snapshot(item.getString("researchReport")) else null).also {
            require(validId(it.id) && validTitle(it.title) && it.created >= 0 && it.dueAt > it.created && it.dueAt - it.created in 1..DAY)
        }
        val rows = json.getJSONArray("records")
        require(rows.length() <= MAX_RECORDS)
        fun record(item: JSONObject) = Record(item.getInt("number"), plan(item.getJSONObject("plan")), Status.valueOf(item.getString("status")))
        val records = (0 until rows.length()).map { index -> record(rows.getJSONObject(index)) }
        val next = json.getInt("next")
        require(next > 0 && records.all { it.number in 1 until next })
        require(records.distinctBy { it.number }.size == records.size && records.distinctBy { it.plan.id }.size == records.size)
        val pending = if (json.has("pending")) plan(json.getJSONObject("pending")) else null
        require(pending == null || records.none { it.plan.id == pending.id })
        require(version == 2 || !json.has("replacing"))
        val replacing = if (json.has("replacing")) record(json.getJSONObject("replacing")) else null
        require(replacing == null || (pending != null && changeable(replacing) && replacing.number in 1 until next &&
            replacing.plan.id != pending.id && replacing.plan.title == pending.title && replacing.plan.research == pending.research))
        return State(next, pending, records, replacing)
    }
    private fun encode(state: State): String {
        fun plan(value: Plan) = JSONObject().put("id", value.id).put("title", value.title).put("created", value.created).put("dueAt", value.dueAt).also {
            value.research?.let { research -> it.put("researchReport", research.report) }
        }
        fun record(value: Record) = JSONObject().put("number", value.number).put("plan", plan(value.plan)).put("status", value.status.name)
        val rows = JSONArray().also { output -> state.records.forEach { output.put(record(it)) } }
        return JSONObject().put("version", 2).put("next", state.next).put("records", rows).also {
            state.pending?.let { pending -> it.put("pending", plan(pending)) }
            state.replacing?.let { before -> it.put("replacing", record(before)) }
        }.toString()
    }
    companion object {
        const val MAX_RECORDS = 100
        private const val DAY = 86_400_000L
        fun validTitle(title: String) = title.trim().length in 1..160 && title.none { it.isISOControl() }
        fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
