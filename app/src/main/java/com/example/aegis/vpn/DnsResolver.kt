package com.example.aegis.vpn

import android.content.Context
import com.example.aegis.data.AppDatabase
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Handles raw IPv4/UDP packets from the TUN device.
 * Parses the DNS query inside, decides block/forward, and builds
 * a complete IPv4/UDP response packet to write back to the TUN.
 */
class DnsResolver(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private var blockedDomains: Set<String> = emptySet()
    private var whitelistedDomains: Set<String> = emptySet()
    private val upstreamDns = "8.8.8.8"

    var lastQueryWasBlocked = false
        private set

    init {
        reloadBlocklists()
    }

    fun reloadBlocklists() {
        runBlocking {
            blockedDomains = db.blockedDomainDao().getEnabledBlockedDomains().toSet()
            whitelistedDomains = db.whitelistDao().getWhitelistedDomains().toSet()
        }
    }

    /**
     * @param packet full IPv4 packet from TUN
     * @param length valid bytes in packet
     * @param ihl    IPv4 header length in bytes
     * @return a full IPv4/UDP packet to write back, or null
     */
    fun handlePacket(packet: ByteArray, length: Int, ihl: Int): ByteArray? {
        lastQueryWasBlocked = false
        return try {
            val udpHeaderStart = ihl
            val dnsStart = ihl + 8
            val dnsLength = length - dnsStart
            if (dnsLength <= 12) return null

            val dnsQuery = ByteArray(dnsLength)
            System.arraycopy(packet, dnsStart, dnsQuery, 0, dnsLength)

            val domainName = DnsPacket.parseQueryName(dnsQuery)

            val dnsResponse: ByteArray = when {
                isWhitelisted(domainName) -> forwardQuery(dnsQuery) ?: return null
                isBlocked(domainName) -> {
                    lastQueryWasBlocked = true
                    createNxDomain(dnsQuery)
                }
                else -> forwardQuery(dnsQuery) ?: return null
            }

            buildResponsePacket(packet, ihl, dnsResponse)
        } catch (e: Exception) {
            null
        }
    }

    private fun isWhitelisted(domain: String): Boolean {
        val d = domain.lowercase()
        if (whitelistedDomains.contains(d)) return true
        val parts = d.split(".")
        for (i in 1 until parts.size) {
            if (whitelistedDomains.contains(parts.subList(i, parts.size).joinToString("."))) return true
        }
        return false
    }

    private fun isBlocked(domain: String): Boolean {
        val d = domain.lowercase()
        if (blockedDomains.contains(d)) return true
        val parts = d.split(".")
        for (i in 1 until parts.size) {
            if (blockedDomains.contains(parts.subList(i, parts.size).joinToString("."))) return true
        }
        return false
    }

    private fun forwardQuery(dnsQuery: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                val addr = InetAddress.getByName(upstreamDns)
                socket.send(DatagramPacket(dnsQuery, dnsQuery.size, addr, 53))
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                buf.copyOf(resp.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Flip the query into an NXDOMAIN response */
    private fun createNxDomain(dnsQuery: ByteArray): ByteArray {
        val response = dnsQuery.copyOf()
        // QR=1 (response), RA=1, RCODE=3 (NXDOMAIN)
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0x70) or 0x83).toByte()
        // Zero answer/authority/additional counts
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }

    /**
     * Build a full IPv4/UDP packet: swap src/dst addresses and ports
     * from the original query packet, attach the DNS response payload,
     * and recompute checksums.
     */
    private fun buildResponsePacket(query: ByteArray, ihl: Int, dnsPayload: ByteArray): ByteArray {
        val udpLength = 8 + dnsPayload.size
        val totalLength = ihl + udpLength
        val out = ByteArray(totalLength)

        // --- IPv4 header: copy then swap ---
        System.arraycopy(query, 0, out, 0, ihl)
        // Total length
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        // Swap src (12..15) and dst (16..19)
        for (i in 0..3) {
            out[12 + i] = query[16 + i]
            out[16 + i] = query[12 + i]
        }
        // TTL
        out[8] = 64
        // Zero checksum then recompute
        out[10] = 0; out[11] = 0
        val ipCsum = checksum(out, 0, ihl)
        out[10] = ((ipCsum shr 8) and 0xFF).toByte()
        out[11] = (ipCsum and 0xFF).toByte()

        // --- UDP header: swap ports ---
        out[ihl] = query[ihl + 2]         // src port = original dst port (53)
        out[ihl + 1] = query[ihl + 3]
        out[ihl + 2] = query[ihl]         // dst port = original src port
        out[ihl + 3] = query[ihl + 1]
        out[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
        out[ihl + 5] = (udpLength and 0xFF).toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0 // UDP checksum optional for IPv4

        // --- DNS payload ---
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)

        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (length % 2 != 0) {
            sum += ((data[offset + length - 1].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }
}

object DnsPacket {
    /** Extract the query name from a raw DNS message */
    fun parseQueryName(dns: ByteArray): String {
        val labels = mutableListOf<String>()
        var pos = 12 // skip DNS header
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break // compression pointer, stop
            pos++
            if (pos + len > dns.size) break
            labels.add(String(dns, pos, len))
            pos += len
        }
        return labels.joinToString(".")
    }
}
