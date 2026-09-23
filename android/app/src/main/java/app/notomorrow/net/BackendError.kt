package app.notomorrow.net

import android.content.Context
import androidx.annotation.StringRes
import app.notomorrow.R
import kotlinx.serialization.SerializationException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * Port of `BackendError` in `NoTomorrow/Services/BackendClient.swift`.
 *
 * `Unauthorized` means "no session, or the session could not be refreshed" — the
 * UI shows its signed-out state, not a banner. `Http` carries the server's
 * `{error, message}` envelope, so callers can branch on codes such as
 * `not_paired`, `username_taken`, `ai_daily_limit` or `ai_busy`. Any code the
 * client does not know about stays an `Http`; anything that is not a
 * `BackendError` at all is normalised by [wrap] into `Server`.
 */
sealed class BackendError(message: String? = null) : Exception(message) {

    data object Unauthorized : BackendError()

    data object Network : BackendError()

    /**
     * The request left but no reply came in time (app-side timeout). Worded like [Network] everywhere
     * except the AI flows, which say "the AI took too long" (`fuel.ai.error.timeout`).
     */
    data object TimedOut : BackendError()

    data class Server(val serverMessage: String) : BackendError(serverMessage)

    /** Any other non-2xx reply. `code` is e.g. "username_taken". */
    data class Http(
        override val status: Int,
        override val code: String,
        val serverMessage: String,
    ) : BackendError(serverMessage)

    data object Decoding : BackendError()

    /** Server error code when the reply carried one ("not_paired", "ai_busy", …). */
    open val code: String? get() = null

    open val status: Int? get() = null

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            Unauthorized -> R.string.error_unauthorized
            Network, TimedOut -> R.string.error_network
            Decoding -> R.string.error_decoding
            is Server, is Http -> R.string.error_server
        }

    /** Arguments for [messageRes] — `error.server` takes the server's message. */
    val messageArgs: List<Any>
        get() = when (this) {
            is Server -> listOf(serverMessage)
            is Http -> listOf(serverMessage)
            else -> emptyList()
        }

    /** The analogue of Swift's `errorDescription`. */
    fun localizedMessage(context: Context): String =
        context.getString(messageRes, *messageArgs.toTypedArray())

    companion object {
        /** Normalises any thrown error into a `BackendError` for display. */
        fun wrap(error: Throwable): BackendError = when {
            // Cancellation is structured-concurrency plumbing, never a backend failure.
            error is CancellationException -> throw error
            error is BackendError -> error
            error is SerializationException -> Decoding
            isTimeout(error) -> TimedOut
            error is IOException -> Network
            else -> Server(error.message ?: error.javaClass.simpleName)
        }

        /**
         * An app-side timeout anywhere in the cause chain: Ktor's request timeout, a socket read or
         * connect timeout, or OkHttp's call timeout (`InterruptedIOException("timeout")`).
         */
        fun isTimeout(error: Throwable): Boolean {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                when {
                    current is HttpRequestTimeoutException -> return true
                    current is SocketTimeoutException -> return true
                    current is ConnectTimeoutException -> return true
                    current is InterruptedIOException && current.message == "timeout" -> return true
                }
                current = current.cause?.takeIf { it !== current }
                depth += 1
            }
            return false
        }
    }
}
