package com.surenjanath.crownfoundry.ui.screens.insights

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PerformanceDto

data class InsightsState(
    val isLoading: Boolean = true,
    val performance: PerformanceDto? = null,
    val error: ApiError? = null
) {
    /** Loaded, reachable, and the opponent has simply never played anyone. */
    val isEmpty: Boolean
        get() = !isLoading && error == null && (performance?.summary?.totalMatches ?: 0) == 0
}

class InsightsStateHolder(private val api: CheckersApi) {
    var state by mutableStateOf(InsightsState())
        private set

    suspend fun load() {
        state = state.copy(isLoading = true, error = null)

        state = when (val outcome = api.performance()) {
            is Outcome.Success -> InsightsState(
                isLoading = false,
                performance = outcome.value,
                error = null
            )

            is Outcome.Failure -> InsightsState(
                isLoading = false,
                performance = null,
                error = outcome.reason
            )
        }
    }
}
