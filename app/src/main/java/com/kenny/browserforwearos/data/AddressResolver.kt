package com.kenny.browserforwearos.data

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

enum class SearchEngine(val searchUrl: String) {
    BING("https://www.bing.com/search?q="),
    GOOGLE("https://www.google.com/search?q="),
    DUCKDUCKGO("https://duckduckgo.com/?q="),
}

/** Address handling is pure logic: no text is sent until the user submits it. */
object AddressResolver {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hostLabel = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
    private val privateQueryNames = setOf(
        "password", "passwd", "pwd", "token", "access_token", "refresh_token", "id_token",
        "auth", "authorization", "auth_token", "session", "sessionid", "session_id",
        "api_key", "apikey", "secret", "client_secret", "credential", "credentials",
        "code", "oauth_token", "oauth_verifier", "jwt", "bearer", "assertion", "samlresponse",
    )

    fun resolve(input: String, engine: SearchEngine = SearchEngine.BING): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH || value.any { it.isISOControl() }) return null
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return httpUrl(value)
        }
        // A host with a port is a URL; arbitrary explicit schemes never become searches.
        val candidate = if (value.none { it.isWhitespace() }) httpUrl("https://$value") else null
        if (candidate != null) {
            val host = URI(candidate).host.orEmpty()
            if (host.contains('.') || host.contains(':') || host.equals("localhost", true)) return candidate
        }
        if (value.none { it.isWhitespace() } && (value.substringBefore('/').contains('@') || value.substringBefore('/').contains('.'))) return null
        if (scheme.containsMatchIn(value) || value.startsWith("//") || value.contains("://")) return null
        return engine.searchUrl + URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    /** Only well-formed HTTP(S) URLs without embedded user credentials can be navigated. */
    fun httpUrl(value: String): String? = runCatching {
        if (value.length > MAX_URL_LENGTH || value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return null
        val uri = URI(value)
        val protocol = uri.scheme?.lowercase(Locale.ROOT)
        if (protocol != "http" && protocol != "https") return null
        if (uri.isOpaque || uri.rawUserInfo != null) return null
        val authority = uri.rawAuthority ?: return null
        if ('@' in authority || '%' in authority || authority.isEmpty()) return null
        val host: String
        val portPart: String
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            if (closing < 0 || uri.host == null) return null
            host = authority.substring(0, closing + 1).lowercase(Locale.ROOT)
            portPart = authority.substring(closing + 1)
        } else {
            if (authority.count { it == ':' } > 1) return null
            val unicodeHost = authority.substringBefore(':').removeSuffix(".")
            host = IDN.toASCII(unicodeHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            if (host.isEmpty() || host.length > 253 || host.split('.').any { !hostLabel.matches(it) }) return null
            // Four numeric labels must be a real IPv4 address, not a misleading broken address.
            val labels = host.split('.')
            if (labels.size == 4 && labels.all { it.all(Char::isDigit) } && labels.any { (it.toIntOrNull() ?: 256) > 255 }) return null
            portPart = if (':' in authority) ":" + authority.substringAfter(':') else ""
        }
        if (portPart.isNotEmpty()) {
            if (!portPart.startsWith(':')) return null
            val port = portPart.drop(1)
            if (port.isEmpty() || !port.all(Char::isDigit) || (port.toIntOrNull() ?: -1) !in 1..65535) return null
        }
        val suffix = value.substring(value.indexOf("://") + 3 + authority.length)
        URI("$protocol://$host$portPart$suffix").toASCIIString()
    }.getOrNull()

    /** Profile metadata contains no credentials, form contents, cookies or auth headers. */
    fun persistableUrl(value: String): String? {
        val safe = httpUrl(value) ?: return null
        val uri = URI(safe)
        val parameterGroups = listOfNotNull(uri.rawQuery, uri.rawFragment)
        val sensitive = parameterGroups.any { parameters ->
            parameters.split('&', ';').any { pair ->
                val name = runCatching {
                    URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name()).lowercase(Locale.ROOT)
                }.getOrNull() ?: return null
                name in privateQueryNames
            }
        }
        return safe.takeUnless { sensitive }
    }

    const val MAX_URL_LENGTH = 8192
}
