package com.surenjanath.crownfoundry.ui.styling

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.surenjanath.crownfoundry.enums.AccentColor
import com.surenjanath.crownfoundry.enums.ColorPaletteMode
import com.surenjanath.crownfoundry.enums.ColorPaletteName

@Immutable
data class ColorPalette(
    val background0: Color,
    val background1: Color,
    val background2: Color,
    val accent: Color,
    val onAccent: Color,
    val red: Color = Color(0xffbf4040),
    val blue: Color = Color(0xff4472cf),
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val isDark: Boolean
) {
    companion object : Saver<ColorPalette, List<Any>> {
        private const val DEFAULT = 0
        private const val PURE_BLACK = 1
        private const val TINTED = 2

        override fun restore(value: List<Any>): ColorPalette {
            val kind = value[0] as Int
            val isDark = value[1] as Boolean
            val accent = Color(value[2] as Int)

            return when (kind) {
                PURE_BLACK -> PureBlackColorPalette.accented(accent)
                TINTED -> tintedColorPaletteOf(accent.hsl, isDark)
                else -> (if (isDark) DefaultDarkColorPalette else DefaultLightColorPalette)
                    .accented(accent)
            }
        }

        override fun SaverScope.save(value: ColorPalette) = listOf(
            when {
                value.background0 == PureBlackColorPalette.background0 -> PURE_BLACK
                value.background0 == DefaultDarkColorPalette.background0 ||
                        value.background0 == DefaultLightColorPalette.background0 -> DEFAULT

                else -> TINTED
            },
            value.isDark,
            value.accent.toArgb()
        )
    }
}

val DefaultDarkColorPalette = ColorPalette(
    background0 = Color(0xff16171d),
    background1 = Color(0xff1f2029),
    background2 = Color(0xff2b2d3b),
    text = Color(0xffe1e1e2),
    textSecondary = Color(0xffa3a4a6),
    textDisabled = Color(0xff6f6f73),
    accent = Color(0xffff6600),
    onAccent = Color.White,
    isDark = true
)

val DefaultLightColorPalette = ColorPalette(
    background0 = Color(0xfffdfdfe),
    background1 = Color(0xfff8f8fc),
    background2 = Color(0xffeaeaf5),
    text = Color(0xff212121),
    textSecondary = Color(0xff656566),
    textDisabled = Color(0xff9d9d9d),
    accent = Color(0xffff6600),
    onAccent = Color.White,
    isDark = false
)

val PureBlackColorPalette = DefaultDarkColorPalette.copy(
    background0 = Color.Black,
    background1 = Color.Black,
    background2 = Color.Black
)

fun colorPaletteOf(
    colorPaletteName: ColorPaletteName,
    colorPaletteMode: ColorPaletteMode,
    isSystemInDarkMode: Boolean,
    accentColor: AccentColor = AccentColor.Orange
): ColorPalette {
    val isDark = when (colorPaletteMode) {
        ColorPaletteMode.Light -> false
        ColorPaletteMode.Dark -> true
        ColorPaletteMode.System -> isSystemInDarkMode
    }

    return when (colorPaletteName) {
        ColorPaletteName.Default -> (if (isDark) DefaultDarkColorPalette else DefaultLightColorPalette)
            .accented(accentColor.color)

        ColorPaletteName.Tinted -> tintedColorPaletteOf(accentColor.color.hsl, isDark)
        ColorPaletteName.PureBlack -> PureBlackColorPalette.accented(accentColor.color)
    }
}

/**
 * Bends the whole palette towards the hue of the accent, the way ViMusic tints itself after the
 * artwork of the song being played - only here the source is the accent the reader picked.
 */
fun tintedColorPaletteOf(hsl: FloatArray, isDark: Boolean): ColorPalette =
    (if (isDark) DefaultDarkColorPalette else DefaultLightColorPalette).copy(
        background0 = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.1f), if (isDark) 0.10f else 0.925f),
        background1 = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.3f), if (isDark) 0.15f else 0.90f),
        background2 = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.4f), if (isDark) 0.2f else 0.85f),
        accent = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.6f), 0.5f),
        text = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.02f), if (isDark) 0.88f else 0.12f),
        textSecondary = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.1f), if (isDark) 0.65f else 0.40f),
        textDisabled = Color.hsl(hsl[0], hsl[1].coerceAtMost(0.2f), if (isDark) 0.40f else 0.65f)
    )

private fun ColorPalette.accented(accent: Color) = copy(accent = accent)

val Color.hsl: FloatArray
    get() = FloatArray(3).apply { ColorUtils.colorToHSL(toArgb(), this) }

inline val ColorPalette.shimmer: Color
    get() = if (isDark) Color(0xff838383) else Color(0xffb0b0b0)

inline val ColorPalette.primaryButton: Color
    get() = if (background0 == PureBlackColorPalette.background0) Color(0xFF272727) else background2

/** The colour of an upvote count, the closest thing Hacker News has to a brand colour. */
inline val ColorPalette.upvote: Color
    get() = accent

inline val ColorPalette.overlay: Color
    get() = PureBlackColorPalette.background0.copy(alpha = 0.75f)

inline val ColorPalette.onOverlay: Color
    get() = PureBlackColorPalette.text
