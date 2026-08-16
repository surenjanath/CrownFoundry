package com.surenjanath.crownfoundry.api

/**
 * Carried over from the shape ViMusic's networking used: a call is pending, or it produced a
 * value, or it produced a failure - and the UI renders all three without a nullable in sight.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val reason: ApiError) : Outcome<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value
    val errorOrNull: ApiError? get() = (this as? Failure)?.reason
    val isSuccess: Boolean get() = this is Success
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (ApiError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(reason)
    return this
}

/**
 * Everything that can go wrong between a tap and the referee's answer, in the terms the UI needs
 * to say something useful - not in the terms Ktor throws.
 */
sealed class ApiError(val message: String) {
    /** No route to the backend at all: wrong URL, laptop asleep, `runserver` not running. */
    class Unreachable(val url: String) : ApiError("Cannot reach the referee at $url")

    /** The referee answered, and said no. `code` is the backend's machine-readable error code. */
    class Rejected(
        val status: Int,
        val code: String,
        val detail: String,
        val legalMoves: List<MoveDto> = emptyList()
    ) : ApiError(detail.ifEmpty { code })

    /** The move was not legal. Carries the moves that are, so the board can correct itself. */
    class IllegalMove(val legalMoves: List<MoveDto>) : ApiError("That move is not legal")

    /** The answer did not parse: a backend of a different vintage. */
    class Malformed(val detail: String) : ApiError("The referee's answer made no sense")

    /** The brain is down - Ollama, the policy, or the training worker. */
    class BrainUnavailable(val detail: String) : ApiError("The opponent is not thinking right now")

    class Timeout(val seconds: Int) : ApiError("The referee took longer than ${seconds}s")
}
