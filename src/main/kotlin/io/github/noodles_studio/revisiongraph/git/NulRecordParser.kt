package io.github.noodles_studio.revisiongraph.git

import java.io.InputStream

/** Incrementally reads NUL-terminated fields without retaining raw command output. */
class NulRecordParser(
    private val fieldsPerRecord: Int,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxFieldBytes: Int = DEFAULT_MAX_FIELD_BYTES,
) {
    init {
        require(fieldsPerRecord > 0)
        require(maxTotalBytes > 0)
        require(maxFieldBytes > 0)
    }

    fun parse(input: InputStream, cancelled: () -> Boolean = { false }, consume: (List<String>) -> Unit) {
        val field = java.io.ByteArrayOutputStream()
        val record = ArrayList<String>(fieldsPerRecord)
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        while (true) {
            if (cancelled()) throw InterruptedException("Git output parsing cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > maxTotalBytes) {
                throw GitOutputLimitException("Git history output exceeded $maxTotalBytes bytes")
            }
            for (i in 0 until count) {
                if (buffer[i].toInt() == 0) {
                    record += field.toString(Charsets.UTF_8)
                    field.reset()
                    if (record.size == fieldsPerRecord) {
                        consume(record.toList())
                        record.clear()
                    }
                } else {
                    if (field.size() >= maxFieldBytes) {
                        throw GitOutputLimitException("Git history field exceeded $maxFieldBytes bytes")
                    }
                    field.write(buffer[i].toInt())
                }
            }
        }
        if (field.size() > 0 || record.isNotEmpty()) {
            throw IllegalArgumentException("Truncated NUL record: ${record.size} complete fields")
        }
    }

    private companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_MAX_FIELD_BYTES = 1024 * 1024
    }
}
