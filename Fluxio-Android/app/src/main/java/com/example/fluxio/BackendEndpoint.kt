package com.example.fluxio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object BackendEndpoint {
    fun parse(configuredValue: String): HttpUrl {
        val url = configuredValue.trim().toHttpUrl()
        require(
            url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null
        ) { "BACKEND_BASE_URL must be a base URL without credentials, query, or fragment." }
        return url
    }
}
