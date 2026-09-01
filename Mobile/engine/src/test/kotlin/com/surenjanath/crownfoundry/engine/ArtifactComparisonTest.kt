package com.surenjanath.crownfoundry.engine

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Play two published policies off against each other, to decide whether to publish the newer one.
 *
 * A tool wearing a test's clothes, and it skips itself unless it is given two artifacts:
 *
 * ```
 * ./gradlew :engine:testDebugUnitTest --tests '*ArtifactComparisonTest*' \
 *   -Pcrownfoundry.champion=app/src/main/assets/policy.cfe \
 *   -Pcrownfoundry.candidate=../docs/engine/policy.cfe
 * ```
 *
 * It exists because the backend's own save gate cannot answer this question. `should_save` compares
 * baseline scores against a random and a greedy opponent, and `ai.training.evaluate` plays every one
 * of those games from the opening position - so for two deterministic policies it is the same game
 * repeated, and for a policy strong enough to win it, the score is 1.0 before and 1.0 after. A run
 * that made the policy worse still clears that bar. Measured here, the shipped v42 beats both fixed
 * opponents every game from the opening and only 69% of the time from varied ones.
 *
 * So the question is asked the way the device asks it: the two policies play each other directly,
 * from randomised openings, alternating seats. That has no ceiling and no shared blind spot to hide
 * in. The baselines are still reported, because a candidate that wins the match while collapsing
 * against a random mover has found something about its predecessor rather than about draughts.
 */
class ArtifactComparisonTest {

    private fun artifact(property: String): Pair<EngineHeader, QNetwork>? =
        System.getProperty(property)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { EngineArtifact.read(it.readBytes()) }

    @Test
    fun `the candidate is measured against the policy it would replace`() {
        val champion = artifact("crownfoundry.champion")
        val candidate = artifact("crownfoundry.candidate")

        assumeTrue(
            "pass -Pcrownfoundry.champion and -Pcrownfoundry.candidate to run the comparison",
            champion != null && candidate != null
        )
        champion!!
        candidate!!

        val games = System.getProperty("crownfoundry.compareGames")?.toIntOrNull() ?: 40
        val knobs = Knobs(depth = 2, epsilon = 0f, risk = 0.6f, topK = 5)

        val match = evaluate(
            NetworkPlayer(candidate.second, knobs),
            NetworkPlayer(champion.second, knobs),
            games = games,
            random = Random(20260831)
        )
        val championBaseline = guardScore(champion.second, games = 20)
        val candidateBaseline = guardScore(candidate.second, games = 20)

        println("=".repeat(72))
        println("champion  v${champion.first.version}  ${champion.first.architecture}  " +
                "elo ${champion.first.elo}  ${champion.first.gamesTrained} games trained")
        println("candidate v${candidate.first.version}  ${candidate.first.architecture}  " +
                "elo ${candidate.first.elo}  ${candidate.first.gamesTrained} games trained")
        println("-".repeat(72))
        println("head-to-head over $games varied openings: " +
                "candidate scores ${pct(match.score)} " +
                "(W${match.wins} D${match.draws} L${match.losses}, ${match.avgPlies} plies avg)")
        println("baseline vs random+greedy: " +
                "champion ${pct(championBaseline)} -> candidate ${pct(candidateBaseline)}")
        println("-".repeat(72))

        val verdict = when {
            candidateBaseline < championBaseline - 0.18f ->
                "DO NOT PUBLISH - the candidate lost ground against the fixed opponents"

            match.score < 0.55f ->
                "DO NOT PUBLISH - the candidate does not clearly beat what it would replace"

            else -> "PUBLISH - the candidate plays better than what it would replace"
        }
        println(verdict)
        println("=".repeat(72))

        // Reported, not enforced. Deciding to publish is the operator's call and there are
        // reasons to ship a level policy - a different architecture, a rebuilt corpus - that a
        // failing test would simply get in the way of.
        assertTrue(match.score in 0f..1f)
    }

    private fun pct(value: Float) = "${Math.round(value * 100)}%"
}
