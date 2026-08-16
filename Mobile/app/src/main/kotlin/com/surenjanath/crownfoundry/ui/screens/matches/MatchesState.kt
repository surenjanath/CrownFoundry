package com.surenjanath.crownfoundry.ui.screens.matches

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.MatchSummaryDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.enums.Difficulty
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The match list's state machine and the wording of a row, kept out of the composable so both
 * can be tested without a screen.
 *
 * Every result on this screen is written from the human's point of view. The backend records the
 * winner as a colour; a reader wants to know whether they won.
 */

enum class MatchOutcome {
    Won,
    Lost,
    Drawn,
    InProgress
}

fun outcomeOf(match: MatchSummaryDto): MatchOutcome = when {
    match.status.equals("active", ignoreCase = true) -> MatchOutcome.InProgress
    match.winner == Side.HUMAN -> MatchOutcome.Won
    match.winner == Side.AI -> MatchOutcome.Lost
    else -> MatchOutcome.Drawn
}

/** The short badge on the row: two or three characters, because the row has a monogram slot. */
fun outcomeBadge(outcome: MatchOutcome): String = when (outcome) {
    MatchOutcome.Won -> "WON"
    MatchOutcome.Lost -> "LOST"
    MatchOutcome.Drawn -> "DRAW"
    MatchOutcome.InProgress -> "OPEN"
}

fun matchTitle(match: MatchSummaryDto): String {
    val turns = match.totalTurns

    return when (outcomeOf(match)) {
        MatchOutcome.Won -> "You won${inTurns(turns)}"
        MatchOutcome.Lost -> "It won${inTurns(turns)}"
        MatchOutcome.Drawn -> "Drawn${afterTurns(turns)}"
        MatchOutcome.InProgress ->
            if (turns <= 0) "Still going, no moves played yet"
            else "Still going, $turns ${turnWord(turns)} in"
    }
}

fun matchSubtitle(match: MatchSummaryDto): String {
    val difficulty = Difficulty.fromWire(match.difficulty).label
    return "$difficulty · you took ${match.humanCaptures}, it took ${match.aiCaptures}"
}

/** A finished match is dated by when it ended; one still running, by when it started. */
fun matchTimestampSeconds(match: MatchSummaryDto): Long? =
    epochSecondsOf(match.endTime) ?: epochSecondsOf(match.startTime)

/** ISO-8601 as the referee writes it, in whichever of the three shapes it arrives. */
fun epochSecondsOf(text: String?): Long? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null

    runCatching { return Instant.parse(raw).epochSecond }
    runCatching { return OffsetDateTime.parse(raw).toEpochSecond() }
    runCatching { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).epochSecond }

    return null
}

private fun inTurns(turns: Int) = if (turns > 0) " in $turns ${turnWord(turns)}" else ""

private fun afterTurns(turns: Int) = if (turns > 0) " after $turns ${turnWord(turns)}" else ""

private fun turnWord(turns: Int) = if (turns == 1) "turn" else "turns"

// --- state --------------------------------------------------------------------------------------

data class MatchesState(
    val isLoading: Boolean = true,
    val matches: List<MatchSummaryDto> = emptyList(),
    val error: ApiError? = null
) {
    val isEmpty: Boolean get() = !isLoading && error == null && matches.isEmpty()
}

/**
 * Loads the match list and holds whichever of the three states it is in. A failed reload keeps
 * nothing: a stale list under an error message would be a lie about what the backend just said.
 */
class MatchesStateHolder(
    private val api: CheckersApi,
    private val playerId: String?,
    private val limit: Int = 50
) {
    var state by mutableStateOf(MatchesState())
        private set

    suspend fun load() {
        state = state.copy(isLoading = true, error = null)

        state = when (val outcome = api.matches(playerId = playerId, limit = limit)) {
            is Outcome.Success -> MatchesState(
                isLoading = false,
                matches = outcome.value.matches,
                error = null
            )

            is Outcome.Failure -> MatchesState(
                isLoading = false,
                matches = emptyList(),
                error = outcome.reason
            )
        }
    }
}
