package com.surenjanath.crownfoundry.ui.screens.game

import com.surenjanath.crownfoundry.api.Side

/**
 * The game, as the screen needs to say it.
 *
 * [GameState] knows what is true; this decides what is written down. The split earns its place
 * because the same fact used to be derived in four different files - "is this pass-and-play, and
 * therefore is this side called Black or You" was spelled out in the opponent card, the player
 * card, the action bar and the game-over dialog, and the captured-piece arithmetic in two of them.
 * Four copies of a rule is four chances for them to disagree about the same game.
 *
 * Nothing here imports Compose, so it is exercised on the JVM alongside the turn machine rather
 * than through a device.
 */

enum class SeatAvatar { Engine, Person }

/** A small piece of standing detail about a seat: its rating, how hard it is trying, where it runs. */
data class SeatTag(val text: String, val accented: Boolean = false)

/**
 * One side of the board, as a card can draw it.
 *
 * Both seats are the same shape even though only one of them ever carries a rating or an
 * evaluation. A seat that reports `null` for those draws the space and leaves it empty, which is
 * what keeps the two cards the same height as each other and the same height all game.
 */
data class SeatView(
    val name: String,
    val avatar: SeatAvatar,
    /**
     * Black's men are the accent ones and White's are drawn in the text colour. The seat carries
     * which side it is so the card can colour its own pieces, and the pieces it has taken in the
     * other side's colour, without being told twice.
     */
    val isBlackSide: Boolean,
    /** Drives the card's highlight, and the only place the screen says whose turn it is. */
    val isToMove: Boolean,
    val pieces: Int,
    val kings: Int,
    val captured: Int,
    val tags: List<SeatTag> = emptyList(),
    val evaluation: String? = null,
    val clock: String? = null
)

/** What the opponent has to say, if anything. The strip holds its line either way. */
sealed interface Commentary {
    data object Silent : Commentary
    data object Thinking : Commentary
    data class Said(val text: String) : Commentary
}

/**
 * The one thing the action bar announces.
 *
 * Only states the seats cannot show themselves: the game being set up, a capture being compulsory,
 * and how it ended. Whose turn it is is deliberately absent - the seat that has the move says so,
 * and saying it twice was half of what made this screen noisy.
 */
data class GameEvent(val text: String, val urgent: Boolean = false)

data class GamePresentation(
    val opponent: SeatView,
    val you: SeatView,
    val event: GameEvent?,
    val commentary: Commentary,
    /** Where the game has got to, for the rail above the board. */
    val moveLabel: String
)

/**
 * Read the screen off the game.
 *
 * [engineLabel] is the on-device engine's version when the match is being refereed by the phone,
 * and null when the server has it - the player is entitled to know which brain answered.
 * [elapsedSeconds] is the clock the composable ticks; it is formatted here so the formatting is
 * covered by the same tests as everything else.
 */
fun GameState.present(
    showReasoning: Boolean = true,
    showEvaluation: Boolean = true,
    engineLabel: String? = null,
    elapsedSeconds: Int = 0
): GamePresentation {
    val passAndPlay = mode.isPassAndPlay
    val counts = this.counts
    // A board still being dealt has taken nothing. Reading the tally off "twelve minus what is
    // there" would otherwise announce a clean sweep for both sides while the match loads.
    val dealt = pieces.isNotEmpty()

    val opponentToMove = !isOver && sideToMove == Side.AI
    val youToMove = !isOver && sideToMove == Side.HUMAN

    val opponent = SeatView(
        name = if (passAndPlay) "White" else "Opponent",
        avatar = if (passAndPlay) SeatAvatar.Person else SeatAvatar.Engine,
        isBlackSide = false,
        isToMove = opponentToMove,
        pieces = counts.white,
        kings = counts.whiteKings,
        // Twelve men a side, so what is missing is what the other player took.
        captured = if (dealt) capturedOf(counts.black) else 0,
        tags = if (passAndPlay) emptyList() else buildList {
            if (aiStatus.elo > 0) add(SeatTag("${aiStatus.elo} Elo"))
            add(SeatTag(difficulty.replaceFirstChar { it.uppercase() }, accented = true))
            engineLabel?.let { add(SeatTag(it, accented = true)) }
        },
        evaluation = evaluation
            ?.takeIf { !passAndPlay && showEvaluation && phase != GamePhase.Thinking }
            ?.let { "Q ${signed(it.qValue)}" }
    )

    val you = SeatView(
        name = if (passAndPlay) "Black" else "You",
        avatar = SeatAvatar.Person,
        isBlackSide = true,
        isToMove = youToMove,
        pieces = counts.black,
        kings = counts.blackKings,
        captured = if (dealt) capturedOf(counts.white) else 0,
        clock = clockOf(elapsedSeconds)
    )

    return GamePresentation(
        opponent = opponent,
        you = you,
        moveLabel = moveLabelOf(),
        event = eventOf(passAndPlay),
        commentary = when {
            passAndPlay -> Commentary.Silent
            phase == GamePhase.Thinking -> Commentary.Thinking
            showReasoning && reasoning != null -> Commentary.Said(reasoning!!)
            else -> Commentary.Silent
        }
    )
}

/**
 * The move about to be played while the game is live, and how long it took once it is not.
 *
 * A game nobody has moved in is on its first move rather than its zeroth, which is why this
 * counts one ahead of the moves actually played.
 */
private fun GameState.moveLabelOf(): String = when {
    isOver -> "$turnNumber ${if (turnNumber == 1) "move" else "moves"}"
    else -> "Move ${turnNumber + 1}"
}

/**
 * Precedence, worst news first: a game that has not started yet, then how it finished, then a
 * capture the player is not allowed to decline. Anything else and the bar has nothing to add.
 *
 * "Thinking" is absent on purpose - the commentary strip is already shimmering, and the bar
 * repeating it was the same fact in two places.
 */
private fun GameState.eventOf(passAndPlay: Boolean): GameEvent? = when {
    phase == GamePhase.Loading -> GameEvent("Setting up…")

    isOver -> GameEvent(
        when (winner) {
            Side.HUMAN -> if (passAndPlay) "Black won" else "You won"
            Side.AI -> if (passAndPlay) "White won" else "It won"
            Side.DRAW -> "A draw"
            else -> "Game over"
        }
    )

    mustCapture -> GameEvent("Capture is mandatory", urgent = true)

    // Lowest priority, and only until the next thing happens: a button that appears to do
    // nothing is worse than one that says why it cannot.
    hintUnavailable -> GameEvent("No engine on this device to ask")

    else -> null
}

/** Twelve a side at the start, so a side's losses are the other side's haul. */
internal fun capturedOf(remaining: Int) = (STARTING_MEN - remaining).coerceAtLeast(0)

internal fun clockOf(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)

private fun signed(value: Double): String = if (value >= 0) "+%.2f".format(value) else "%.2f".format(value)

private const val STARTING_MEN = 12
