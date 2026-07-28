package com.aegis.core.net

/**
 * Just enough DNS to answer a question the user asked us to refuse.
 *
 * The VPN filter (§3.5, §5) never needs to *resolve* anything — it forwards questions it
 * approves to the real resolver untouched. All it must do itself is read the name being
 * asked for, and, for a name that is blocked, synthesise a well-formed refusal. So this
 * parses the question section and builds a response, and deliberately does no more:
 * every additional byte of DNS parsing in a privileged network path is attack surface
 * for no benefit.
 */
object DnsMessage {

    const val HEADER_SIZE = 12
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    const val RCODE_NO_ERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val MAX_NAME_LENGTH = 255
    private const val MAX_LABEL_LENGTH = 63
    private const val BLOCK_TTL_SECONDS = 60

    data class Question(
        val transactionId: Int,
        val name: String,
        val type: Int,
        val qClass: Int,
        /** Offset just past the question section, where an answer would be appended. */
        val endOffset: Int,
        val recursionDesired: Boolean,
    )

    /**
     * Read the first question, or null if this is not a well-formed single-question query.
     *
     * Anything unexpected returns null, and the caller forwards the packet untouched.
     * Failing *open* is the right call here: a malformed packet we do not understand is
     * not evidence of anything, and silently dropping traffic we cannot parse would make
     * the VPN look like a broken network connection.
     */
    fun parseQuestion(packet: ByteArray, length: Int = packet.size): Question? {
        if (length < HEADER_SIZE) return null

        val flags = readUShort(packet, 2)
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null
        val opcode = (flags shr 11) and 0x0F
        if (opcode != 0) return null

        val questionCount = readUShort(packet, 4)
        if (questionCount != 1) return null

        val name = StringBuilder()
        var offset = HEADER_SIZE
        var totalLength = 0

        while (true) {
            if (offset >= length) return null
            val labelLength = packet[offset].toInt() and 0xFF
            offset++

            if (labelLength == 0) break
            // Compression pointers are illegal in a question section; refuse to guess.
            if (labelLength and 0xC0 != 0) return null
            if (labelLength > MAX_LABEL_LENGTH) return null
            if (offset + labelLength > length) return null

            totalLength += labelLength + 1
            if (totalLength > MAX_NAME_LENGTH) return null

            if (name.isNotEmpty()) name.append('.')
            for (index in 0 until labelLength) {
                name.append((packet[offset + index].toInt() and 0xFF).toChar())
            }
            offset += labelLength
        }

        if (offset + 4 > length) return null
        val type = readUShort(packet, offset)
        val qClass = readUShort(packet, offset + 2)
        offset += 4

        return Question(
            transactionId = readUShort(packet, 0),
            name = name.toString().lowercase(),
            type = type,
            qClass = qClass,
            endOffset = offset,
            recursionDesired = (flags and 0x0100) != 0,
        )
    }

    /**
     * Build the answer for a name the user has refused.
     *
     * For A and AAAA this returns the unroutable address rather than NXDOMAIN. Both are
     * "no", but an address fails immediately and locally, whereas NXDOMAIN sends some
     * clients off to retry against a hard-coded public resolver — which is precisely the
     * side door this feature exists to close.
     */
    fun buildBlockedResponse(query: ByteArray, question: Question): ByteArray {
        val rdata: ByteArray? = when (question.type) {
            TYPE_A -> byteArrayOf(0, 0, 0, 0)
            TYPE_AAAA -> ByteArray(16)
            else -> null
        }

        val questionBytes = question.endOffset
        val answerSize = if (rdata != null) 2 + 2 + 2 + 4 + 2 + rdata.size else 0
        val response = ByteArray(questionBytes + answerSize)

        query.copyInto(response, 0, 0, questionBytes)

        var flags = 0x8000 // QR: this is a response
        if (question.recursionDesired) flags = flags or 0x0100
        flags = flags or 0x0080 // RA: recursion available
        flags = flags or (if (rdata != null) RCODE_NO_ERROR else RCODE_NXDOMAIN)
        writeUShort(response, 2, flags)
        writeUShort(response, 6, if (rdata != null) 1 else 0) // ANCOUNT
        writeUShort(response, 8, 0) // NSCOUNT
        writeUShort(response, 10, 0) // ARCOUNT

        if (rdata != null) {
            var offset = questionBytes
            // Pointer to the name already present in the question section.
            writeUShort(response, offset, 0xC000 or HEADER_SIZE); offset += 2
            writeUShort(response, offset, question.type); offset += 2
            writeUShort(response, offset, CLASS_IN); offset += 2
            writeUInt(response, offset, BLOCK_TTL_SECONDS.toLong()); offset += 4
            writeUShort(response, offset, rdata.size); offset += 2
            rdata.copyInto(response, offset)
        }

        return response
    }

    internal fun readUShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    internal fun writeUShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun writeUInt(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
