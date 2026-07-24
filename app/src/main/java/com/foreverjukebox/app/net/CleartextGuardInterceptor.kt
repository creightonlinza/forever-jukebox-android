package com.foreverjukebox.app.net

import java.net.Proxy
import java.net.UnknownServiceException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor enforcing [CleartextPolicy] on every connection hop, including each
 * redirect. It judges http requests by the socket address actually connected to — not the
 * hostname — so DNS tricks cannot route cleartext to the public internet, while split-DNS names
 * that resolve to private addresses still work. Must be registered with addNetworkInterceptor:
 * application interceptors run once per call, before any connection exists.
 */
object CleartextGuardInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = chain.connection()?.route()
        val allowed = CleartextPolicy.isCleartextRequestAllowed(
            isHttps = request.url.isHttps,
            viaProxy = route == null || route.proxy.type() != Proxy.Type.DIRECT,
            address = route?.socketAddress?.address
        )
        if (!allowed) {
            throw UnknownServiceException(
                "CLEARTEXT communication to ${request.url.host} not permitted: " +
                    "http is only supported for private/local addresses"
            )
        }
        return chain.proceed(request)
    }
}
