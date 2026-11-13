package app.honmani.reader.data.dictionary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A term's definitions (Yomitan glossary JSON) as stored: deflated with a preset sampled from
 * its own dictionary at import ([GlossaryPacker]), or plain where that doesn't make it smaller.
 * The first byte says which.
 */
class Glossary(val bytes: ByteArray) {
    val isPacked: Boolean get() = bytes.firstOrNull() == PACKED

    /** The JSON of a plain glossary; a packed one is unpacked with [GlossaryPacker.unpack] first. */
    val json: String
        get() {
            check(!isPacked) { "Packed glossary; unpack it with its dictionary's preset" }
            return String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        }

    override fun equals(other: Any?) = other is Glossary && bytes.contentEquals(other.bytes)

    override fun hashCode() = bytes.contentHashCode()

    override fun toString() = if (isPacked) "Glossary(${bytes.size} bytes packed)" else "Glossary($json)"

    companion object {
        const val PLAIN: Byte = 0
        const val PACKED: Byte = 1

        fun plain(json: String) = Glossary(byteArrayOf(PLAIN) + json.toByteArray(Charsets.UTF_8))
    }
}

class GlossaryConverters {
    @TypeConverter
    fun toBytes(glossary: Glossary): ByteArray = glossary.bytes

    @TypeConverter
    fun fromBytes(bytes: ByteArray): Glossary = Glossary(bytes)
}

/** The preset a dictionary's glossaries are packed with (see [GlossaryPacker]). */
@Entity(
    tableName = "glossary_preset",
    foreignKeys = [
        ForeignKey(
            entity = DictionaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["dictionary_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class GlossaryPresetEntity(
    @PrimaryKey @ColumnInfo(name = "dictionary_id") val dictionaryId: Long,
    val preset: ByteArray,
)

/**
 * Packs glossaries with raw deflate and a preset dictionary: a sample of the dictionary's own
 * entries, so the structure every entry repeats (structured-content tags, styles, labels) costs
 * almost nothing. Measured on Jitendex: 466 MB of JSON become 59 MB, unpacking an entry takes
 * about 5 µs (desktop), and importing is no slower (less to write). One packer per import; not
 * thread-safe.
 */
class GlossaryPacker(val preset: ByteArray) : AutoCloseable {
    private val deflater = Deflater(LEVEL, true)
    private val buffer = ByteArray(BUFFER_SIZE)
    private val output = ByteArrayOutputStream()

    fun pack(json: String): Glossary {
        val input = json.toByteArray(Charsets.UTF_8)
        deflater.reset()
        deflater.setDictionary(preset)
        deflater.setInput(input)
        deflater.finish()
        output.reset()
        output.write(Glossary.PACKED.toInt())
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
            // Short entries can come out larger; those stay plain.
            if (output.size() > input.size) return Glossary.plain(json)
        }
        return Glossary(output.toByteArray())
    }

    override fun close() = deflater.end()

    companion object {
        /** Most of the gain at the import speed of no preset (16 KB: 4% smaller, half again as slow). */
        const val PRESET_SIZE = 8_000
        private const val LEVEL = 6
        private const val BUFFER_SIZE = 8_192

        /** A preset from [samples] (glossary JSON from the dictionary): entries in a fixed random order, up to [PRESET_SIZE] bytes. */
        fun preset(samples: List<String>): ByteArray {
            val text = ByteArrayOutputStream()
            for (sample in samples.shuffled(Random(SEED))) {
                if (text.size() >= PRESET_SIZE) break
                text.write(sample.toByteArray(Charsets.UTF_8))
            }
            val bytes = text.toByteArray()
            return if (bytes.size > PRESET_SIZE) bytes.copyOfRange(bytes.size - PRESET_SIZE, bytes.size) else bytes
        }

        /**
         * The JSON of [glossary]; [preset] is its dictionary's, needed for packed glossaries. A
         * packed glossary that inflates to more than [maxBytes] throws [DataFormatException], so a
         * corrupt or hostile database row cannot fill the memory.
         */
        fun unpack(glossary: Glossary, preset: ByteArray?, maxBytes: Int = MAX_UNPACKED_BYTES): String {
            if (!glossary.isPacked) return glossary.json
            requireNotNull(preset) { "Packed glossary without a preset" }
            val inflater = Inflater(true)
            try {
                val bytes = glossary.bytes
                inflater.setInput(bytes, 1, bytes.size - 1)
                inflater.setDictionary(preset)
                val output = ByteArrayOutputStream(minOf(bytes.size * 4, maxBytes))
                val buffer = ByteArray(BUFFER_SIZE)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw DataFormatException("Truncated glossary")
                    }
                    if (output.size() + count > maxBytes) throw DataFormatException("Glossary larger than $maxBytes bytes")
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            } finally {
                inflater.end()
            }
        }

        private const val SEED = 7L

        /** Far more than any real glossary (the largest structured ones are some 100 KB). */
        const val MAX_UNPACKED_BYTES = 8 * 1024 * 1024
    }
}
