package com.surenjanath.crownfoundry.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

const val colorPaletteNameKey = "colorPaletteName"
const val colorPaletteModeKey = "colorPaletteMode"
const val accentColorKey = "accentColor"
const val thumbnailRoundnessKey = "thumbnailRoundness"
const val useSystemFontKey = "useSystemFont"
const val applyFontPaddingKey = "applyFontPadding"
const val textSizeKey = "textSize"

const val homeScreenTabIndexKey = "homeScreenTabIndex"
const val insightsScreenTabIndexKey = "insightsScreenTabIndex"

/** Where the Django referee lives. 10.0.2.2 is the host machine as the emulator sees it. */
const val backendUrlKey = "backendUrl"
const val defaultBackendUrl = "http://10.0.2.2:8000"

/** Stable identity for this install, so the AI can model one opponent across matches. */
const val playerIdKey = "playerId"

const val difficultyKey = "difficulty"
const val activeMatchIdKey = "activeMatchId"

/**
 * Whether the resumable match is a pass-and-play one.
 *
 * Kept beside the id rather than derived from it: an offline match id says where the game is
 * stored, not who was playing, and resuming a two-player game against the engine would have it
 * answer for a person who had simply walked away from the phone.
 */
const val activeMatchPassAndPlayKey = "activeMatchPassAndPlay"

const val showLegalMovesKey = "showLegalMoves"
const val showReasoningKey = "showReasoning"
const val showEvaluationKey = "showEvaluation"
const val hapticFeedbackKey = "hapticFeedback"

const val flyingKingsKey = "flyingKings"
const val menCaptureBackwardsKey = "menCaptureBackwards"
const val mandatoryCaptureKey = "mandatoryCapture"

inline fun <reified T : Enum<T>> SharedPreferences.getEnum(
    key: String,
    defaultValue: T
): T =
    getString(key, null)?.let {
        try {
            enumValueOf<T>(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: defaultValue

inline fun <reified T : Enum<T>> SharedPreferences.Editor.putEnum(
    key: String,
    value: T
): SharedPreferences.Editor =
    putString(key, value.name)

val Context.preferences: SharedPreferences
    get() = getSharedPreferences("preferences", Context.MODE_PRIVATE)

@Composable
fun rememberPreference(key: String, defaultValue: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    return remember {
        mutableStatePreferenceOf(context.preferences.getBoolean(key, defaultValue)) {
            context.preferences.edit { putBoolean(key, it) }
        }
    }
}

@Composable
fun rememberPreference(key: String, defaultValue: Int): MutableState<Int> {
    val context = LocalContext.current
    return remember {
        mutableStatePreferenceOf(context.preferences.getInt(key, defaultValue)) {
            context.preferences.edit { putInt(key, it) }
        }
    }
}

@Composable
fun rememberPreference(key: String, defaultValue: String): MutableState<String> {
    val context = LocalContext.current
    return remember {
        mutableStatePreferenceOf(context.preferences.getString(key, null) ?: defaultValue) {
            context.preferences.edit { putString(key, it) }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberPreference(key: String, defaultValue: T): MutableState<T> {
    val context = LocalContext.current
    return remember {
        mutableStatePreferenceOf(context.preferences.getEnum(key, defaultValue)) {
            context.preferences.edit { putEnum(key, it) }
        }
    }
}

inline fun <T> mutableStatePreferenceOf(
    value: T,
    crossinline onStructuralInequality: (newValue: T) -> Unit
) =
    mutableStateOf(
        value = value,
        policy = object : SnapshotMutationPolicy<T> {
            override fun equivalent(a: T, b: T): Boolean {
                val areEquals = a == b
                if (!areEquals) onStructuralInequality(b)
                return areEquals
            }
        })
