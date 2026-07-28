package com.aegis.core.classifier

import com.aegis.core.model.Category

/**
 * The lexicons Aegis ships with.
 *
 * Two things are worth saying plainly about this data:
 *
 * 1. It is a starting point, not the ceiling. Every category carries dampeners as well
 *    as triggers, because the failure mode that destroys trust in a filter is blocking
 *    a cancer charity or an addiction helpline for using the vocabulary it has to use.
 *
 * 2. It is deliberately clinical. The point is to recognise a class of content, not to
 *    reproduce it, so the triggers are the neutral markers such pages carry rather than
 *    the material itself.
 */
object DefaultLexicons {

    private fun t(pattern: String, weight: Float) = Term(pattern, weight)

    val ADULT = Lexicon(
        category = Category.ADULT,
        terms = listOf(
            t("porn", 1.0f), t("pornography", 1.0f), t("xxx", 0.8f),
            t("hardcore", 0.5f), t("explicit content", 0.6f), t("nsfw", 0.5f),
            t("hentai", 1.0f), t("camgirl", 0.9f), t("live cams", 0.8f),
            t("webcam girls", 1.0f), t("escort service", 0.9f), t("escorts", 0.7f),
            t("onlyfans", 0.7f), t("nudes", 0.7f), t("nude photos", 0.8f),
            t("sex video", 1.0f), t("sex videos", 1.0f), t("adult video", 0.9f),
            t("adult videos", 0.9f), t("erotic", 0.6f), t("erotica", 0.6f),
            t("fetish", 0.6f), t("strip club", 0.6f), t("brothel", 0.7f),
            t("18+ only", 0.6f), t("uncensored", 0.4f), t("full length scenes", 0.7f),
        ),
        dampeners = listOf(
            t("sex education", 1.2f), t("sexual health", 1.2f), t("sexually transmitted", 1.0f),
            t("reproductive health", 1.0f), t("breast cancer", 1.4f), t("consent workshop", 0.8f),
            t("art history", 0.8f), t("life drawing", 0.8f), t("puberty", 0.6f),
            t("safeguarding", 0.8f), t("content moderation", 0.8f), t("age verification policy", 0.6f),
        ),
        hostSubstrings = listOf("porn", "xxx", "hentai", "camgirl", "onlyfans", "nsfw", "escort"),
        hostTokens = listOf("adult", "nude", "sex", "cam", "cams", "xnxx", "redtube", "tube8"),
        tlds = listOf("xxx", "porn", "sex", "adult", "cam"),
    )

    val VIOLENCE = Lexicon(
        category = Category.VIOLENCE,
        terms = listOf(
            t("gore", 0.8f), t("gruesome", 0.6f), t("beheading", 1.2f),
            t("execution video", 1.2f), t("graphic footage", 0.9f), t("graphic video", 0.9f),
            t("mutilated", 0.9f), t("dismembered", 1.0f), t("snuff", 1.0f),
            t("torture video", 1.2f), t("brutal killing", 1.1f), t("death footage", 1.1f),
            t("crime scene photos", 0.8f), t("shocking video", 0.6f), t("liveleak", 0.7f),
            t("nsfl", 0.9f),
        ),
        dampeners = listOf(
            t("video game", 1.2f), t("movie review", 1.0f), t("film review", 1.0f),
            t("fictional", 0.8f), t("documentary", 0.8f), t("war memorial", 1.0f),
            t("history of", 0.6f), t("first aid", 1.0f), t("trauma surgery", 1.2f),
            t("forensic pathology", 1.2f), t("safety training", 1.0f),
        ),
        hostSubstrings = listOf("gore", "bestgore"),
        hostTokens = listOf("nsfl", "liveleak", "documenting"),
        tlds = emptyList(),
    )

    val GAMBLING = Lexicon(
        category = Category.GAMBLING,
        terms = listOf(
            t("casino", 0.9f), t("betting odds", 1.0f), t("sportsbook", 1.1f),
            t("roulette", 0.8f), t("blackjack", 0.7f), t("free spins", 1.0f),
            t("slots bonus", 1.0f), t("poker room", 0.8f), t("bookmaker", 0.9f),
            t("bet now", 1.0f), t("place your bets", 1.0f), t("jackpot", 0.6f),
            t("loot box", 0.6f), t("parlay", 0.8f), t("accumulator odds", 1.0f),
            t("deposit bonus", 0.9f), t("wager", 0.6f), t("in-play betting", 1.1f),
            t("welcome bonus", 0.5f), t("stake", 0.3f),
        ),
        dampeners = listOf(
            t("gambling addiction", 1.6f), t("problem gambling", 1.6f), t("gamblers anonymous", 1.6f),
            t("self-exclusion", 1.4f), t("gamstop", 1.4f), t("gambling harm", 1.4f),
            t("responsible gambling research", 1.0f), t("regulation", 0.5f),
        ),
        hostSubstrings = listOf("casino", "betting", "sportsbook", "pokerstars"),
        hostTokens = listOf("bet", "bets", "poker", "slots", "odds", "wager"),
        tlds = listOf("casino", "bet", "poker", "bingo"),
    )

    val SELF_HARM = Lexicon(
        category = Category.SELF_HARM,
        terms = listOf(
            t("thinspo", 1.4f), t("thinspiration", 1.4f), t("pro ana", 1.5f),
            t("proana", 1.5f), t("pro mia", 1.5f), t("meanspo", 1.4f),
            t("how to starve", 1.4f), t("starvation tips", 1.4f), t("body check", 0.7f),
            t("self harm methods", 1.5f), t("cutting tips", 1.4f), t("suicide method", 1.5f),
            t("how to end it", 1.0f), t("purge tips", 1.4f), t("goal weight", 0.5f),
            t("calorie restriction extreme", 1.0f),
        ),
        dampeners = listOf(
            t("recovery", 1.2f), t("helpline", 1.6f), t("crisis line", 1.6f),
            t("eating disorder treatment", 1.6f), t("support group", 1.2f), t("samaritans", 1.6f),
            t("warning signs", 1.0f), t("if you are struggling", 1.6f), t("get help", 1.0f),
            t("therapy", 1.0f), t("beat eating disorders", 1.6f),
        ),
        hostSubstrings = listOf("thinspo", "proana"),
        hostTokens = emptyList(),
        tlds = emptyList(),
    )

    val EXTREMIST = Lexicon(
        category = Category.EXTREMIST,
        terms = listOf(
            t("ethnic cleansing", 1.0f), t("race war", 1.4f), t("racial purity", 1.4f),
            t("master race", 1.3f), t("subhuman", 1.2f), t("great replacement", 1.3f),
            t("white genocide", 1.4f), t("holocaust denial", 1.2f), t("holocaust never happened", 1.6f),
            t("kill all", 1.2f), t("deport them all", 1.2f), t("accelerationism", 1.0f),
            t("shooter manifesto", 1.4f), t("day of the rope", 1.5f), t("blood and soil", 1.3f),
            t("they are vermin", 1.5f), t("cleanse the nation", 1.4f),
        ),
        dampeners = listOf(
            t("counter-extremism", 1.8f), t("deradicalization", 1.8f), t("deradicalisation", 1.8f),
            t("condemned", 1.0f), t("holocaust memorial", 1.8f), t("holocaust education", 1.8f),
            t("research paper", 1.0f), t("academic study", 1.0f), t("hate crime report", 1.4f),
            t("prevent duty", 1.2f), t("journalism", 0.8f), t("history of", 0.6f),
        ),
        hostSubstrings = emptyList(),
        hostTokens = emptyList(),
        tlds = emptyList(),
    )

    val DRUGS = Lexicon(
        category = Category.DRUGS,
        terms = listOf(
            t("buy cocaine", 1.5f), t("buy mdma", 1.5f), t("darknet market", 1.3f),
            t("weed delivery", 1.1f), t("steroids for sale", 1.3f), t("research chemicals", 0.9f),
            t("vendor reviews", 0.6f), t("escrow market", 0.9f), t("legal highs", 1.0f),
            t("buy xanax", 1.4f), t("no prescription needed", 1.3f),
        ),
        dampeners = listOf(
            t("rehab", 1.4f), t("addiction recovery", 1.6f), t("harm reduction", 1.4f),
            t("drug policy", 1.2f), t("pharmacology", 1.2f), t("clinical trial", 1.2f),
            t("overdose prevention", 1.6f),
        ),
        hostSubstrings = emptyList(),
        hostTokens = emptyList(),
        tlds = emptyList(),
    )

    val SOCIAL = Lexicon(
        category = Category.SOCIAL,
        terms = listOf(
            t("for you page", 0.7f), t("infinite scroll", 0.5f), t("who to follow", 0.6f),
            t("trending now", 0.4f), t("stories", 0.2f), t("reels", 0.6f),
            t("retweet", 0.6f), t("upvote", 0.5f), t("news feed", 0.7f),
        ),
        dampeners = emptyList(),
        hostSubstrings = listOf("facebook", "instagram", "tiktok", "snapchat", "pinterest"),
        // "x" is deliberately absent. A single-letter label matches things like
        // cdn-x.example.com, and with Social on a timed budget that would start blocking
        // random content hosts every evening. x.com is better handled as a destination
        // rule, where the user asked for it explicitly.
        hostTokens = listOf("twitter", "reddit", "threads", "tumblr", "9gag"),
        tlds = emptyList(),
    )

    val ALL: Map<Category, Lexicon> = listOf(
        ADULT, VIOLENCE, GAMBLING, SELF_HARM, EXTREMIST, DRUGS, SOCIAL,
    ).associateBy { it.category }
}
