package com.aegis.core.net

/**
 * Hand-rolled IPv4/UDP for the local VPN.
 *
 * A `VpnService` hands us raw IP packets and expects raw IP packets back, so filtering
 * DNS means reading an IPv4 header, reading a UDP header, and — for a name we refuse —
 * writing a reply packet with the addresses and ports swapped and both checksums
 * recomputed. There is no Android API for that, hence this.
 *
 * Kept in the pure-Kotlin core, not the Android module, for one specific reason: packet
 * assembly is the kind of code that fails in ways that look like "the internet is
 * broken", and it needs to be unit-testable without a device.
 */
object Ipv4Udp {

    const val PROTOCOL_UDP = 17
    const val MIN_IPV4_HEADER = 20
    const val UDP_HEADER = 8

    data class UdpDatagram(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        // Explicit because ByteArray identity equality would make these useless in tests.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpDatagram) return false
            return sourceAddress.contentEquals(other.sourceAddress) &&
                destinationAddress.contentEquals(other.destinationAddress) &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                payloadOffset == other.payloadOffset &&
                payloadLength == other.payloadLength
        }

        override fun hashCode(): Int {
            var result = sourceAddress.contentHashCode()
            result = 31 * result + destinationAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payloadOffset
            result = 31 * result + payloadLength
            return result
        }
    }

    /** Read an IPv4 UDP datagram, or null if this packet is anything else. */
    fun parseUdp(packet: ByteArray, length: Int = packet.size): UdpDatagram? {
        if (length < MIN_IPV4_HEADER) return null

        val versionAndIhl = packet[0].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return null
        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < MIN_IPV4_HEADER || headerLength > length) return null

        val totalLength = DnsMessage.readUShort(packet, 2)
        if (totalLength > length || totalLength < headerLength) return null

        // A fragmented packet cannot be interpreted on its own; leave it alone.
        val flagsAndFragment = DnsMessage.readUShort(packet, 6)
        val moreFragments = (flagsAndFragment and 0x2000) != 0
        val fragmentOffset = flagsAndFragment and 0x1FFF
        if (moreFragments || fragmentOffset != 0) return null

        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null
        if (headerLength + UDP_HEADER > totalLength) return null

        val udpLength = DnsMessage.readUShort(packet, headerLength + 4)
        if (udpLength < UDP_HEADER || headerLength + udpLength > totalLength) return null

        return UdpDatagram(
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = DnsMessage.readUShort(packet, headerLength),
            destinationPort = DnsMessage.readUShort(packet, headerLength + 2),
            payloadOffset = headerLength + UDP_HEADER,
            payloadLength = udpLength - UDP_HEADER,
        )
    }

    /**
     * Build a reply to [request] carrying [payload], with addresses and ports reversed.
     */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray {
        val totalLength = MIN_IPV4_HEADER + UDP_HEADER + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45 // IPv4, 20-byte header
        packet[1] = 0 // DSCP/ECN
        DnsMessage.writeUShort(packet, 2, totalLength)
        DnsMessage.writeUShort(packet, 4, 0) // identification
        DnsMessage.writeUShort(packet, 6, 0x4000) // don't fragment
        packet[8] = 64 // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        // Reversed: the reply comes *from* where the request was going.
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        DnsMessage.writeUShort(packet, 10, 0)
        DnsMessage.writeUShort(packet, 10, checksum(packet, 0, MIN_IPV4_HEADER))

        var offset = MIN_IPV4_HEADER
        DnsMessage.writeUShort(packet, offset, request.destinationPort)
        DnsMessage.writeUShort(packet, offset + 2, request.sourcePort)
        DnsMessage.writeUShort(packet, offset + 4, UDP_HEADER + payload.size)
        DnsMessage.writeUShort(packet, offset + 6, 0)
        offset += UDP_HEADER
        payload.copyInto(packet, offset)

        DnsMessage.writeUShort(packet, MIN_IPV4_HEADER + 6, udpChecksum(packet, payload.size))
        return packet
    }

    /** Standard one's-complement checksum over a byte range. */
    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += DnsMessage.readUShort(bytes, index).toLong()
            index += 2
        }
        if (index < end) {
            sum += ((bytes[index].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * UDP checksum, which covers a pseudo-header of the IP addresses as well as the
     * datagram itself. Getting this wrong yields packets the kernel silently discards,
     * which is exactly the kind of bug that is impossible to find from a phone.
     */
    fun udpChecksum(packet: ByteArray, payloadSize: Int): Int {
        val udpLength = UDP_HEADER + payloadSize
        var sum = 0L

        for (index in 12 until 20 step 2) {
            sum += DnsMessage.readUShort(packet, index).toLong()
        }
        sum += PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()

        var index = MIN_IPV4_HEADER
        val end = MIN_IPV4_HEADER + udpLength
        while (index + 1 < end) {
            sum += DnsMessage.readUShort(packet, index).toLong()
            index += 2
        }
        if (index < end) {
            sum += ((packet[index].toInt() and 0xFF) shl 8).toLong()
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        // Zero means "no checksum" in UDP, so it is transmitted as all ones instead.
        return if (result == 0) 0xFFFF else result
    }

    fun addressToString(address: ByteArray): String =
        address.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
