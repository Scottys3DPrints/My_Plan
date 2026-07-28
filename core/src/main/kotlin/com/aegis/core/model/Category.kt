package com.aegis.core.model

import kotlinx.serialization.Serializable

/**
 * The unit of control in Aegis.
 *
 * Rules are expressed over categories, never over URLs. A site nobody has ever seen
 * before still gets caught, because it is judged on what it contains.
 *
 * The set is intentionally open to extension: [id] is the stable key used in storage
 * and in lexicon files, so adding a category is an additive change.
 */
@Serializable
enum class Category(val id: String, val label: String, val description: String) {
    ADULT(
        id = "adult",
        label = "Adult / sexual content",
        description = "Pornography and sexually explicit material, including explicit content embedded in otherwise ordinary sites.",
    ),
    VIOLENCE(
        id = "violence",
        label = "Graphic violence / gore",
        description = "Real or realistic depictions of severe injury, death, and gore.",
    ),
    GAMBLING(
        id = "gambling",
        label = "Gambling",
        description = "Betting, casino, loot-box and real-money wagering content.",
    ),
    SELF_HARM(
        id = "self_harm",
        label = "Self-harm / pro-eating-disorder",
        description = "Content that encourages self-injury, suicide, or disordered eating.",
    ),
    EXTREMIST(
        id = "extremist",
        label = "Extremist / hate content",
        description = "Dehumanising or violent-extremist material targeting people by group identity.",
    ),
    DRUGS(
        id = "drugs",
        label = "Drugs / alcohol",
        description = "Recreational drug and alcohol sourcing, sales, and promotion.",
    ),
    SOCIAL(
        id = "social",
        label = "Social media / infinite feeds",
        description = "Endless-scroll feeds and social platforms. Usually budgeted rather than walled.",
    ),
    ;

    companion object {
        fun fromId(id: String): Category? = entries.firstOrNull { it.id == id }
    }
}
