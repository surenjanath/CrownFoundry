// rememberRipple/RippleTheme are deprecated in favour of ripple(), which only ships in the
// material and material3 artifacts. This app has its own design system and pulls in neither.
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.surenjanath.crownfoundry

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.valentinilk.shimmer.LocalShimmerTheme
import com.valentinilk.shimmer.defaultShimmerTheme
import it.vfsfitvnm.compose.persist.PersistMap
import it.vfsfitvnm.compose.persist.PersistMapOwner
import com.surenjanath.crownfoundry.enums.AccentColor
import com.surenjanath.crownfoundry.enums.ColorPaletteMode
import com.surenjanath.crownfoundry.enums.ColorPaletteName
import com.surenjanath.crownfoundry.enums.TextSize
import com.surenjanath.crownfoundry.enums.ThumbnailRoundness
import com.surenjanath.crownfoundry.ui.components.BottomSheetMenu
import com.surenjanath.crownfoundry.ui.components.LocalMenuState
import com.surenjanath.crownfoundry.ui.screens.home.HomeScreen
import com.surenjanath.crownfoundry.ui.styling.Appearance
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.colorPaletteOf
import com.surenjanath.crownfoundry.ui.styling.typographyOf
import com.surenjanath.crownfoundry.utils.accentColorKey
import com.surenjanath.crownfoundry.utils.applyFontPaddingKey
import com.surenjanath.crownfoundry.utils.colorPaletteModeKey
import com.surenjanath.crownfoundry.utils.colorPaletteNameKey
import com.surenjanath.crownfoundry.utils.getEnum
import com.surenjanath.crownfoundry.utils.isAtLeastAndroid6
import com.surenjanath.crownfoundry.utils.isAtLeastAndroid8
import com.surenjanath.crownfoundry.utils.preferences
import com.surenjanath.crownfoundry.utils.textSizeKey
import com.surenjanath.crownfoundry.utils.thumbnailRoundnessKey
import com.surenjanath.crownfoundry.utils.useSystemFontKey

class MainActivity : ComponentActivity(), PersistMapOwner {
    override lateinit var persistMap: PersistMap

    @OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        persistMap = lastCustomNonConfigurationInstance as? PersistMap ?: PersistMap()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val isSystemInDarkTheme = isSystemInDarkTheme()

            var appearance by rememberSaveable(
                isSystemInDarkTheme,
                stateSaver = Appearance.Companion
            ) {
                with(preferences) {
                    val colorPaletteName = getEnum(colorPaletteNameKey, ColorPaletteName.Default)
                    val colorPaletteMode = getEnum(colorPaletteModeKey, ColorPaletteMode.System)
                    val accentColor = getEnum(accentColorKey, AccentColor.Orange)
                    val thumbnailRoundness =
                        getEnum(thumbnailRoundnessKey, ThumbnailRoundness.Medium)

                    val useSystemFont = getBoolean(useSystemFontKey, false)
                    val applyFontPadding = getBoolean(applyFontPaddingKey, false)
                    val textSize = getEnum(textSizeKey, TextSize.Medium)

                    val colorPalette = colorPaletteOf(
                        colorPaletteName,
                        colorPaletteMode,
                        isSystemInDarkTheme,
                        accentColor
                    )

                    setSystemBarAppearance(colorPalette.isDark)

                    mutableStateOf(
                        Appearance(
                            colorPalette = colorPalette,
                            typography = typographyOf(
                                colorPalette.text,
                                useSystemFont,
                                applyFontPadding,
                                textSize.scale
                            ),
                            thumbnailShape = thumbnailRoundness.shape()
                        )
                    )
                }
            }

            // The activity handles uiMode changes itself, so the palette has to be recomputed
            // by hand whenever the system flips between light and dark.
            LaunchedEffect(isSystemInDarkTheme) {
                with(preferences) {
                    val colorPalette = colorPaletteOf(
                        getEnum(colorPaletteNameKey, ColorPaletteName.Default),
                        getEnum(colorPaletteModeKey, ColorPaletteMode.System),
                        isSystemInDarkTheme,
                        getEnum(accentColorKey, AccentColor.Orange)
                    )

                    setSystemBarAppearance(colorPalette.isDark)

                    appearance = appearance.copy(
                        colorPalette = colorPalette,
                        typography = appearance.typography.copy(colorPalette.text)
                    )
                }
            }

            DisposableEffect(isSystemInDarkTheme) {
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                        when (key) {
                            colorPaletteNameKey, colorPaletteModeKey, accentColorKey -> {
                                val colorPalette = colorPaletteOf(
                                    sharedPreferences.getEnum(
                                        colorPaletteNameKey,
                                        ColorPaletteName.Default
                                    ),
                                    sharedPreferences.getEnum(
                                        colorPaletteModeKey,
                                        ColorPaletteMode.System
                                    ),
                                    isSystemInDarkTheme,
                                    sharedPreferences.getEnum(accentColorKey, AccentColor.Orange)
                                )

                                setSystemBarAppearance(colorPalette.isDark)

                                appearance = appearance.copy(
                                    colorPalette = colorPalette,
                                    typography = appearance.typography.copy(colorPalette.text)
                                )
                            }

                            thumbnailRoundnessKey -> {
                                val thumbnailRoundness =
                                    sharedPreferences.getEnum(key, ThumbnailRoundness.Medium)

                                appearance = appearance.copy(
                                    thumbnailShape = thumbnailRoundness.shape()
                                )
                            }

                            useSystemFontKey, applyFontPaddingKey, textSizeKey -> {
                                appearance = appearance.copy(
                                    typography = typographyOf(
                                        appearance.colorPalette.text,
                                        sharedPreferences.getBoolean(useSystemFontKey, false),
                                        sharedPreferences.getBoolean(applyFontPaddingKey, false),
                                        sharedPreferences.getEnum(textSizeKey, TextSize.Medium).scale
                                    )
                                )
                            }
                        }
                    }

                with(preferences) {
                    registerOnSharedPreferenceChangeListener(listener)
                    onDispose { unregisterOnSharedPreferenceChangeListener(listener) }
                }
            }

            val rippleTheme =
                remember(appearance.colorPalette.text, appearance.colorPalette.isDark) {
                    object : RippleTheme {
                        @Composable
                        override fun defaultColor(): Color = RippleTheme.defaultRippleColor(
                            contentColor = appearance.colorPalette.text,
                            lightTheme = !appearance.colorPalette.isDark
                        )

                        @Composable
                        override fun rippleAlpha(): RippleAlpha = RippleTheme.defaultRippleAlpha(
                            contentColor = appearance.colorPalette.text,
                            lightTheme = !appearance.colorPalette.isDark
                        )
                    }
                }

            val shimmerTheme = remember {
                defaultShimmerTheme.copy(
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 800,
                            easing = LinearEasing,
                            delayMillis = 250
                        ),
                        repeatMode = RepeatMode.Restart
                    ),
                    shaderColors = listOf(
                        Color.Unspecified.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.50f),
                        Color.Unspecified.copy(alpha = 0.25f)
                    )
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(appearance.colorPalette.background0)
            ) {
                CompositionLocalProvider(
                    LocalAppearance provides appearance,
                    LocalIndication provides rememberRipple(bounded = true),
                    LocalRippleTheme provides rippleTheme,
                    LocalShimmerTheme provides shimmerTheme,
                    LocalWindowInsets provides WindowInsets.systemBars,
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    HomeScreen()

                    BottomSheetMenu(
                        state = LocalMenuState.current,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }

    }

    override fun onRetainCustomNonConfigurationInstance() = persistMap

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            persistMap.clear()
        }

        super.onDestroy()
    }

    private fun setSystemBarAppearance(isDark: Boolean) {
        with(WindowCompat.getInsetsController(window, window.decorView.rootView)) {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        if (!isAtLeastAndroid6) {
            window.statusBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }

        if (!isAtLeastAndroid8) {
            window.navigationBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }
}

val LocalWindowInsets = staticCompositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }
