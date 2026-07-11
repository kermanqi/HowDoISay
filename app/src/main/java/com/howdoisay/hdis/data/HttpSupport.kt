package com.howdoisay.hdis.data

import com.howdoisay.hdis.domain.ExpressionError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

internal suspend fun OkHttpClient.await(request: Request): Result<Response> = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(Result.success(response)) else response.close()
        }
    })
}

internal fun Throwable.toExpressionError(): ExpressionError = when (this) {
    is java.net.UnknownHostException,
    is java.net.SocketTimeoutException,
    is IOException -> ExpressionError.NetworkUnavailable
    else -> ExpressionError.ProviderFailure(message?.takeIf { it.isNotBlank() })
}

internal fun statusError(status: Int): ExpressionError = when (status) {
    401, 403 -> ExpressionError.Unauthorized
    429 -> ExpressionError.RateLimited
    else -> ExpressionError.ProviderFailure()
}
