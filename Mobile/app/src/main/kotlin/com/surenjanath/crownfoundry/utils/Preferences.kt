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
import com.surenjanath.crownfoundry.BuildConfig

const val colorPaletteNameKey = "colorPaletteName"
const val colorPaletteModeKey = "colorPaletteMode"
const val accentColorKey = "accentColor"
const val thumbnailRoundnessKey = "thumbnailRoundness"
const val useSystemFontKey = "useSystemFont"
const val applyFontPaddingKey = "applyFontPadding"
const val textSizeKey = "textSize"

const val homeScreenTabIndexKey = "homeScreenTabIndex"
const val insightsScreenTabIndexKey = "insightsScreenTabIndex"

/** Where the Django referee lives, until the player says otherwise in Settings. */
const val backendUrlKey = "backendUrl"

/**
 * The referee this build ships pointed at, set by `crownfoundry.backendUrl` at build time.
 *
 * A debug build defaults to `http://10.0.2.2:8000`, the emulator's route to the developer's
 * machine. A release build has to name a real host, because that default is unreachable from a
 * real phone and would make every first run look like a broken app.
 */
val defaultBackendUrl: String = BuildConfig.DEFAULT_BACKEND_URL

/** The build property value that means "this build ships without a server". */
const val noBackend = "none"

/**
 * Whether this build ships pointed at a server at all.
 *
 * `crownfoundry.backendUrl=none` publishes the bundled engine as the whole product: no sync, no
 * downloaded policy, no analytics from a referee. Everything offline play already does still
 * works, because none of it needed the network in the first place.
 *
 * It is a *default*, not a prohibition - a player who has their own referee can still type its
 * address into Settings, and [effectiveBackendUrl] will start using it.
 */
val backendConfigured: Boolean = defaultBackendUrl != noBackend

/**
 * The address to actually use, or `null` when there is no server to talk to.
 *
 * `null` is the whole point: it is what stops an offline-only build from spending every screen's
 * first moment resolving a hostname that was never meant to exist. Without it the build default
 * would be taken literally, and `http://none/api/...` fails in a way the offline fallback does
 * not even recognise as a connectivity problem.
 */
fun effectiveBackendUrl(stored: String?): String? {
    val trimmed = stored?.trim().orEmpty()
    if (trimmed.isNotEmpty() && trimmed != noBackend) return trimmed
    return if (backendConfigured) defaultBackendUrl else null
}

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
