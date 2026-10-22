package com.isaacbenavides.aircast.features.receiver.data.media

/** Track fields from the DMAP ("now playing") blob a client sends with SET_PARAMETER. */
internal data class DmapMetadata(val title: String?, val artist: String?, val album: String?) {

    companion object {
        private val CONTAINERS = setOf("mlit", "mlcl", "cmst")

        fun parse(data: ByteArray): DmapMetadata {
            val fields = HashMap<String, String>()
            collect(data, 0, data.size, fields)
            return DmapMetadata(
                title = fields["minm"]?.ifBlank { null },
                artist = fields["asar"]?.ifBlank { null },
                album = fields["asal"]?.ifBlank { null },
            )
        }

        private fun collect(data: ByteArray, from: Int, to: Int, out: MutableMap<String, String>) {
            var offset = from
            while (offset + 8 <= to) {
                val tag = String(data, offset, 4, Charsets.US_ASCII)
                val length = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 5].toInt() and 0xFF) shl 16) or
                    ((data[offset + 6].toInt() and 0xFF) shl 8) or
                    (data[offset + 7].toInt() and 0xFF)
                val valueStart = offset + 8
                if (length < 0 || valueStart + length > to) return
                if (tag in CONTAINERS) {
                    collect(data, valueStart, valueStart + length, out)
                } else {
                    out[tag] = String(data, valueStart, length, Charsets.UTF_8)
                }
                offset = valueStart + length
            }
        }
    }
}
