package com.t212widgets.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.t212widgets.core.Credentials
import com.t212widgets.core.Environment
import com.t212widgets.core.SecureStore
import com.t212widgets.core.environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * What went wrong, in terms the widget can render in one short line.
 *
 * The connectivity failures are split three ways on purpose. Lumping them together produced
 * "Offline or unreachable" on a phone that was demonstrably online, because a request that
 * merely ran slow was reported the same way as a dead network — which tells the user nothing
 * and looks like the app is broken when it is not.
 */
sealed class ApiError(val message: String) {
    object NoKey : ApiError("No API key set — open the app")
    object Unauthorised : ApiError("API key rejected (401)")
    object Forbidden : ApiError("Key is missing a permission (403)")
    class RateLimited(val retryAfterSec: Int?) : ApiError("Rate limited — backing off")
    class Http(val code: Int) : ApiError("Server error $code")

    /** The device itself has no usable network. */
    object Offline : ApiError("No internet connection")

    /** We are online; Trading 212 did not answer in time. */
    object Timeout : ApiError("Trading 212 didn't respond")

    /** Online, but the request failed for some other transport reason. */
    class Unreachable(val detail: String) : ApiError("Couldn't reach Trading 212")

    class Parse(val detail: String) : ApiError("Unexpected response")
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: ApiError) : ApiResult<Nothing>()
}

/**
 * Trading 212 public API client, built on [HttpsURLConnection].
 *
 * Deliberately dependency-free: an HTTP stack the size of OkHttp buys nothing here and a
 * widget process wants to start fast. TLS is left entirely to the platform (system trust
 * store, no custom trust managers, no cleartext fallback — see `network_security_config`).
 *
 * Endpoints used, with their documented rate limits:
 *  - `GET /api/v0/equity/account/summary` — 1 req / 5s
 *  - `GET /api/v0/equity/positions` — 1 req / 1s
 *
 * Credentials are read from [SecureStore] per request, used to build the header, and never
 * held in a field, never logged, and never attached to an exception message.
 */
class T212Client(private val context: Context) {

    // Generous enough for a cold TLS handshake on mobile data — 5s was not, and turned an
    // ordinary slow connection into a reported failure. Both calls run in parallel, so the
    // worst case is roughly readTimeoutMs, still well inside the caller's deadline (see
    // RefreshReceiver.REFRESH_DEADLINE_MS).
    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 15_000

    suspend fun accountSummary(): ApiResult<AccountSummary> =
        getObject(PATH_SUMMARY).flatMap { parse { AccountSummary.fromJson(it) } }

    suspend fun positions(): ApiResult<List<Position>> =
        getArray(PATH_POSITIONS).flatMap { parse { Position.listFromJson(it) } }

    /**
     * Works out how to reach Trading 212 with these credentials, by trying every environment
     * that could plausibly be right.
     *
     * Keys are issued per environment and cannot be used across them, and a Live key sent to
     * the demo host comes back as a flat 401 with no hint that the host is the problem —
     * which is the most common reason setup fails. Rejected requests are not counted against
     * the rate limits, so sweeping both costs nothing but a few hundred milliseconds.
     */
    suspend fun detectConnection(
        credentials: Credentials,
        preferred: Environment,
    ): ApiResult<Connection> = withContext(Dispatchers.IO) {
        if (credentials.isEmpty) return@withContext ApiResult.Err(ApiError.NoKey)

        // Try the environment the user picked first, so the common case is one request.
        val environments = listOf(preferred) + Environment.entries.filter { it != preferred }
        var bestError: ApiError = ApiError.Unreachable("not attempted")

        for (environment in environments) {
            when (val r = request(PATH_SUMMARY, credentials, environment)) {
                is ApiResult.Ok -> {
                    val summary = runCatching { AccountSummary.fromJson(JSONObject(r.value)) }
                        .getOrElse {
                            return@withContext ApiResult.Err(ApiError.Parse(it.javaClass.simpleName))
                        }
                    return@withContext ApiResult.Ok(Connection(environment, summary.currency))
                }
                is ApiResult.Err -> {
                    // Being offline or rate limited says nothing about the credentials, so
                    // stop rather than reporting a misleading "key rejected".
                    if (r.error is ApiError.Offline ||
                        r.error is ApiError.Timeout ||
                        r.error is ApiError.Unreachable ||
                        r.error is ApiError.RateLimited
                    ) {
                        return@withContext ApiResult.Err(r.error)
                    }
                    // A 403 means the credentials authenticated but lack a permission — far
                    // more actionable than yet another 401, so let it win.
                    if (bestError !is ApiError.Forbidden) bestError = r.error
                }
            }
        }
        ApiResult.Err(bestError)
    }

    // ---------------------------------------------------------------- internals

    private suspend fun getObject(path: String): ApiResult<JSONObject> =
        body(path).flatMap { parse { JSONObject(it) } }

    private suspend fun getArray(path: String): ApiResult<JSONArray> =
        body(path).flatMap { parse { JSONArray(it) } }

    private suspend fun body(path: String): ApiResult<String> = withContext(Dispatchers.IO) {
        val credentials = SecureStore.readCredentials(context)
            ?: return@withContext ApiResult.Err(ApiError.NoKey)
        if (credentials.isEmpty) return@withContext ApiResult.Err(ApiError.NoKey)
        request(path, credentials, context.environment)
    }

    private fun request(
        path: String,
        credentials: Credentials,
        environment: Environment,
    ): ApiResult<String> = try {
        openConnection(path, credentials, environment).use { conn ->
            val code = conn.responseCode
            // Read the budget off every answered request, including errors — that is how
            // the client learns the real pace rather than assuming the documented one.
            RateLimiter.observe(path, conn)
            when (code) {
                in 200..299 ->
                    ApiResult.Ok(decodedStream(conn).bufferedReader().use(BufferedReader::readText))
                else -> ApiResult.Err(errorFor(path, code, conn))
            }
        }
    } catch (e: CancellationException) {
        // Never swallow this. A cancelled coroutine is our own deadline firing, not a
        // failure of the request — catching it as one is what put a bogus "offline" banner
        // on a widget whose phone was plainly online.
        throw e
    } catch (e: Exception) {
        // Only the exception's class name is kept: messages from the URL stack can carry the
        // request URL, and nothing derived from the credentials should ever escape here.
        ApiResult.Err(transportError(e))
    }

    /** Turns a transport exception into something a user can act on. */
    private fun transportError(e: Exception): ApiError = when {
        !hasNetwork() -> ApiError.Offline
        e is SocketTimeoutException -> ApiError.Timeout
        e is UnknownHostException -> ApiError.Offline
        else -> ApiError.Unreachable(e.javaClass.simpleName)
    }

    /**
     * Whether the device plainly has no network. Used only to label a failure; a wrong
     * answer here can never block a request.
     *
     * Deliberately does **not** consult `NET_CAPABILITY_VALIDATED`. That flag means "Android
     * probed this network and reached the internet", and it is routinely false on connections
     * that work perfectly: behind a VPN, behind a DNS-level ad blocker, while revalidation is
     * in flight, and on assorted devices where the probe is blocked. Gating on it reported
     * "No internet connection" to someone browsing the web on the same phone.
     *
     * Anything short of "there is no active network at all" is treated as online, so an
     * ambiguous answer produces an honest "couldn't reach Trading 212" instead of a
     * confident lie about the user's connection.
     */
    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openConnection(
        path: String,
        credentials: Credentials,
        environment: Environment,
    ): HttpURLConnection {
        val conn = URL(environment.baseUrl + path).openConnection() as HttpURLConnection
        require(conn is HttpsURLConnection) { "refusing to send credentials over cleartext" }
        conn.requestMethod = "GET"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false // never replay the auth header to another host
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "T212Widgets/1.0 (Android)")
        conn.setRequestProperty("Authorization", credentials.authorizationHeader())
        return conn
    }

    private fun decodedStream(conn: HttpURLConnection): InputStream {
        val raw = conn.inputStream
        return if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw)
        } else {
            raw
        }
    }

    private fun errorFor(path: String, code: Int, conn: HttpURLConnection): ApiError {
        runCatching { conn.errorStream?.close() }
        return when (code) {
            401 -> ApiError.Unauthorised
            403 -> ApiError.Forbidden
            429 -> {
                val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull()
                RateLimiter.onRateLimited(path, retryAfter)
                ApiError.RateLimited(retryAfter)
            }
            else -> ApiError.Http(code)
        }
    }

    companion object {
        const val PATH_SUMMARY = "/api/v0/equity/account/summary"
        const val PATH_POSITIONS = "/api/v0/equity/positions"
    }
}

/** A working environment for the stored credentials, plus the account's currency. */
data class Connection(val environment: Environment, val currencyCode: String)

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

private inline fun <T> parse(block: () -> T): ApiResult<T> =
    runCatching(block).fold(
        onSuccess = { ApiResult.Ok(it) },
        onFailure = { ApiResult.Err(ApiError.Parse(it.javaClass.simpleName)) },
    )

private inline fun <T, R> ApiResult<T>.flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> =
    when (this) {
        is ApiResult.Ok -> transform(value)
        is ApiResult.Err -> this
    }
