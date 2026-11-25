package dev.flexaccess.flextunnel.tunnelcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val node = "a".repeat(64)
    private val m1 = Destination("1", "m1", node, listOf("*.corp.example", "db.internal"), listOf("10.0.0.0/8"))

    @Test fun nameIsOneLowercasedLabel() {
        assertEquals("machine-1", Rules.destinationName(" Machine-1 "))
        assertThrows(RuleException::class.java) { Rules.destinationName("a.b") }
        assertThrows(RuleException::class.java) { Rules.destinationName("-a") }
        assertThrows(RuleException::class.java) { Rules.destinationName("xn--abc") }
        assertThrows(RuleException::class.java) { Rules.destinationName("a".repeat(64)) }
    }

    @Test fun nodeIdIsHexOrBase32() {
        Rules.serverNodeId(node)
        Rules.serverNodeId("a".repeat(52))
        assertThrows(RuleException::class.java) { Rules.serverNodeId("xyz") }
    }

    @Test fun domainRules() {
        assertEquals("*", Rules.domainRule("*"))
        assertEquals("*.corp.example", Rules.domainRule("*.Corp.Example."))
        assertThrows(RuleException::class.java) { Rules.domainRule("10.0.0.1") }
        assertThrows(RuleException::class.java) { Rules.domainRule("a.flextunnel.internal") }
        assertThrows(RuleException::class.java) { Rules.domainRule("*.*.x") }
    }

    @Test fun cidrRules() {
        assertEquals("10.0.0.0/8", Rules.cidrRule("10.0.0.0/8").toString())
        Rules.cidrRule("192.168.1.5")
        Rules.cidrRule("fd00::/8")
        assertThrows(RuleException::class.java) { Rules.cidrRule("10.0.0.1/8") }
        assertThrows(RuleException::class.java) { Rules.cidrRule("nope") }
    }

    @Test fun ipv4MappedRangesStayIpv6() {
        // Rust's IpNetwork reads it as an IPv6 /120, not as 192.0.2.0 with a bad length.
        assertEquals("::ffff:c000:200/120", Rules.cidrRule("::ffff:192.0.2.0/120").toString())
        assertEquals("10.0.0.0/8", Rules.cidrRule("10.0.0.0/8").toString())
        assertFalse("a mapped address is IPv6: 10.0.0.0/8 doesn't route it", Rules.routes("::ffff:10.1.2.3", listOf(m1)))
    }

    @Test fun destinationsDoNotShareNamesServersOrRules() {
        val other = Destination("2", "m2", "b".repeat(64))
        Rules.destination(other, listOf(m1))
        assertThrows(RuleException::class.java) { Rules.destination(other.copy(name = "M1"), listOf(m1)) }
        assertThrows(RuleException::class.java) { Rules.destination(other.copy(serverNodeId = node.uppercase()), listOf(m1)) }
        // The same key in its base32 spelling is the same server; another key in base32 is not.
        assertThrows(RuleException::class.java) {
            Rules.destination(other.copy(serverNodeId = "vkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkvkva"), listOf(m1))
        }
        Rules.destination(other.copy(serverNodeId = "xo53xo53xo53xo53xo53xo53xo53xo53xo53xo53xo53xo53xo5q"), listOf(m1))
        assertThrows(RuleException::class.java) {
            Rules.destination(other, listOf(m1.copy(serverNodeId = "XO53XO53XO53XO53XO53XO53XO53XO53XO53XO53XO53XO53XO5Q")))
        }
        assertThrows(RuleException::class.java) { Rules.destination(other.copy(routedDomains = listOf("db.internal")), listOf(m1)) }
        assertThrows(RuleException::class.java) { Rules.destination(other.copy(routedCidrs = listOf("10.0.0.0/8")), listOf(m1)) }
    }

    @Test fun routingFollowsTheCore() {
        val ds = listOf(m1)
        assertTrue(Rules.routes("m1.localhost.flextunnel.internal", ds))
        assertTrue(Rules.routes("api.corp.example", ds))
        assertFalse("*.suffix leaves out the bare apex", Rules.routes("corp.example", ds))
        assertTrue(Rules.routes("DB.internal.", ds))
        assertTrue(Rules.routes("10.1.2.3", ds))
        assertFalse(Rules.routes("11.1.2.3", ds))
        assertFalse(Rules.routes("other.flextunnel.internal", ds))
        assertTrue(Rules.routes("anything", listOf(m1.copy(routedDomains = listOf("*")))))
    }

    @Test fun forwardsNeedARouteAndAFreePort() {
        val settings = SessionSettings(socksEnabled = true, socksPort = 1080)
        val ssh = PortForward("f1", "ssh", 2222, "m1.localhost.flextunnel.internal", 22)
        Rules.forward(ssh, emptyList(), settings, listOf(m1))
        assertThrows(RuleException::class.java) { Rules.forward(ssh.copy(remoteHost = "example.com"), emptyList(), settings, listOf(m1)) }
        assertThrows(RuleException::class.java) { Rules.forward(ssh.copy(localPort = 1080), emptyList(), settings, listOf(m1)) }
        assertThrows(RuleException::class.java) { Rules.forward(ssh.copy(id = "f2"), listOf(ssh), settings, listOf(m1)) }
        // A disabled forward may share a port; only enabled ones bind.
        Rules.forward(ssh.copy(id = "f2", enabled = false), listOf(ssh), settings, listOf(m1))
        Rules.forward(ssh.copy(localPort = 1080), emptyList(), settings.copy(socksEnabled = false), listOf(m1))
    }

    @Test fun settingsCheckThePortAndRelays() {
        assertEquals(
            listOf("https://relay.example/"),
            Rules.settings(SessionSettings(relayUrls = listOf(" https://relay.example/ ")), emptyList()).relayUrls,
        )
        assertThrows(RuleException::class.java) { Rules.settings(SessionSettings(relayUrls = listOf("relay.example")), emptyList()) }
        assertThrows(RuleException::class.java) {
            Rules.settings(SessionSettings(socksPort = 2222), listOf(PortForward("f", "", 2222, "h", 22)))
        }
    }
}
