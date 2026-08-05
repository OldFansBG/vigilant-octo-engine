package com.t212widgets.core

import android.util.Base64

/**
 * What Trading 212 hands you when you generate an API key.
 *
 * Current keys come as a **pair** — an API Key and an API Secret — and authenticate with
 * HTTP Basic, exactly as the official quickstart shows:
 *
 * ```
 * Authorization: Basic base64("<API_KEY>:<API_SECRET>")
 * ```
 *
 * Older keys were a single opaque string sent raw in the `Authorization` header. Trading 212
 * still accepts those (their endpoint docs list `legacyApiKeyHeader` alongside
 * `authWithSecretKey`), so both are supported here and [isLegacy] decides which to send.
 */
data class Credentials(val apiKey: String, val apiSecret: String) {

    /** A key with no secret: the older single-string form. */
    val isLegacy: Boolean get() = apiSecret.isEmpty()

    val isEmpty: Boolean get() = apiKey.isEmpty()

    /**
     * The `Authorization` header value.
     *
     * `NO_WRAP` matters: Base64 with line breaks in it produces a malformed header, which is
     * why the official curl example pipes through `tr -d '\n'`.
     */
    fun authorizationHeader(): String = if (isLegacy) {
        apiKey
    } else {
        "Basic " + Base64.encodeToString(
            "$apiKey:$apiSecret".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
    }

    companion object {
        /**
         * Removes anything a clipboard may have carried along.
         *
         * Copying from a browser or a messaging app routinely picks up a trailing newline, a
         * non-breaking space, or a zero-width character. None are visible in a password
         * field and every one of them turns a good credential into a 401.
         */
        fun sanitise(raw: String): String = raw.filterNot { c ->
            c.isWhitespace() ||
                c == '​' || // zero-width space
                c == '‌' || // zero-width non-joiner
                c == '‍' || // zero-width joiner
                c == '﻿' // byte-order mark
        }

        /**
         * Builds credentials from whatever the user pasted.
         *
         * Accepts the two fields separately, and also tolerates the whole thing pasted into
         * the key box as `key:secret` — that is the shape the docs show, so people do paste
         * it that way.
         */
        fun of(rawKey: String, rawSecret: String): Credentials {
            val key = sanitise(rawKey)
            val secret = sanitise(rawSecret)
            if (secret.isEmpty() && key.count { it == ':' } == 1) {
                return Credentials(key.substringBefore(':'), key.substringAfter(':'))
            }
            return Credentials(key, secret)
        }
    }
}
