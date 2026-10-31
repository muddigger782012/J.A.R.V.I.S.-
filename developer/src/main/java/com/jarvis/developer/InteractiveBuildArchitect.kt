package com.jarvis.developer

/**
 * Validated, project-scoped design decisions. This pure model deliberately has no ability to
 * execute generated code, edit files, trigger CI, or publish builds.
 */
object InteractiveBuildArchitect {
    enum class Consultation { AUTOMATIC, COLLABORATIVE, MANUAL }
    enum class Navigation { DRAWER, TABS }
    enum class Density { COMPACT, BALANCED, SPACIOUS }
    enum class ControlStyle { HYBRID, ICONS, LABELS }
    enum class Screen { PHONE, TABLET }

    data class Configuration(
        val consultation: Consultation = Consultation.COLLABORATIVE,
        val navigation: Navigation = Navigation.DRAWER,
        val density: Density = Density.BALANCED,
        val scalePercent: Int = 100,
        val proactiveRecommendations: Boolean = true,
        val controls: ControlStyle = ControlStyle.HYBRID,
        val adaptiveLayout: Boolean = true,
        val preserveJarvisTheme: Boolean = true
    ) {
        init {
            require(scalePercent in 80..130 && scalePercent % 5 == 0) {
                "Interface scale must be 80–130% in 5% increments"
            }
            require(adaptiveLayout) { "Approved adaptive layout cannot be disabled" }
            require(preserveJarvisTheme) { "Approved Jarvis theme cannot be disabled" }
        }
    }

    enum class ChoiceType { SINGLE_SELECT, SCALE, TOGGLE, PREVIEW }
    data class Choice(
        val id: String,
        val label: String,
        val type: ChoiceType,
        val options: List<String> = emptyList(),
        val recommendedValue: String,
        val rationale: String
    ) {
        init {
            require(id.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Invalid choice ID" }
            require(label.length in 1..120 && rationale.length in 1..600)
            require(options.size <= 12 && options.distinct().size == options.size)
            require(options.all { it.length in 1..80 })
            if (type == ChoiceType.SINGLE_SELECT) {
                require(options.isNotEmpty() && recommendedValue in options)
            }
        }
    }

    data class ConsultationCard(
        val projectId: String,
        val decisionId: String,
        val title: String,
        val choices: List<Choice>
    ) {
        init {
            require(projectId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")))
            require(decisionId.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
            require(title.length in 1..140)
            require(choices.size in 1..16)
            require(choices.map { it.id }.distinct().size == choices.size)
        }
    }

    data class Selection(val decisionId: String, val values: Map<String, String>)

    /** Check selections against the exact reviewed card before any approval is accepted. */
    fun validate(card: ConsultationCard, selection: Selection): Selection {
        require(selection.decisionId == card.decisionId) { "Stale decision" }
        require(selection.values.keys == card.choices.map { it.id }.toSet()) { "Missing or unknown choices" }
        card.choices.forEach { choice ->
            val value = selection.values.getValue(choice.id)
            when (choice.type) {
                ChoiceType.SINGLE_SELECT -> require(value in choice.options)
                ChoiceType.TOGGLE -> require(value == "true" || value == "false")
                ChoiceType.SCALE -> require(value.toIntOrNull()?.let { it in 80..130 && it % 5 == 0 } == true)
                ChoiceType.PREVIEW -> require(value == "phone" || value == "tablet")
            }
        }
        return selection
    }

    /** Stable, theme-preserving preview dimensions for phone/tablet design mockups. */
    fun previewWidthDp(screen: Screen): Int = when (screen) {
        Screen.PHONE -> 360
        Screen.TABLET -> 840
    }

    fun defaults() = Configuration()
}
