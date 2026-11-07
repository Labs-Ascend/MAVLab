package com.ascend.mavlab.core.mavlink

/** common.xml base lengths and CRC_EXTRA for MAVLab's supported message subset. */
internal object MavlinkWire {
    data class Definition(val baseLength: Int, val crcExtra: Int)

    val definitions = mapOf(
        0 to Definition(9, 50), 1 to Definition(31, 124), 11 to Definition(6, 89),
        20 to Definition(20, 214), 21 to Definition(2, 159), 22 to Definition(25, 220),
        23 to Definition(23, 168), 24 to Definition(30, 24), 30 to Definition(28, 39),
        33 to Definition(28, 104), 39 to Definition(37, 254), 40 to Definition(4, 230),
        41 to Definition(4, 28), 42 to Definition(2, 28), 43 to Definition(2, 132),
        44 to Definition(4, 221), 45 to Definition(2, 232), 46 to Definition(2, 11),
        47 to Definition(3, 153), 51 to Definition(4, 196), 66 to Definition(6, 148),
        73 to Definition(37, 38), 74 to Definition(20, 20), 75 to Definition(35, 158),
        76 to Definition(33, 152), 77 to Definition(3, 143), 147 to Definition(36, 154),
        148 to Definition(60, 178), 242 to Definition(52, 104), 245 to Definition(2, 130),
        162 to Definition(8, 189),
        259 to Definition(235, 92), 260 to Definition(5, 146), 261 to Definition(27, 179),
        262 to Definition(18, 12), 263 to Definition(255, 133),
    )

    fun crc(bytes: ByteArray, start: Int, end: Int, extra: Int): Int {
        var crc = 0xffff
        fun accumulate(value: Int) {
            var tmp = value xor (crc and 0xff)
            tmp = (tmp xor (tmp shl 4)) and 0xff
            crc = ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xffff
        }
        for (index in start until end) accumulate(bytes[index].toInt() and 0xff)
        accumulate(extra)
        return crc
    }

    /** Unsigned MAVLink 2; trailing zero truncation retains at least one payload byte. */
    fun frame(messageId: Int, payload: ByteArray, sequence: Int, systemId: Int, componentId: Int): ByteArray {
        val definition = requireNotNull(definitions[messageId])
        require(messageId in 0..0xffffff && payload.size in definition.baseLength..255)
        var size = payload.size
        while (size > 1 && payload[size - 1] == 0.toByte()) size--
        val data = ByteArray(10 + size + 2)
        data[0] = 0xfd.toByte()
        data[1] = size.toByte()
        data[4] = sequence.toByte()
        data[5] = systemId.toByte()
        data[6] = componentId.toByte()
        data[7] = messageId.toByte()
        data[8] = (messageId ushr 8).toByte()
        data[9] = (messageId ushr 16).toByte()
        payload.copyInto(data, 10, endIndex = size)
        val crc = crc(data, 1, data.size - 2, definition.crcExtra)
        data[data.size - 2] = crc.toByte()
        data[data.size - 1] = (crc ushr 8).toByte()
        return data
    }
}
