package com.surenjanath.crownfoundry.styling

import androidx.compose.ui.graphics.Color
import com.surenjanath.crownfoundry.enums.AccentColor
import com.surenjanath.crownfoundry.enums.BoardStyle
import com.surenjanath.crownfoundry.ui.styling.ColorPalette
import com.surenjanath.crownfoundry.ui.styling.DefaultDarkColorPalette
import com.surenjanath.crownfoundry.ui.styling.DefaultLightColorPalette
import com.surenjanath.crownfoundry.ui.styling.PureBlackColorPalette
import com.surenjanath.crownfoundry.ui.styling.boardColorsOf
import com.surenjanath.crownfoundry.ui.styling.contrastRatio
import com.surenjanath.crownfoundry.ui.styling.squareContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board has one job before it has any other: to look like a board.
 *
 * These are the numbers that used to fail. Drawn from `background1`/`background2` the squares sat
 * at a contrast ratio of about 1.1 in both default palettes and at exactly 1.0 in Pure Black,
 * where the two tokens are the same colour and the pattern disappeared entirely.
 */
class BoardColorsTest {

    private val palettes: List<Pair<String, ColorPalette>> = buildList {
        for (accent in AccentColor.entries) {
            add("light/${accent.name}" to DefaultLightColorPalette.copy(accent = accent.color))
            add("dark/${accent.name}" to DefaultDarkColorPalette.copy(accent = accent.color))
            add("black/${accent.name}" to PureBlackColorPalette.copy(accent = accent.color))
        }
    }

    /** 2.2:1 is roughly where an 8x8 pattern stops reading as one at arm's length. */
    @Test
    fun `every style reads as a checkerboard in every palette`() {
        for (style in BoardStyle.entries) {
            for ((name, palette) in palettes) {
                val ratio = boardColorsOf(style, palette).squareContrast
                assertTrue(
                    "$style on $name has square contrast $ratio, under 2.2",
                    ratio >= 2.2f
                )
            }
        }
    }

    /** The old pairing, kept as the thing this file exists to prevent coming back. */
    @Test
    fun `the surface tokens the board used to use are not a checkerboard`() {
        assertTrue(
            contrastRatio(
                PureBlackColorPalette.background1,
                PureBlackColorPalette.background2
            ) < 1.01f
        )
        assertTrue(
            contrastRatio(
                DefaultLightColorPalette.background1,
                DefaultLightColorPalette.background2
            ) < 1.2f
        )
    }

    /** Hints, selection rings and threat marks are all drawn on the playable squares. */
    @Test
    fun `markers stay visible on the squares they are drawn on`() {
        for (style in BoardStyle.entries) {
            for ((name, palette) in palettes) {
                val board = boardColorsOf(style, palette)
                assertTrue(
                    "$style on $name: marker contrast ${contrastRatio(board.marker, board.dark)}",
                    contrastRatio(board.marker, board.dark) >= 3.0f
                )
                assertTrue(
                    "$style on $name: threat contrast ${contrastRatio(board.threat, board.dark)}",
                    contrastRatio(board.threat, board.dark) >= 3.0f
                )
            }
        }
    }

    /** A style that does not change with the accent would make the Accent style a lie. */
    @Test
    fun `the accent style follows the accent and the fixed styles do not`() {
        val orange = DefaultLightColorPalette.copy(accent = AccentColor.Orange.color)
        val ocean = DefaultLightColorPalette.copy(accent = AccentColor.Ocean.color)

        assertTrue(
            boardColorsOf(BoardStyle.Accent, orange).dark !=
                    boardColorsOf(BoardStyle.Accent, ocean).dark
        )
        assertTrue(
            boardColorsOf(BoardStyle.Classic, orange).dark ==
                    boardColorsOf(BoardStyle.Classic, ocean).dark
        )
    }

    /** Light squares are lighter than dark ones. Obvious, and the kind of thing a hex typo breaks. */
    @Test
    fun `light squares are lighter than playable ones`() {
        for (style in BoardStyle.entries) {
            for ((name, palette) in palettes) {
                val board = boardColorsOf(style, palette)
                assertTrue(
                    "$style on $name has its light square darker than its playable square",
                    contrastRatio(board.light, Color.White) <
                            contrastRatio(board.dark, Color.White)
                )
            }
        }
    }
}
