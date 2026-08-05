package com.t212widgets.api

import android.content.Context
import android.util.JsonReader
import com.t212widgets.core.AuthScheme
import com.t212widgets.core.Environment
import com.t212widgets.core.SecureStore
import com.t212widgets.core.authScheme
import com.t212widgets.core.environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** What went wrong, in terms the widget can render in one short line. */
sealed class ApiError(val message: String) {
    object NoKey : ApiError("No API key set — open the app")
    object Unauthorised : ApiError("API key rejected (401)")
    object Forbidden : ApiError("Key lacks the required scope (403)")
    class RateLimited(val retryAfterSec: Int?) : ApiError("Rate limited — backing off")
    class Http(val code: Int) : ApiError("Server error $code")
    class Network(val detail: String) : ApiError("Offline or unreachable")
    class Parse(val detail: String) : ApiError("Unexpected response")
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: ApiError) : ApiResult<Nothing>()
}

/**
 * Minimal Trading 212 REST client built on [HttpsURLConnection].
 *
 * Deliberately dependency-free: an HTTP stack the size of OkHttp buys nothing here and a
 * widget process wants to start fast. TLS is left entirely to the platform (system trust
 * store, no custom trust managers, no cleartext fallback — see `network_security_config`).
 *
 * The API key is read from [SecureStore] per request, used to set the header, and never
 * held in a field, never logged, and never attached to an exception message.
 */
class T212Client(private val context: Context) {

    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 15_000

    val baseUrl: String get() = context.environment.baseUrl

    suspend fun accountCash(): ApiResult<AccountCash> =
        getJsonObject("/api/v0/equity/account/cash").map(AccountCash::fromJson)

    suspend fun accountInfo(): ApiResult<AccountInfo> =
        getJsonObject("/api/v0/equity/account/info").map(AccountInfo::fromJson)

    suspend fun portfolio(): ApiResult<List<Position>> =
        getJsonArray("/api/v0/equity/portfolio").map(Position::listFromJson)

    /**
     * Probes the stored key against every [AuthScheme] and returns the first that the server
     * accepts, together with the account info it returned. Used by "Test connection" so the
     * app self-heals if Trading 212 changes the header convention.
     */
    suspend fun detectAuthScheme(
        apiKey: String,
        environment: Environment,
    ): ApiResult<Pair<AuthScheme, AccountInfo>> = withContext(Dispatchers.IO) {
        var lastError: ApiError = ApiError.Network("not attempted")
        for (scheme in AuthScheme.entries) {
            when (val r = request("/api/v0/equity/account/info", apiKey, scheme, environment)) {
                is ApiResult.Ok -> {
                    val parsed = runCatching { AccountInfo.fromJson(JSONObject(r.value)) }
                    return@withContext parsed.fold(
                        onSuccess = { ApiResult.Ok(scheme to it) },
                        onFailure = { ApiResult.Err(ApiError.Parse(it.javaClass.simpleName)) },
                    )
                }
                is ApiResult.Err -> {
                    lastError = r.error
                    // A rate limit or an outage says nothing about the scheme; stop probing
                    // rather than burning the remaining quota on the other two.
                    if (r.error !is ApiError.Unauthorised && r.error !is ApiError.Forbidden) {
                        return@withContext ApiResult.Err(r.error)
                    }
                }
            }
        }
        ApiResult.Err(lastError)
    }

    /**
     * Streams `/equity/metadata/instruments` and pulls out display names for the tickers we
     * care about. The payload is several megabytes and this endpoint is rate-limited to one
     * call per 50 seconds, so it is parsed with a streaming reader instead of being loaded
     * into a JSONArray, and callers cache the result for days.
     */
    suspend fun instrumentNames(wanted: Set<String>): ApiResult<Map<String, InstrumentMeta>> =
        withContext(Dispatchers.IO) {
            val apiKey = SecureStore.readApiKey(context) ?: return@withContext ApiResult.Err(ApiError.NoKey)
            val scheme = context.authScheme
            val connection = runCatching {
                openConnection("/api/v0/equity/metadata/instruments", apiKey, scheme, context.environment)
            }.getOrElse { return@withContext ApiResult.Err(ApiError.Network(it.javaClass.simpleName)) }
            connection
                .use { conn ->
                    val code = runCatching { conn.responseCode }.getOrElse {
                        return@withContext ApiResult.Err(ApiError.Network(it.javaClass.simpleName))
                    }
                    if (code != 200) return@withContext ApiResult.Err(errorFor(code, conn))
                    val out = HashMap<String, InstrumentMeta>(wanted.size)
                    runCatching {
                        JsonReader(InputStreamReader(decodedStream(conn), Charsets.UTF_8)).use { reader ->
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var ticker: String? = null
                                var name: String? = null
                                var shortName: String? = null
                                var currency: String? = null
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "ticker" -> ticker = reader.nextStringOrNull()
                                        "name" -> name = reader.nextStringOrNull()
                                        "shortName" -> shortName = reader.nextStringOrNull()
                                        "currencyCode" -> currency = reader.nextStringOrNull()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (ticker != null && ticker in wanted) {
                                    out[ticker] = InstrumentMeta(
                                        name = name ?: shortName ?: ticker,
                                        shortName = shortName ?: ticker.substringBefore('_'),
                                        currencyCode = currency ?: "",
                                    )
                                }
                            }
                            reader.endArray()
                        }
                    }.fold(
                        onSuccess = { ApiResult.Ok(out) },
                        onFailure = { ApiResult.Err(ApiError.Parse(it.javaClass.simpleName)) },
                    )
                }
        }

    // ---------------------------------------------------------------- internals

    private suspend fun getJsonObject(path: String): ApiResult<JSONObject> =
        getBody(path).flatMap {
            runCatching { JSONObject(it) }.fold(
                onSuccess = { o -> ApiResult.Ok(o) },
                onFailure = { e -> ApiResult.Err(ApiError.Parse(e.javaClass.simpleName)) },
            )
        }

    private suspend fun getJsonArray(path: String): ApiResult<JSONArray> =
        getBody(path).flatMap {
            runCatching { JSONArray(it) }.fold(
                onSuccess = { a -> ApiResult.Ok(a) },
                onFailure = { e -> ApiResult.Err(ApiError.Parse(e.javaClass.simpleName)) },
            )
        }

    private suspend fun getBody(path: String): ApiResult<String> = withContext(Dispatchers.IO) {
        val apiKey = SecureStore.readApiKey(context) ?: return@withContext ApiResult.Err(ApiError.NoKey)
        request(path, apiKey, context.authScheme, context.environment)
    }

    private fun request(
        path: String,
        apiKey: String,
        scheme: AuthScheme,
        environment: Environment,
    ): ApiResult<String> = try {
        openConnection(path, apiKey, scheme, environment).use { conn ->
            when (val code = conn.responseCode) {
                in 200..299 -> ApiResult.Ok(decodedStream(conn).bufferedReader().use(BufferedReader::readText))
                else -> ApiResult.Err(errorFor(code, conn))
            }
        }
    } catch (e: Exception) {
        // Exception messages from the URL stack can contain the request URL but never the
        // headers, so this is safe to surface — still, only the class name is kept.
        ApiResult.Err(ApiError.Network(e.javaClass.simpleName))
    }

    private fun openConnection(
        path: String,
        apiKey: String,
        scheme: AuthScheme,
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
        scheme.headers(apiKey).forEach { (k, v) -> conn.setRequestProperty(k, v) }
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

    private fun errorFor(code: Int, conn: HttpURLConnection): ApiError {
        runCatching { conn.errorStream?.close() }
        return when (code) {
            401 -> ApiError.Unauthorised
            403 -> ApiError.Forbidden
            429 -> ApiError.RateLimited(conn.getHeaderField("Retry-After")?.toIntOrNull())
            else -> ApiError.Http(code)
        }
    }
}

data class InstrumentMeta(val name: String, val shortName: String, val currencyCode: String)

private fun JsonReader.nextStringOrNull(): String? =
    if (peek() == android.util.JsonToken.NULL) {
        nextNull()
        null
    } else {
        nextString()
    }

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}

private inline fun <T, R> ApiResult<T>.flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> =
    when (this) {
        is ApiResult.Ok -> transform(value)
        is ApiResult.Err -> this
    }
