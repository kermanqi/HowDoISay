package com.howdoisay.hdis.overlay

import com.howdoisay.hdis.domain.ExpressionError
import com.howdoisay.hdis.domain.ResultState
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayReducerTest {
    @Test fun `follows the recording lifecycle`() {
        val listening = OverlayReducer.reduce(ResultState.Idle, OverlayAction.Start)
        val creating = OverlayReducer.reduce(listening, OverlayAction.Stop)
        val success = OverlayReducer.reduce(creating, OverlayAction.Completed("Can I pay here?"))
        assertEquals(ResultState.Success("Can I pay here?"), success)
    }

    @Test fun `ignores duplicate start while listening`() {
        assertEquals(ResultState.Listening, OverlayReducer.reduce(ResultState.Listening, OverlayAction.Start))
    }

    @Test fun `converts a pipeline failure into an error state`() {
        assertEquals(
            ResultState.Failure(ExpressionError.NetworkUnavailable),
            OverlayReducer.reduce(ResultState.Creating, OverlayAction.Failed(ExpressionError.NetworkUnavailable))
        )
    }
}
