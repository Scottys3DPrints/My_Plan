package com.aegis.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ipv4UdpTest {

    private fun udpPacket(
        payload: ByteArray,
        sourcePort: Int = 40_000,
        destinationPort: Int = 53,
        protocol: Int = Ipv4Udp.PROTOCOL_UDP,
    ): ByteArray {
        val total = Ipv4Udp.MIN_IPV4_HEADER + Ipv4Udp.UDP_HEADER + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        DnsMessage.writeUShort(packet, 2, total)
        DnsMessage.writeUShort(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = protocol.toByte()
        byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
        byteArrayOf(10, 0, 0, 1).copyInto(packet, 16)
        DnsMessage.writeUShort(packet, 10, Ipv4Udp.checksum(packet, 0, Ipv4Udp.MIN_IPV4_HEADER))

        DnsMessage.writeUShort(packet, 20, sourcePort)
        DnsMessage.writeUShort(packet, 22, destinationPort)
        DnsMessage.writeUShort(packet, 24, Ipv4Udp.UDP_HEADER + payload.size)
        payload.copyInto(packet, 28)
        DnsMessage.writeUShort(packet, 26, Ipv4Udp.udpChecksum(packet, payload.size))
        return packet
    }

    @Test
    fun `reads an IPv4 UDP datagram`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val datagram = Ipv4Udp.parseUdp(udpPacket(payload))

        assertNotNull(datagram)
        assertEquals(40_000, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertEquals("10.0.0.2", Ipv4Udp.addressToString(datagram.sourceAddress))
        assertEquals("10.0.0.1", Ipv4Udp.addressToString(datagram.destinationAddress))
        assertEquals(5, datagram.payloadLength)
        assertEquals(28, datagram.payloadOffset)
    }

    @Test
    fun `non-UDP traffic is not ours to touch`() {
        assertNull(Ipv4Udp.parseUdp(udpPacket(byteArrayOf(1), protocol = 6))) // TCP
    }

    @Test
    fun `fragments are passed through untouched`() {
        val packet = udpPacket(byteArrayOf(1, 2, 3))
        DnsMessage.writeUShort(packet, 6, 0x2000) // more fragments
        assertNull(Ipv4Udp.parseUdp(packet))
    }

    @Test
    fun `an IPv6 packet is not mistaken for IPv4`() {
        val packet = udpPacket(byteArrayOf(1))
        packet[0] = 0x60
        assertNull(Ipv4Udp.parseUdp(packet))
    }

    @Test
    fun `a truncated packet is refused`() {
        val packet = udpPacket(byteArrayOf(1, 2, 3, 4))
        assertNull(Ipv4Udp.parseUdp(packet, length = 12))
    }

    @Test
    fun `a reply reverses the addresses and ports`() {
        val request = Ipv4Udp.parseUdp(udpPacket(byteArrayOf(9, 9)))!!
        val reply = Ipv4Udp.buildUdpReply(request, byteArrayOf(7, 7, 7))
        val parsed = Ipv4Udp.parseUdp(reply)!!

        assertEquals("10.0.0.1", Ipv4Udp.addressToString(parsed.sourceAddress))
        assertEquals("10.0.0.2", Ipv4Udp.addressToString(parsed.destinationAddress))
        assertEquals(53, parsed.sourcePort)
        assertEquals(40_000, parsed.destinationPort)
        assertEquals(3, parsed.payloadLength)
        assertEquals(listOf<Byte>(7, 7, 7), reply.takeLast(3))
    }

    @Test
    fun `a built reply carries valid checksums`() {
        // The kernel drops packets whose checksums are wrong, silently. Verified here by
        // the standard property: checksumming a header that already contains its own
        // checksum yields zero.
        val request = Ipv4Udp.parseUdp(udpPacket(ByteArray(40) { it.toByte() }))!!
        val reply = Ipv4Udp.buildUdpReply(request, ByteArray(64) { (it * 7).toByte() })

        assertEquals(0, Ipv4Udp.checksum(reply, 0, Ipv4Udp.MIN_IPV4_HEADER), "IPv4 header checksum")

        val payloadSize = reply.size - Ipv4Udp.MIN_IPV4_HEADER - Ipv4Udp.UDP_HEADER
        val stated = DnsMessage.readUShort(reply, Ipv4Udp.MIN_IPV4_HEADER + 6)
        DnsMessage.writeUShort(reply, Ipv4Udp.MIN_IPV4_HEADER + 6, 0)
        val recomputed = Ipv4Udp.udpChecksum(reply, payloadSize)
        assertEquals(stated, recomputed, "UDP checksum")
    }

    @Test
    fun `end to end - a blocked name produces a well-formed refusal packet`() {
        val name = "tracker.example"
        val labels = name.split('.')
        val dnsQuery = ByteArray(DnsMessage.HEADER_SIZE + labels.sumOf { it.length + 1 } + 1 + 4)
        DnsMessage.writeUShort(dnsQuery, 0, 0x4242)
        DnsMessage.writeUShort(dnsQuery, 2, 0x0100)
        DnsMessage.writeUShort(dnsQuery, 4, 1)
        var offset = DnsMessage.HEADER_SIZE
        for (label in labels) {
            dnsQuery[offset++] = label.length.toByte()
            for (char in label) dnsQuery[offset++] = char.code.toByte()
        }
        dnsQuery[offset++] = 0
        DnsMessage.writeUShort(dnsQuery, offset, DnsMessage.TYPE_A)
        DnsMessage.writeUShort(dnsQuery, offset + 2, DnsMessage.CLASS_IN)

        val ipPacket = udpPacket(dnsQuery)
        val datagram = Ipv4Udp.parseUdp(ipPacket)!!
        val payload = ipPacket.copyOfRange(
            datagram.payloadOffset,
            datagram.payloadOffset + datagram.payloadLength,
        )
        val question = DnsMessage.parseQuestion(payload)!!
        assertEquals("tracker.example", question.name)

        val dnsResponse = DnsMessage.buildBlockedResponse(payload, question)
        val replyPacket = Ipv4Udp.buildUdpReply(datagram, dnsResponse)

        val replyDatagram = Ipv4Udp.parseUdp(replyPacket)!!
        assertEquals(53, replyDatagram.sourcePort)
        assertEquals(0, Ipv4Udp.checksum(replyPacket, 0, Ipv4Udp.MIN_IPV4_HEADER))
        assertEquals(0x4242, DnsMessage.readUShort(replyPacket, replyDatagram.payloadOffset))
        assertTrue(replyPacket.takeLast(4).all { it == 0.toByte() }, "answers 0.0.0.0")
    }
}
