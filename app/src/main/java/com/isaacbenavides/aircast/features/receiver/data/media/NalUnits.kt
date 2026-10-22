package com.isaacbenavides.aircast.features.receiver.data.media

/** Minimal Annex-B helpers for the H.264/H.265 access units produced by the mirror RTP channel. */
internal object NalUnits {

    class Nal(val type: Int, val start: Int, val end: Int)

    private const val H264_SPS = 7
    private const val H264_PPS = 8
    private const val H265_VPS = 32
    private const val H265_SPS = 33
    private const val H265_PPS = 34

    fun startsWithParameterSet(data: ByteArray, isH265: Boolean): Boolean {
        val payload = firstPayloadOffset(data, 0) ?: return false
        return isParameterSet(nalType(data[payload], isH265), isH265)
    }

    /** Leading VPS/SPS/PPS NAL units (start codes included), concatenated. */
    fun parameterSets(data: ByteArray, isH265: Boolean): ByteArray {
        val leading = split(data, isH265).takeWhile { isParameterSet(it.type, isH265) }
        if (leading.isEmpty()) return ByteArray(0)
        return data.copyOfRange(leading.first().start, leading.last().end)
    }

    fun split(data: ByteArray, isH265: Boolean): List<Nal> {
        val starts = mutableListOf<Pair<Int, Int>>() // (start-code offset, payload offset)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexedNotNull { index, (start, payload) ->
            if (payload >= data.size) return@mapIndexedNotNull null
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            Nal(nalType(data[payload], isH265), start, end)
        }
    }

    private fun firstPayloadOffset(data: ByteArray, from: Int): Int? {
        if (data.size >= from + 4 && data[from].toInt() == 0 && data[from + 1].toInt() == 0) {
            if (data[from + 2].toInt() == 1) return from + 3
            if (data[from + 2].toInt() == 0 && data[from + 3].toInt() == 1 && data.size > from + 4) return from + 4
        }
        return null
    }

    private fun nalType(header: Byte, isH265: Boolean): Int =
        if (isH265) (header.toInt() shr 1) and 0x3F else header.toInt() and 0x1F

    private fun isParameterSet(type: Int, isH265: Boolean): Boolean =
        if (isH265) type == H265_VPS || type == H265_SPS || type == H265_PPS
        else type == H264_SPS || type == H264_PPS
}
