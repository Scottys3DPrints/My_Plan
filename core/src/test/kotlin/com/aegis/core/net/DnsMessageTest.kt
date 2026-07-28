package com.aegis.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsMessageTest {

    /** A standard recursive query, built the way a real resolver client builds one. */
    private fun query(name: String, type: Int = DnsMessage.TYPE_A, transactionId: Int = 0x1234): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val nameSize = labels.sumOf { it.length + 1 } + 1
        val packet = ByteArray(DnsMessage.HEADER_SIZE + nameSize + 4)

        DnsMessage.writeUShort(packet, 0, transactionId)
        DnsMessage.writeUShort(packet, 2, 0x0100) // standard query, recursion desired
        DnsMessage.writeUShort(packet, 4, 1) // one question

        var offset = DnsMessage.HEADER_SIZE
        for (label in labels) {
            packet[offset++] = label.length.toByte()
            for (char in label) packet[offset++] = char.code.toByte()
        }
        packet[offset++] = 0
        DnsMessage.writeUShort(packet, offset, type)
        DnsMessage.writeUShort(packet, offset + 2, DnsMessage.CLASS_IN)
        return packet
    }

    @Test
    fun `reads the name being asked for`() {
        val question = DnsMessage.parseQuestion(query("ads.tracker.example"))!!

        assertEquals("ads.tracker.example", question.name)
        assertEquals(DnsMessage.TYPE_A, question.type)
        assertEquals(0x1234, question.transactionId)
        assertTrue(question.recursionDesired)
    }

    @Test
    fun `names are lowercased so rules match regardless of case`() {
        val question = DnsMessage.parseQuestion(query("Ads.Tracker.EXAMPLE"))!!
        assertEquals("ads.tracker.example", question.name)
    }

    @Test
    fun `a blocked A query is answered with an unroutable address`() {
        val request = query("blocked.example")
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question)

        assertEquals(0x1234, DnsMessage.readUShort(response, 0))
        val flags = DnsMessage.readUShort(response, 2)
        assertTrue(flags and 0x8000 != 0, "must be marked as a response")
        assertEquals(DnsMessage.RCODE_NO_ERROR, flags and 0x0F)
        assertEquals(1, DnsMessage.readUShort(response, 6), "one answer record")

        // Name pointer, type A, class IN, then four zero bytes of address.
        val answer = question.endOffset
        assertEquals(0xC00C, DnsMessage.readUShort(response, answer))
        assertEquals(DnsMessage.TYPE_A, DnsMessage.readUShort(response, answer + 2))
        assertEquals(DnsMessage.CLASS_IN, DnsMessage.readUShort(response, answer + 4))
        assertEquals(4, DnsMessage.readUShort(response, answer + 10))
        assertEquals(listOf<Byte>(0, 0, 0, 0), response.takeLast(4))
    }

    @Test
    fun `a blocked AAAA query is answered with the unspecified address`() {
        val request = query("blocked.example", DnsMessage.TYPE_AAAA)
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question)

        val answer = question.endOffset
        assertEquals(DnsMessage.TYPE_AAAA, DnsMessage.readUShort(response, answer + 2))
        assertEquals(16, DnsMessage.readUShort(response, answer + 10))
        assertTrue(response.takeLast(16).all { it == 0.toByte() })
    }

    @Test
    fun `a blocked query of some other type gets NXDOMAIN`() {
        val request = query("blocked.example", DnsMessage.TYPE_HTTPS)
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question)

        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.readUShort(response, 2) and 0x0F)
        assertEquals(0, DnsMessage.readUShort(response, 6))
    }

    @Test
    fun `anything we cannot parse is left alone rather than dropped`() {
        // Failing open matters: silently swallowing traffic looks like a broken network.
        assertNull(DnsMessage.parseQuestion(ByteArray(4)))
        assertNull(DnsMessage.parseQuestion(ByteArray(64)), "all-zero packet claims zero questions")

        val truncated = query("example.com").copyOfRange(0, 16)
        assertNull(DnsMessage.parseQuestion(truncated))

        val response = query("example.com").also { DnsMessage.writeUShort(it, 2, 0x8180) }
        assertNull(DnsMessage.parseQuestion(response), "a response is not a question")
    }

    @Test
    fun `a compression pointer in the question section is refused`() {
        // Illegal there, and following one is how a parser gets walked into a loop.
        val packet = query("example.com")
        packet[DnsMessage.HEADER_SIZE] = 0xC0.toByte()
        assertNull(DnsMessage.parseQuestion(packet))
    }

    @Test
    fun `an over-long name is refused`() {
        val longName = (1..40).joinToString(".") { "abcdefghij" } // 40 labels of 10
        assertNull(DnsMessage.parseQuestion(query(longName)))
    }
}
