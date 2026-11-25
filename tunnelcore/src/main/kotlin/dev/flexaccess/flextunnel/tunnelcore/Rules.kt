package dev.flexaccess.flextunnel.tunnelcore

import java.net.URI

/** A rule a value broke, phrased for the user. */
class RuleException(message: String) : Exception(message)

/**
 * The core's rules for what the app saves, repeated so a mistake is reported
 * next to its field instead of when a session starts. The core checks again
 * at start (and on every forward change) and is the authority: whether a
 * well-formed server node id is a real key is only decided there.
 */
object Rules {
    private const val RESERVED = "flextunnel.internal"
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")
    private val base32 = Regex("^[a-zA-Z2-7]{52}$")
    private val hostChars = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * The core's `validate_destination_name`: one DNS label — 1 to 63 ASCII
     * letters, digits and hyphens, not starting or ending with a hyphen, and
     * no reserved `ab--` label (`xn--` among them). Returns it lowercased.
     */
    fun destinationName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw RuleException("Name is required.")
        if (name.length > 63) throw RuleException("Name is longer than 63 characters.")
        if (!name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) {
            throw RuleException("Name may only contain letters, digits and hyphens (one DNS label, no dots).")
        }
        if (name.startsWith('-') || name.endsWith('-')) throw RuleException("Name can't start or end with a hyphen.")
        if (name.length >= 4 && name[2] == '-' && name[3] == '-') {
            throw RuleException("Name can't have hyphens as its third and fourth characters (reserved, like xn--).")
        }
        return name.lowercase()
    }

    /** An iroh endpoint id as a server prints it: 64 hex digits, or its 52-character base32 form. */
    fun serverNodeId(raw: String): String {
        val id = raw.trim()
        if (id.isEmpty()) throw RuleException("Server node id is required.")
        if (!hex64.matches(id) && !base32.matches(id)) {
            throw RuleException("That is not a server node id. Paste the id the server prints: 64 hex digits, or its 52-character base32 form.")
        }
        return id
    }

    /**
     * The 32 key bytes a well-formed node id spells (hex, or RFC 4648 base32
     * without padding), so the two spellings of one server compare equal, as
     * the core's parsed `EndpointId`s do. Null for anything else.
     */
    fun serverKey(raw: String): ByteArray? {
        val id = raw.trim()
        if (hex64.matches(id)) return ByteArray(32) { id.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        if (!base32.matches(id)) return null
        val out = java.io.ByteArrayOutputStream(32)
        var buffer = 0
        var bits = 0
        for (c in id.uppercase()) {
            buffer = (buffer shl 5) or (if (c in 'A'..'Z') c - 'A' else c - '2' + 26)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    /** Split a rules field — one rule per line, or separated by commas or spaces. */
    fun splitRules(text: String): List<String> =
        text.split(',', ' ', '\n', '\t', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    /** The key two domain rules collide on: lowercased, without a trailing dot. */
    private fun domainKey(rule: String): String = rule.trim().lowercase().removeSuffix(".")

    private fun isReserved(name: String): Boolean = name == RESERVED || name.endsWith(".$RESERVED")

    /** A hostname the core would take: labels of letters, digits, `-` and `_`, at most 253 long. */
    fun hostnameProblem(host: String): String? {
        if (host.length > 253) return "is too long (253 characters max)"
        if (!hostChars.matches(host)) return "may only contain letters, digits, '-', '_' and dots"
        if (host.split('.').any { it.isEmpty() || it.length > 63 }) return "has an empty or over-long label"
        return null
    }

    /** One `routed_domains` entry: `*`, a hostname, or `*.` and a hostname. */
    fun domainRule(raw: String): String {
        val rule = domainKey(raw)
        if (rule == "*") return rule
        val name = rule.removePrefix("*.")
        if (IpLiteral.isAddress(name.removePrefix("[").removeSuffix("]"))) {
            throw RuleException("$raw is an IP address — list it under IP ranges.")
        }
        if (name.isEmpty() || name.contains('*') || hostnameProblem(name) != null) {
            throw RuleException("$raw is not a domain rule. Use a name, *.suffix or *.")
        }
        if (isReserved(name)) throw RuleException("$raw is in the reserved flextunnel.internal namespace.")
        return rule
    }

    /** One `routed_cidrs` entry: a network with no host bits set, or one address. */
    fun cidrRule(raw: String): IpPrefix {
        val text = raw.trim()
        IpPrefix.host(text)?.let { return it }
        val prefix = IpPrefix.parse(text)
            ?: throw RuleException("$raw is not an IP range. Use a network like 10.0.0.0/8, or one address.")
        val written = IpLiteral.parse(text.substringBefore('/'))!!
        if (!written.contentEquals(prefix.bytes)) {
            throw RuleException("$raw has address bits beyond its prefix. Use $prefix.")
        }
        return prefix
    }

    /**
     * Normalize [destination] and check it against [others] (every other saved
     * destination): names and servers are unique, and no rule is on two
     * destinations — a tie the core refuses.
     */
    fun destination(destination: Destination, others: List<Destination>): Destination {
        val name = destinationName(destination.name)
        val nodeId = serverNodeId(destination.serverNodeId)
        val domains = destination.routedDomains.map { domainRule(it) }.distinct()
        val cidrs = destination.routedCidrs.map { it.trim() }.filter { it.isNotEmpty() }
        val prefixes = cidrs.map { cidrRule(it) }
        others.firstOrNull { it.name == name }?.let { throw RuleException("Another destination is already named $name.") }
        val key = serverKey(nodeId)
        others.firstOrNull { o -> serverKey(o.serverNodeId)?.contentEquals(key) == true }
            ?.let { throw RuleException("${it.name} already reaches that server.") }
        for (rule in domains) {
            others.firstOrNull { o -> o.routedDomains.any { domainKey(it) == rule } }
                ?.let { throw RuleException("$rule is already routed through ${it.name}.") }
        }
        for ((i, prefix) in prefixes.withIndex()) {
            others.firstOrNull { o -> o.routedCidrs.any { runCatching { cidrRule(it) }.getOrNull() == prefix } }
                ?.let { throw RuleException("${cidrs[i]} is already routed through ${it.name}.") }
        }
        return destination.copy(name = name, serverNodeId = nodeId, routedDomains = domains, routedCidrs = cidrs)
    }

    /** A port number field. */
    fun port(raw: String, what: String): Int {
        val port = raw.trim().toIntOrNull()
        if (port == null || port !in 1..65535) throw RuleException("$what must be a number from 1 to 65535.")
        return port
    }

    /**
     * Whether some destination routes [host] — the core's precedence without
     * the ranking: a loopback name, then an exact domain, a `*.suffix` (not
     * its bare apex), `*`, or for an address literal a routed network.
     */
    fun routes(host: String, destinations: List<Destination>): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        if (destinations.any { h == it.loopbackHost }) return true
        if (isReserved(h)) return false
        val address = IpLiteral.parse(h.removePrefix("[").removeSuffix("]"))
        if (address != null) {
            return destinations.any { d ->
                d.routedCidrs.any { runCatching { cidrRule(it) }.getOrNull()?.containsAddress(address) == true }
            }
        }
        return destinations.any { d ->
            d.routedDomains.any { raw ->
                val rule = domainKey(raw)
                rule == "*" || rule == h || (rule.startsWith("*.") && h.endsWith(rule.substring(1)))
            }
        }
    }

    /**
     * Normalize [forward] and check it against [others] and the session: an
     * enabled forward's local port is unique among the enabled ones and not
     * the SOCKS5 port, and its host is routed by a destination.
     */
    fun forward(
        forward: PortForward,
        others: List<PortForward>,
        settings: SessionSettings,
        destinations: List<Destination>,
    ): PortForward {
        val host = forward.remoteHost.trim()
        if (host.isEmpty()) throw RuleException("Remote host is required.")
        if (IpLiteral.parse(host.removePrefix("[").removeSuffix("]")) == null) {
            hostnameProblem(host.removeSuffix("."))?.let { throw RuleException("Remote host $it.") }
        }
        if (forward.localPort !in 1..65535 || forward.remotePort !in 1..65535) {
            throw RuleException("Ports must be from 1 to 65535.")
        }
        if (forward.enabled) {
            others.firstOrNull { it.enabled && it.localPort == forward.localPort }
                ?.let { throw RuleException("Local port ${forward.localPort} is already used by ${it.label.ifEmpty { "another forward" }}.") }
            if (settings.socksEnabled && settings.socksPort == forward.localPort) {
                throw RuleException("Local port ${forward.localPort} is the SOCKS5 proxy's port.")
            }
        }
        if (!routes(host, destinations)) {
            throw RuleException("No destination routes $host. Use <name>.${Destination.LOOPBACK_SUFFIX} for a server's own ports, or add the host to a destination's routes.")
        }
        return forward.copy(label = forward.label.trim(), remoteHost = host)
    }

    /** The session settings: a valid SOCKS5 port no enabled forward holds, and http(s) relay URLs. */
    fun settings(settings: SessionSettings, forwards: List<PortForward>): SessionSettings {
        if (settings.socksPort !in 1..65535) throw RuleException("SOCKS5 port must be from 1 to 65535.")
        if (settings.socksEnabled) {
            forwards.firstOrNull { it.enabled && it.localPort == settings.socksPort }
                ?.let { throw RuleException("Port ${settings.socksPort} is used by forward ${it.label.ifEmpty { it.remoteHost }}.") }
        }
        val relays = settings.relayUrls.map { it.trim() }.filter { it.isNotEmpty() }
        for (url in relays) {
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrEmpty()) {
                throw RuleException("$url is not a relay URL (https://relay.example/).")
            }
        }
        return settings.copy(relayUrls = relays)
    }
}
