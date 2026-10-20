package com.assistant.core.services

import org.json.JSONObject
import java.util.Date
import java.text.DateFormat
import java.util.Locale
import java.util.UUID

/** One saved source-linked brief. Retrieval is bounded, explicit, and never executes source text. */
class ResearchBriefs(private val file: PersonalStateFile, private val now: () -> Long = System::currentTimeMillis) {
    data class Ticket(val id: String, val query: String, val createdAt: Long)
    private enum class State { IDLE, PREPARED, RUNNING, COMPLETED, FAILED, CANCELLED }
    private data class Saved(val pending: Ticket? = null, val started: Boolean = false, val report: String? = null,
                             val query: String? = null, val state: State = State.IDLE)
    fun start(query: String): Ticket = file.access {
        require(validQuery(query))
        val saved = load()
        val ticket = Ticket(UUID.randomUUID().toString(), query, now())
        save(saved.copy(pending = ticket, started = false, query = query, state = State.PREPARED))
        ticket
    }
    fun retry(): Ticket? = file.access {
        val saved = load(); val query = saved.query ?: return@access null
        val ticket = Ticket(UUID.randomUUID().toString(), query, now())
        save(saved.copy(pending = ticket, started = false, state = State.PREPARED))
        ticket
    }
    fun run(ticket: Ticket, fetch: (String) -> SearchRouter.Response): String {
        val claimed = file.access {
            val saved = load()
            if (saved.pending != ticket || saved.started) false
            else { save(saved.copy(started = true, state = State.RUNNING)); true }
        }
        if (!claimed) return "This research request was already started, cancelled or replaced. No duplicate lookup was started."
        val response = try { fetch(ticket.query) } catch (error: SearchProviderFailure) {
            SearchRouter.Response(null, emptyList(), listOf(error.kind))
        } catch (_: Exception) { SearchRouter.Response(null, emptyList(), listOf(SearchProviderFailure.Kind.UNAVAILABLE)) }
        val items = response.items.filter { it.kind == "web" && it.title.isNotBlank() && it.url.length <= 2048 && ChatSearchResults.safeUrl(it.url) }
            .distinctBy { it.url }.take(5).map {
                it.copy(title = plain(it.title, 200), description = SearchReadableText.excerpt(plain(it.description, 1500)), thumbnail = null)
            }.filter { it.title.isNotBlank() }
        return file.access {
            val saved = load()
            if (saved.pending != ticket) return@access "The research request was cancelled or replaced. Its late results were not saved."
            if (items.isEmpty()) {
                save(saved.copy(pending = null, started = false, state = State.FAILED))
                return@access SearchRouter(emptyList()).failureMessage(response, "web") + " No new brief was saved; an older saved brief is unchanged. Use 'retry research' for an explicit new attempt."
            }
            val at = now()
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
            val answer = "Research brief: ${ticket.query}\nSearch-excerpt summary, retrieved $date. Check the linked originals; full pages and claims were not independently verified.\n" +
                items.mapIndexed { i, item -> "[${i + 1}] " + item.description.ifBlank { "${item.title} — no summary excerpt was returned." } }.joinToString("\n") +
                "\nSaved locally for 'review research brief'."
            val report = ChatSearchResults.encode(ticket.query, items, answer, retrievedAt = at)
            require(report.length <= 16000)
            save(saved.copy(pending = null, started = false, report = report, state = State.COMPLETED))
            report
        }
    }
    fun review(): String = file.access { load().report ?: "No research brief is saved. Try 'research solar panels and summarize'. An online lookup is required to create a brief; saved briefs can be reviewed offline." }
    fun status(): String = file.access {
        when (load().state) {
            State.PREPARED -> "The research query is prepared; no lookup is confirmed. Use 'retry research' to make an explicit attempt, or 'cancel research'."
            State.RUNNING -> "A research lookup was started but completion is not saved. It may still be running or have been interrupted. You can cancel it or explicitly retry; no automatic restart occurs."
            State.COMPLETED -> "A source-linked excerpt brief is saved. Use 'review research brief'; no new lookup is needed."
            State.FAILED -> "The last research lookup did not save a new brief. Check connectivity or search settings, then explicitly retry. An older brief, if any, is unchanged."
            State.CANCELLED -> "The research request was cancelled. Late results cannot replace the saved brief; an in-flight provider request may still finish."
            State.IDLE -> "No research request is recorded."
        }
    }
    fun cancel(): String = file.access {
        val saved = load(); save(saved.copy(pending = null, started = false, state = State.CANCELLED))
        "Cancelled research result saving. An in-flight provider request may finish; its late results will not replace the saved brief."
    }
    fun clear(): String = file.access {
        val saved = load()
        if (saved.pending != null) "Cancel the current research request before clearing its saved brief. Nothing was cleared."
        else { save(Saved()); "Cleared the saved research brief and query. Chat history is separate; no lookup was started." }
    }
    private fun PersonalStateFile.Document.load(): Saved {
        val text = read() ?: return Saved()
        val root = JSONObject(text); require(root.getInt("version") == 1)
        val pending = root.optJSONObject("pending")?.let {
            Ticket(it.getString("id"), it.getString("query"), it.getLong("createdAt")).also { t ->
                require(UUID.fromString(t.id).toString() == t.id && validQuery(t.query) && t.createdAt > 0)
            }
        }
        val report = root.optString("report").takeIf { it.isNotBlank() }
        if (report != null) {
            require(report.length <= 16000)
            val decoded = requireNotNull(ChatSearchResults.decode(report))
            require(validQuery(decoded.query) && decoded.items.size in 1..5 && decoded.retrievedAt > 0)
        }
        val query = root.optString("query").takeIf { it.isNotBlank() }; require(query == null || validQuery(query))
        val state = State.valueOf(root.getString("state")); val started = root.getBoolean("started")
        require((pending != null) == (state in setOf(State.PREPARED, State.RUNNING)))
        require(started == (state == State.RUNNING))
        require(pending == null || pending.query == query)
        return Saved(pending, started, report, query, state)
    }
    private fun PersonalStateFile.Document.save(saved: Saved) {
        val root = JSONObject().put("version", 1).put("started", saved.started).put("state", saved.state.name)
        saved.pending?.let { root.put("pending", JSONObject().put("id", it.id).put("query", it.query).put("createdAt", it.createdAt)) }
        saved.report?.let { root.put("report", it) }; saved.query?.let { root.put("query", it) }
        check(write(root.toString())) { "Research checkpoint could not be saved" }
    }
    companion object {
        private fun validQuery(query: String) = query.length in 2..300 && query.none(Char::isISOControl)
        private fun plain(value: String, limit: Int) = SearchReadableText.format(value.take(limit)).text.filterNot(Char::isISOControl).trim()
    }
}

object ResearchBriefCommands {
    enum class Command { START, REVIEW, STATUS, RETRY, CANCEL, CLEAR, INVALID }
    data class Input(val command: Command, val query: String? = null)
    fun parse(text: String): Input? {
        val clean = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        val command = when (clean) {
            "review research brief", "summarize that research", "show research brief" -> Command.REVIEW
            "research status" -> Command.STATUS
            "retry research" -> Command.RETRY
            "cancel research" -> Command.CANCEL
            "clear research brief" -> Command.CLEAR
            else -> null
        }
        if (command != null) return Input(command)
        if (Regex("^(?:research status|review research brief|summarize that research|show research brief|retry research|cancel research|clear research brief)\\b").containsMatchIn(clean)) return Input(Command.INVALID)
        val match = Regex("(?i)^research(?: and summarize)? (.+?)(?: and summarize)?$").matchEntire(text.trim())
        if (match != null) return Input(Command.START, match.groupValues[1].trim())
        return if (Regex("^(?:research|review research brief|summarize that research|show research brief|retry research|cancel research|clear research brief)\\b").containsMatchIn(clean)) Input(Command.INVALID) else null
    }
    fun local(input: Input, briefs: ResearchBriefs): String = when (input.command) {
        Command.REVIEW -> briefs.review()
        Command.STATUS -> briefs.status()
        Command.CANCEL -> briefs.cancel()
        Command.CLEAR -> briefs.clear()
        Command.START, Command.RETRY -> "This request needs an online research lookup. Ask it in chat or voice; saved briefs remain available through 'review research brief'."
        Command.INVALID -> "Use 'research solar panels and summarize', 'review research brief', 'research status', 'retry research' or 'cancel research'. No lookup was started."
    }
}
