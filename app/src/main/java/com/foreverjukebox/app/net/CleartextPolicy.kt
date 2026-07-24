package com.foreverjukebox.app.net

import java.net.InetAddress

/**
 * App-layer cleartext policy for self-hosted Server mode. Full-flavor release builds permit
 * cleartext at the platform layer (see usesCleartextTraffic in app/build.gradle.kts) so http://
 * LAN servers work; this policy restores the "no cleartext to the public internet" guarantee by
 * allowing http only to private/local destinations: loopback, RFC1918, link-local, IPv6 unique
 * local (fc00::/7), and CGNAT (100.64/10, which Tailscale assigns).
 */
object CleartextPolicy {

    /** Name suffixes conventionally reserved for private networks (mDNS, routers, RFC 8375). */
    private val LOCAL_NAME_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val MAX_OCTET = 255
    private const val CGNAT_FIRST_OCTET = 100
    private val CGNAT_SECOND_OCTET_RANGE = 64..127
    private const val ULA_PREFIX_MASK = 0xfe
    private const val ULA_PREFIX = 0xfc

    /**
     * The interceptor-side decision: https is always allowed; http is allowed only when the
     * destination address is verifiably private. Proxied http is rejected because the final
     * destination cannot be verified from the socket address.
     */
    fun isCleartextRequestAllowed(isHttps: Boolean, viaProxy: Boolean, address: InetAddress?): Boolean {
        if (isHttps) return true
        if (viaProxy) return false
        return address != null && isPrivateAddress(address)
    }

    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        return when (bytes.size) {
            IPV4_BYTES -> isCgnatAddress(bytes)
            IPV6_BYTES -> isUniqueLocalAddress(bytes)
            else -> false
        }
    }

    /**
     * Hostname-shape check for entry-time validation only: matches hosts that are recognizably
     * local (private IP literals, localhost, single-label names, and [LOCAL_NAME_SUFFIXES]).
     * Never resolves DNS, so a public-looking name that resolves privately (split DNS) is not
     * matched here — [CleartextGuardInterceptor] judges those by the connected address instead.
     */
    fun isKnownLocalHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        parseIpLiteral(normalized)?.let { return isPrivateAddress(it) }
        if (normalized == "localhost" || !normalized.contains('.')) return true
        return LOCAL_NAME_SUFFIXES.any { normalized.endsWith(it) }
    }

    /**
     * Parses [host] as an IP literal without ever resolving DNS: only shapes that cannot be a
     * DNS name (bracketed/colon IPv6, strict dotted-quad IPv4) are handed to [InetAddress].
     */
    private fun parseIpLiteral(host: String): InetAddress? {
        val candidate = host.removeSurrounding("[", "]")
        val isIpv4Literal = IPV4_LITERAL.matches(candidate) &&
            candidate.split('.').all { it.toInt() <= MAX_OCTET }
        if (!isIpv4Literal && !candidate.contains(':')) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private fun isCgnatAddress(bytes: ByteArray): Boolean {
        val firstOctet = bytes[0].toInt() and MAX_OCTET
        val secondOctet = bytes[1].toInt() and MAX_OCTET
        return firstOctet == CGNAT_FIRST_OCTET && secondOctet in CGNAT_SECOND_OCTET_RANGE
    }

    private fun isUniqueLocalAddress(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and ULA_PREFIX_MASK) == ULA_PREFIX
}
