package io.github.noodles_studio.revisiongraph.git

import java.io.InputStream

/** Incrementally reads NUL-terminated fields without retaining raw command output. */
class NulRecordParser(private val fieldsPerRecord: Int) {
    init { require(fieldsPerRecord > 0) }

    fun parse(input: InputStream, cancelled: () -> Boolean = { false }, consume: (List<String>) -> Unit) {
        val field = ArrayList<Byte>()
        val record = ArrayList<String>(fieldsPerRecord)
        val buffer = ByteArray(8192)
        while (true) {
            if (cancelled()) throw InterruptedException("Git output parsing cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                if (buffer[i].toInt() == 0) {
                    record += field.toByteArray().toString(Charsets.UTF_8)
                    field.clear()
                    if (record.size == fieldsPerRecord) {
                        consume(record.toList())
                        record.clear()
                    }
                } else field += buffer[i]
            }
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            throw IllegalArgumentException("Truncated NUL record: ${record.size} complete fields")
        }
    }
}
