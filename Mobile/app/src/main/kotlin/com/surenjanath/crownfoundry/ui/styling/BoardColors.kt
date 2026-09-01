package com.surenjanath.crownfoundry.ui.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.surenjanath.crownfoundry.enums.BoardStyle
import com.surenjanath.crownfoundry.utils.boardStyleKey
import com.surenjanath.crownfoundry.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours the draughts board is drawn in, as a set of their own.
 *
 * Separate from [ColorPalette] on purpose. The palette's `background1`/`background2` exist to
 * separate a card from the page behind it, so they are deliberately a few percent apart - and in
 * the Pure Black palette they are the *same colour*. Drawing an 8x8 pattern out of them produced
 * a board that was hard to read in two themes and invisible in the third.
 *
 * [marker] and [threat] are not simply the palette's accent and red: they are those colours moved
 * far enough in lightness to stay visible on whichever squares the chosen style puts under them.
 * A hint drawn in orange at 22% alpha over a walnut board is a hint nobody can see.
 */
@Immutable
data class BoardColors(
    /** The unplayable squares - light in every style, by convention. */
    val light: Color,
    /** The playable squares. Every piece and every hint sits on one of these. */
    val dark: Color,
    /** Selection, legal-move hints, the last move's trace, the engine's suggestion. */
    val marker: Color,
    /** Threatened pieces. */
    val threat: Color
)

/**
 * Hue, saturation and lightness, computed from the Compose colour rather than through
 * `ColorUtils.colorToHSL`.
 *
 * The androidx helper reaches into `android.graphics.Color`, which is a stub under unit tests -
 * it answers zero for every channel, so every colour comes back as black and the whole palette
 * collapses to one grey. Board colours are pure arithmetic and worth being able to test as such.
 */
private fun hslOf(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val range = high - low
    val lightness = (high + low) / 2f

    if (range < 1e-6f) return floatArrayOf(0f, 0f, lightness)

    val saturation = range / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-6f)
    val hue = when (high) {
        r -> 60f * (((g - b) / range) % 6f)
        g -> 60f * (((b - r) / range) + 2f)
        else -> 60f * (((r - g) / range) + 4f)
    }
    return floatArrayOf((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
}

/** WCAG relative luminance. */
private fun luminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
}

/** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * The colour at [hue]/[saturation] whose relative luminance is closest to [target].
 *
 * HSL lightness is not luminance and the gap between them depends on the hue: a teal at lightness
 * 0.46 is nearly twice as bright as a blue at the same lightness, because green carries most of
 * the perceived brightness and blue almost none. Choosing the Accent style's squares by lightness
 * therefore produced a readable board for orange and a washed-out one for emerald. Choosing them
 * by luminance gives every accent the same contrast.
 *
 * Bisection, because luminance rises monotonically with lightness at a fixed hue - twenty
 * iterations put it within a millionth, far past anything a screen can show.
 */
private fun atLuminance(hue: Float, saturation: Float, target: Float): Color {
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (luminance(Color.hsl(hue, saturation, mid)) < target) low = mid else high = mid
    }
    return Color.hsl(hue, saturation, (low + high) / 2f)
}

/**
 * [color], pushed away from [against] in lightness until it is clearly visible on it.
 *
 * Hue and saturation are held: the point is to keep the accent recognisably the player's accent
 * while making it legible, not to substitute a different colour for it. Direction is chosen by
 * which way there is more room, so a marker on a dark board brightens and one on a light board
 * darkens rather than both drifting toward mid-grey and stalling.
 */
internal fun contrastAgainst(color: Color, against: Color, target: Float = 3.2f): Color {
    if (contrastRatio(color, against) >= target) return color

    val hsl = hslOf(color)
    val towardsLight = luminance(against) < 0.35f
    var lightness = hsl[2]
    // 24 steps of 2.5% covers the whole range; anything that has not cleared the target by then
    // is a colour with nowhere left to go, and the endpoint is the best available answer.
    repeat(24) {
        lightness = (lightness + if (towardsLight) 0.025f else -0.025f).coerceIn(0.04f, 0.96f)
        val candidate = Color.hsl(hsl[0], hsl[1], lightness)
        if (contrastRatio(candidate, against) >= target) return candidate
    }
    return Color.hsl(hsl[0], hsl[1], lightness)
}

/**
 * The squares for [style], in the light or dark variant [palette] calls for.
 *
 * Each pair was picked to sit at least four luminance steps apart - far enough that the board
 * reads as a board at arm's length, and far enough that a piece drawn in the accent never
 * disappears into the square under it.
 */
fun boardColorsOf(style: BoardStyle, palette: ColorPalette): BoardColors {
    val dark = palette.isDark

    val (light, playable) = when (style) {
        // Buff and green: the printing every tournament draughts board uses.
        BoardStyle.Classic ->
            if (dark) Color(0xffc4b492) to Color(0xff41573a)
            else Color(0xfff0e3c6) to Color(0xff6a8a52)

        BoardStyle.Wood ->
            if (dark) Color(0xffb2926f) to Color(0xff63402a)
            else Color(0xffe3c49b) to Color(0xff96603a)

        BoardStyle.Slate ->
            if (dark) Color(0xff8d98a4) to Color(0xff3c4753)
            else Color(0xffced6de) to Color(0xff6d7d8c)

        // Tinted to the accent, but with the gap between the two squares held open by hand. This
        // is what the old `background1`/`background2` pairing was reaching for and never had the
        // range to deliver.
        BoardStyle.Accent -> {
            val hsl = hslOf(palette.accent)
            val hue = hsl[0]
            val saturation = hsl[1]
            // Saturation is capped so a vivid accent does not produce a board that competes with
            // the pieces standing on it; the luminance targets are what hold the two squares
            // apart, and they are the same for every accent.
            if (dark) {
                atLuminance(hue, min(saturation, 0.24f), 0.22f) to
                        atLuminance(hue, min(saturation, 0.40f), 0.055f)
            } else {
                atLuminance(hue, min(saturation, 0.30f), 0.62f) to
                        atLuminance(hue, min(saturation, 0.46f), 0.16f)
            }
        }
    }

    // Markers are drawn on the playable squares, which is where every piece and hint lives, so
    // that is the colour they have to survive.
    return BoardColors(
        light = light,
        dark = playable,
        marker = contrastAgainst(palette.accent, playable),
        threat = contrastAgainst(palette.red, playable)
    )
}

/** How far apart the two squares of a style are, for the test that pins every style readable. */
internal val BoardColors.squareContrast: Float get() = contrastRatio(light, dark)

/**
 * The board colours for the style the player has chosen, recomputed when either the style or the
 * palette changes.
 *
 * Read here rather than threaded through [Appearance] because the board is the only thing that
 * wants them: putting them in the appearance would mean every screen carried four colours it
 * never draws, and the saver, the system-bar callback and the uiMode listener would all have to
 * learn about a setting none of them affect.
 */
@Composable
fun rememberBoardColors(): BoardColors {
    val (colorPalette) = LocalAppearance.current
    val style by rememberPreference(boardStyleKey, BoardStyle.Classic)
    return remember(style, colorPalette) { boardColorsOf(style, colorPalette) }
}
