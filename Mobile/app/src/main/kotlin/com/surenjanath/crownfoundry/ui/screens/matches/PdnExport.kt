package com.surenjanath.crownfoundry.ui.screens.matches

import android.content.Context
import android.content.Intent
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.DRAW_RESULT
import com.surenjanath.crownfoundry.engine.Pdn
import com.surenjanath.crownfoundry.engine.WHITE
import com.surenjanath.crownfoundry.enums.Difficulty
import com.surenjanath.crownfoundry.offline.toEngineRules

/**
 * A finished game, on its way out of the app.
 *
 * Export is the promise that a game does not only exist here. PDN is what every other draughts
 * program reads, so a game shared from this screen can be opened, analysed, or archived by
 * something that has never heard of CrownFoundry.
 *
 * It travels as text rather than as a file. A game is a few hundred bytes, every share target
 * accepts text, and a file needs a content provider whose only job would be to hand over those
 * same few hundred bytes.
 */
object PdnExport {

    /** No `Date` tag is invented: the backend does not send one, and PDN has a spelling for that. */
    fun of(match: MatchDto): String = Pdn.export(
        moves = match.history.map { it.move },
        tags = Pdn.Tags(
            event = "CrownFoundry match",
            site = "CrownFoundry",
            round = "-",
            // White is the engine, Black is the person holding the phone.
            white = "CrownFoundry ${Difficulty.fromWire(match.difficulty).label}",
            black = "Player",
            result = Pdn.resultOf(winnerOf(match)),
            rules = match.rules.toEngineRules(),
            extra = buildList {
                if (match.matchId.isNotBlank()) add("MatchId" to match.matchId)
                if (match.ai.policyVersion > 0) {
                    add("PolicyVersion" to match.ai.policyVersion.toString())
                }
            }
        )
    )

    /** Engine side constants, so [Pdn.resultOf] writes the result from White's point of view. */
    private fun winnerOf(match: MatchDto): Int? = when {
        !match.isFinished -> null
        match.winner == Side.AI -> WHITE
        match.winner == Side.HUMAN -> BLACK
        match.winner == Side.DRAW -> DRAW_RESULT
        else -> null
    }

    /** True when there is a game to export. An opening position is not one. */
    fun isExportable(match: MatchDto?) = match != null && match.history.isNotEmpty()

    fun share(context: Context, match: MatchDto) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "CrownFoundry match")
            putExtra(Intent.EXTRA_SUBJECT, "CrownFoundry match")
            putExtra(Intent.EXTRA_TEXT, of(match))
        }

        context.startActivity(
            Intent.createChooser(intent, "Share this game").apply {
                // The chooser is started from a composable, which may not be an Activity context.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
