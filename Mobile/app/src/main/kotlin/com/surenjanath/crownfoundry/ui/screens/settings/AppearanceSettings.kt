package com.surenjanath.crownfoundry.ui.screens.settings

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.enums.AccentColor
import com.surenjanath.crownfoundry.enums.ColorPaletteMode
import com.surenjanath.crownfoundry.enums.ColorPaletteName
import com.surenjanath.crownfoundry.enums.TextSize
import com.surenjanath.crownfoundry.enums.ThumbnailRoundness
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.accentColorKey
import com.surenjanath.crownfoundry.utils.applyFontPaddingKey
import com.surenjanath.crownfoundry.utils.colorPaletteModeKey
import com.surenjanath.crownfoundry.utils.colorPaletteNameKey
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.textSizeKey
import com.surenjanath.crownfoundry.utils.thumbnailRoundnessKey
import com.surenjanath.crownfoundry.utils.useSystemFontKey

@ExperimentalAnimationApi
@Composable
fun AppearanceSettings() {
    val (colorPalette) = LocalAppearance.current

    var colorPaletteName by rememberPreference(colorPaletteNameKey, ColorPaletteName.Default)
    var colorPaletteMode by rememberPreference(colorPaletteModeKey, ColorPaletteMode.System)
    var accentColor by rememberPreference(accentColorKey, AccentColor.Orange)
    var thumbnailRoundness by rememberPreference(thumbnailRoundnessKey, ThumbnailRoundness.Medium)
    var textSize by rememberPreference(textSizeKey, TextSize.Medium)
    var useSystemFont by rememberPreference(useSystemFontKey, false)
    var applyFontPadding by rememberPreference(applyFontPaddingKey, false)

    Column(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                LocalWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
    ) {
        Header(title = "Appearance")

        SettingsEntryGroupText(title = "COLORS")

        EnumValueSelectorSettingsEntry(
            title = "Theme",
            selectedValue = colorPaletteName,
            onValueSelected = { colorPaletteName = it },
            valueText = {
                when (it) {
                    ColorPaletteName.Default -> "Default"
                    ColorPaletteName.Tinted -> "Tinted by accent"
                    ColorPaletteName.PureBlack -> "Pure black"
                }
            }
        )

        EnumValueSelectorSettingsEntry(
            title = "Theme mode",
            selectedValue = colorPaletteMode,
            isEnabled = colorPaletteName != ColorPaletteName.PureBlack,
            onValueSelected = { colorPaletteMode = it }
        )

        EnumValueSelectorSettingsEntry(
            title = "Accent",
            selectedValue = accentColor,
            onValueSelected = { accentColor = it },
            trailingContent = {
                Spacer(
                    modifier = Modifier
                        .background(color = accentColor.color, shape = CircleShape)
                        .size(24.dp)
                )
            }
        )

        SettingsDescription(text = "Points, links and the selected tab take the accent colour.")

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "SHAPES")

        EnumValueSelectorSettingsEntry(
            title = "Corner roundness",
            selectedValue = thumbnailRoundness,
            onValueSelected = { thumbnailRoundness = it },
            trailingContent = {
                Spacer(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = colorPalette.accent,
                            shape = thumbnailRoundness.shape()
                        )
                        .background(
                            color = colorPalette.background1,
                            shape = thumbnailRoundness.shape()
                        )
                        .size(36.dp)
                )
            }
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "TEXT")

        EnumValueSelectorSettingsEntry(
            title = "Text size",
            selectedValue = textSize,
            onValueSelected = { textSize = it }
        )

        SwitchSettingEntry(
            title = "Use system font",
            text = "Use the font applied by the system",
            isChecked = useSystemFont,
            onCheckedChange = { useSystemFont = it }
        )

        SwitchSettingEntry(
            title = "Apply font padding",
            text = "Add spacing around texts",
            isChecked = applyFontPadding,
            onCheckedChange = { applyFontPadding = it }
        )
    }
}
