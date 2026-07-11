package com.howdoisay.hdis.overlay

import com.howdoisay.hdis.domain.ExpressionError
import com.howdoisay.hdis.domain.ResultState

sealed interface OverlayAction {
    data object Start : OverlayAction
    data object Stop : OverlayAction
    data class Completed(val english: String) : OverlayAction
    data class Failed(val error: ExpressionError) : OverlayAction
    data object Cancel : OverlayAction
    data object Close : OverlayAction
}

object OverlayReducer {
    fun reduce(current: ResultState, action: OverlayAction): ResultState = when (action) {
        OverlayAction.Start -> if (current is ResultState.Idle || current is ResultState.Success || current is ResultState.Failure) {
            ResultState.Listening
        } else current
        OverlayAction.Stop -> if (current is ResultState.Listening) ResultState.Creating else current
        is OverlayAction.Completed -> if (current is ResultState.Creating) ResultState.Success(action.english) else current
        is OverlayAction.Failed -> if (current is ResultState.Listening || current is ResultState.Creating) {
            ResultState.Failure(action.error)
        } else current
        OverlayAction.Cancel, OverlayAction.Close -> ResultState.Idle
    }
}
