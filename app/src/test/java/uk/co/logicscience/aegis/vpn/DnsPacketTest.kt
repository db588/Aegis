package uk.co.logicscience.aegis.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPacketTest {

    /** A minimal IPv4/UDP packet: 20-byte header (no options) + 8-byte UDP header + payload. */
    private fun buildQueryPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ihl = 20
        val udpLength = 8 + payload.size
        val packet = ByteArray(ihl + udpLength)

        packet[0] = 0x45 // version 4, IHL 5 (20 bytes)
        val totalLength = ihl + udpLength
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[9] = 17 // protocol = UDP
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        packet[ihl] = ((srcPort shr 8) and 0xFF).toByte()
        packet[ihl + 1] = (srcPort and 0xFF).toByte()
        packet[ihl + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[ihl + 3] = (dstPort and 0xFF).toByte()
        packet[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[ihl + 5] = (udpLength and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, ihl + 8, payload.size)
        return packet
    }

    @Test
    fun `source and destination addresses are swapped`() {
        val query = buildQueryPacket(
            srcIp = byteArrayOf(10, 111, 222.toByte(), 7),
            dstIp = byteArrayOf(10, 111, 222.toByte(), 53),
            srcPort = 54321,
            dstPort = 53,
            payload = ByteArray(4)
        )

        val response = DnsPacket.buildResponsePacket(query, ihl = 20, dnsPayload = ByteArray(4))

        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 53), response.copyOfRange(12, 16)) // new src = old dst
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 7), response.copyOfRange(16, 20)) // new dst = old src
    }

    @Test
    fun `source and destination ports are swapped`() {
        val query = buildQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 40000,
            dstPort = 53,
            payload = ByteArray(4)
        )

        val response = DnsPacket.buildResponsePacket(query, ihl = 20, dnsPayload = ByteArray(4))

        val udpSrcPort = ((response[20].toInt() and 0xFF) shl 8) or (response[21].toInt() and 0xFF)
        val udpDstPort = ((response[22].toInt() and 0xFF) shl 8) or (response[23].toInt() and 0xFF)
        assertEquals(53, udpSrcPort) // new src port = old dst port
        assertEquals(40000, udpDstPort) // new dst port = old src port
    }

    @Test
    fun `total length covers IP header, UDP header and payload`() {
        val payload = ByteArray(37) { it.toByte() }
        val query = buildQueryPacket(
            srcIp = byteArrayOf(1, 2, 3, 4),
            dstIp = byteArrayOf(5, 6, 7, 8),
            srcPort = 1234,
            dstPort = 53,
            payload = payload
        )

        val response = DnsPacket.buildResponsePacket(query, ihl = 20, dnsPayload = payload)

        val declaredTotalLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertEquals(20 + 8 + payload.size, declaredTotalLength)
        assertEquals(declaredTotalLength, response.size)
    }

    @Test
    fun `IP header checksum is valid`() {
        val query = buildQueryPacket(
            srcIp = byteArrayOf(10, 111, 222.toByte(), 7),
            dstIp = byteArrayOf(10, 111, 222.toByte(), 53),
            srcPort = 54321,
            dstPort = 53,
            payload = ByteArray(12)
        )

        val response = DnsPacket.buildResponsePacket(query, ihl = 20, dnsPayload = ByteArray(12))

        // A correct IPv4 header checksum makes the 16-bit one's-complement sum
        // of the whole header (checksum field included) come out to 0xFFFF.
        var sum = 0L
        var i = 0
        while (i < 20) {
            sum += ((response[i].toInt() and 0xFF) shl 8) or (response[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        assertEquals(0xFFFFL, sum)
    }

    @Test
    fun `dns payload is copied unchanged after the UDP header`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val query = buildQueryPacket(
            srcIp = byteArrayOf(1, 1, 1, 1),
            dstIp = byteArrayOf(2, 2, 2, 2),
            srcPort = 1111,
            dstPort = 53,
            payload = payload
        )

        val response = DnsPacket.buildResponsePacket(query, ihl = 20, dnsPayload = payload)

        assertArrayEquals(payload, response.copyOfRange(28, 33)) // ihl(20) + udpHeader(8) = 28
    }
}
