package app.safephotomark.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Minimal, correct TIFF reader/writer used so this app can import and export
 * TIFF without a network connection. The writer always emits an uncompressed
 * 8-bit RGB or RGBA little-endian TIFF with orientation = 1.
 *
 * The reader handles the common photo cases: 8-bit (and 16-bit downsampled)
 * grayscale, RGB, RGBA and palette images, striped or tiled, with
 * uncompressed, PackBits, LZW, or Deflate compression.
 */
object TiffCodec {
    fun write(bitmap: Bitmap, output: OutputStream, hasAlpha: Boolean) {
        val width = bitmap.width
        val height = bitmap.height
        val samples = if (hasAlpha) 4 else 3
        val bytesPerPixel = samples
        val stripSize = width * height * bytesPerPixel
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val raster = ByteArray(stripSize)
        var i = 0
        for (p in pixels) {
            raster[i++] = Color.red(p).toByte()
            raster[i++] = Color.green(p).toByte()
            raster[i++] = Color.blue(p).toByte()
            if (hasAlpha) {
                raster[i++] = Color.alpha(p).toByte()
            }
        }

        val entries = ArrayList<IfdEntry>()
        fun addShort(tag: Int, values: IntArray) {
            entries.add(IfdEntry(tag, TYPE_SHORT, values.size, shorts = values))
        }
        fun addLong(tag: Int, values: LongArray) {
            entries.add(IfdEntry(tag, TYPE_LONG, values.size, longs = values))
        }
        fun addRational(tag: Int, num: Long, den: Long) {
            entries.add(IfdEntry(tag, TYPE_RATIONAL, 1, rationals = longArrayOf(num, den)))
        }

        addLong(256, longArrayOf(width.toLong()))
        addLong(257, longArrayOf(height.toLong()))
        addShort(258, IntArray(samples) { 8 })
        addShort(259, intArrayOf(1)) // uncompressed
        addShort(262, intArrayOf(2)) // RGB
        addShort(274, intArrayOf(1)) // orientation up
        addLong(273, longArrayOf(0)) // strip offsets, patched
        addShort(277, intArrayOf(samples))
        addLong(278, longArrayOf(height.toLong()))
        addLong(279, longArrayOf(stripSize.toLong()))
        addRational(282, 72, 1)
        addRational(283, 72, 1)
        addShort(296, intArrayOf(2))
        addShort(305, intArrayOf()) // placeholder, software written as ASCII below
        if (hasAlpha) {
            addShort(338, intArrayOf(2)) // unassociated alpha
        }

        // Layout:
        // 8-byte header, IFD, extra value data, software ASCII, then raster.
        val software = "Safe Photo Mark 3.0\u0000".toByteArray(Charsets.US_ASCII)
        // Rebuild entries with software as ASCII
        entries.removeAll { it.tag == 305 }
        entries.add(IfdEntry(305, TYPE_ASCII, software.size, ascii = software))
        entries.sortBy { it.tag }

        val headerSize = 8
        val ifdSize = 2 + entries.size * 12 + 4
        var extra = 0
        for (e in entries) extra += e.overflowBytes()
        val rasterOffset = headerSize + ifdSize + extra
        for (idx in entries.indices) {
            if (entries[idx].tag == 273) {
                entries[idx] = entries[idx].copy(longs = longArrayOf(rasterOffset.toLong()))
            }
        }

        val buf = ByteArrayOutputStream(rasterOffset + stripSize)
        val out = TiffWriter(buf, ByteOrder.LITTLE_ENDIAN)
        out.writeBytes(byteArrayOf('I'.code.toByte(), 'I'.code.toByte()))
        out.writeShort(42)
        out.writeLong(headerSize.toLong()) // IFD offset
        var overflowCursor = headerSize + ifdSize
        out.writeShort(entries.size)
        for (e in entries) {
            overflowCursor = e.write(out, overflowCursor)
        }
        out.writeLong(0) // no next IFD
        // overflow payloads were already written by entries via a side buffer
        out.writeBytes(out.takeOverflow())
        out.writeBytes(raster)
        output.write(buf.toByteArray())
    }

    fun decode(input: InputStream, maxPixelSize: Int? = null): Bitmap {
        val data = input.readBytes()
        if (data.size < 8) throw ImagePipelineException(ImagePipelineError.CANNOT_OPEN_SOURCE)
        val reader = TiffReader(data)
        val bitmap = reader.decode()
        return if (maxPixelSize != null) downsample(bitmap, maxPixelSize) else bitmap
    }

    fun downsample(bitmap: Bitmap, maxPixelSize: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxPixelSize || maxPixelSize <= 0) return bitmap
        val scale = maxPixelSize.toFloat() / longEdge.toFloat()
        val w = maxOf(1, (bitmap.width * scale).toInt())
        val h = maxOf(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}

private const val TYPE_BYTE = 1
private const val TYPE_ASCII = 2
private const val TYPE_SHORT = 3
private const val TYPE_LONG = 4
private const val TYPE_RATIONAL = 5
private const val TYPE_SBYTE = 6
private const val TYPE_UNDEFINED = 7
private const val TYPE_SSHORT = 8
private const val TYPE_SLONG = 9
private const val TYPE_SRATIONAL = 10
private const val TYPE_FLOAT = 11
private const val TYPE_DOUBLE = 12

private data class IfdEntry(
    val tag: Int,
    val type: Int,
    val count: Int,
    val shorts: IntArray? = null,
    val longs: LongArray? = null,
    val rationals: LongArray? = null,
    val ascii: ByteArray? = null
) {
    fun overflowBytes(): Int {
        val size = valueByteCount()
        return if (size > 4) size else 0
    }

    fun valueByteCount(): Int {
        val unit = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
            TYPE_SHORT, TYPE_SSHORT -> 2
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
            else -> 1
        }
        return unit * count
    }

    fun write(writer: TiffWriter, overflowCursor: Int): Int {
        writer.writeShort(tag)
        writer.writeShort(type)
        writer.writeLong(count.toLong())
        val payload = packValues()
        var next = overflowCursor
        if (payload.size <= 4) {
            val padded = ByteArray(4)
            payload.copyInto(padded)
            writer.writeBytes(padded)
        } else {
            writer.writeLong(overflowCursor.toLong())
            writer.appendOverflow(payload)
            next = overflowCursor + payload.size
        }
        return next
    }

    private fun packValues(): ByteArray {
        val size = valueByteCount()
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        when {
            ascii != null -> buf.put(ascii)
            shorts != null -> shorts.forEach { buf.putShort(it.toShort()) }
            longs != null && type != TYPE_RATIONAL && type != TYPE_SRATIONAL ->
                longs.forEach { buf.putInt(it.toInt()) }
            rationals != null -> {
                var i = 0
                while (i < rationals.size) {
                    buf.putInt(rationals[i].toInt())
                    buf.putInt(rationals[i + 1].toInt())
                    i += 2
                }
            }
        }
        return buf.array()
    }
}

private class TiffWriter(private val dest: ByteArrayOutputStream, private val order: ByteOrder) {
    private val overflow = ByteArrayOutputStream()

    fun writeBytes(bytes: ByteArray) {
        dest.write(bytes)
    }

    fun writeShort(value: Int) {
        val b = ByteArray(2)
        ByteBuffer.wrap(b).order(order).putShort(value.toShort())
        dest.write(b)
    }

    fun writeLong(value: Long) {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).order(order).putInt(value.toInt())
        dest.write(b)
    }

    fun appendOverflow(bytes: ByteArray) {
        overflow.write(bytes)
    }

    fun takeOverflow(): ByteArray = overflow.toByteArray()
}

private class TiffReader(private val data: ByteArray) {
    private val order: ByteOrder
    private var pos = 0

    init {
        if (data.size < 8) throw ImagePipelineException(ImagePipelineError.CANNOT_OPEN_SOURCE)
        order = when {
            data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
        }
        pos = 2
        val magic = readUShort()
        if (magic != 42) throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
    }

    fun decode(): Bitmap {
        val ifdOffset = readUInt().toInt()
        val tags = readIfd(ifdOffset)

        val width = tags.int(256) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val height = tags.int(257) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        }
        val compression = tags.int(259) ?: 1
        val photometric = tags.int(262) ?: 2
        val samples = tags.int(277) ?: if (photometric == 2) 3 else 1
        val bits = tags.ints(258) ?: intArrayOf(8)
        val bitDepth = bits.maxOrNull() ?: 8
        if (bitDepth != 8 && bitDepth != 16) {
            throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
        }
        val orientation = tags.int(274) ?: 1
        val planar = tags.int(284) ?: 1
        val predictor = tags.int(317) ?: 1
        val extra = tags.ints(338)

        val raster = when {
            tags.longs(324) != null -> { // tiles
                readTiled(tags, width, height, samples, bitDepth, compression, predictor, planar)
            }
            else -> {
                readStriped(tags, width, height, samples, bitDepth, compression, predictor, planar)
            }
        }

        val palette = if (photometric == 3) tags.ints(320) else null
        val pixels = IntArray(width * height)
        packPixels(pixels, raster, width, height, samples, bitDepth, photometric, palette, extra)
        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap = applyTiffOrientation(bitmap, orientation)
        return bitmap
    }

    private fun readStriped(
        tags: TagMap,
        width: Int,
        height: Int,
        samples: Int,
        bitDepth: Int,
        compression: Int,
        predictor: Int,
        planar: Int
    ): ByteArray {
        val offsets = tags.longs(273) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val counts = tags.longs(279) ?: LongArray(offsets.size) { 0 }
        val rowsPerStrip = (tags.int(278) ?: height).coerceAtLeast(1)
        val bytesPerSample = if (bitDepth == 16) 2 else 1
        val planes = if (planar == 2) samples else 1
        val samplesPerPixel = if (planar == 2) 1 else samples
        val out = ByteArray(width * height * samples * bytesPerSample)
        var stripIndex = 0
        for (plane in 0 until planes) {
            var row = 0
            while (row < height) {
                val rows = minOf(rowsPerStrip, height - row)
                if (stripIndex >= offsets.size) throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
                val start = offsets[stripIndex].toInt()
                val count = if (stripIndex < counts.size && counts[stripIndex] > 0) {
                    counts[stripIndex].toInt()
                } else {
                    data.size - start
                }
                val compressed = data.copyOfRange(start, (start + count).coerceAtMost(data.size))
                val rowBytes = width * samplesPerPixel * bytesPerSample
                val expected = rowBytes * rows
                val decompressed = decompress(compressed, compression, expected)
                val predicted = if (predictor == 2) applyPredictor(decompressed, width, rows, samplesPerPixel, bytesPerSample) else decompressed
                if (planar == 2) {
                    var src = 0
                    for (r in 0 until rows) {
                        val destRow = (row + r) * width * samples * bytesPerSample + plane * bytesPerSample
                        for (x in 0 until width) {
                            val dest = destRow + x * samples * bytesPerSample
                            if (bytesPerSample == 2) {
                                out[dest] = predicted[src++]
                                out[dest + 1] = predicted[src++]
                            } else {
                                out[dest] = predicted[src++]
                            }
                        }
                    }
                } else {
                    val dest = row * width * samples * bytesPerSample
                    val len = minOf(predicted.size, expected, out.size - dest)
                    predicted.copyInto(out, dest, 0, len)
                }
                stripIndex++
                row += rows
            }
        }
        return out
    }

    private fun readTiled(
        tags: TagMap,
        width: Int,
        height: Int,
        samples: Int,
        bitDepth: Int,
        compression: Int,
        predictor: Int,
        planar: Int
    ): ByteArray {
        if (planar == 2) throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
        val tileW = tags.int(322) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val tileH = tags.int(323) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val offsets = tags.longs(324) ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val counts = tags.longs(325) ?: LongArray(offsets.size) { 0 }
        val bytesPerSample = if (bitDepth == 16) 2 else 1
        val out = ByteArray(width * height * samples * bytesPerSample)
        val tilesAcross = (width + tileW - 1) / tileW
        val tilesDown = (height + tileH - 1) / tileH
        var index = 0
        for (ty in 0 until tilesDown) {
            for (tx in 0 until tilesAcross) {
                val start = offsets[index].toInt()
                val count = if (index < counts.size && counts[index] > 0) counts[index].toInt() else data.size - start
                val compressed = data.copyOfRange(start, (start + count).coerceAtMost(data.size))
                val rowBytes = tileW * samples * bytesPerSample
                val expected = rowBytes * tileH
                val decompressed = decompress(compressed, compression, expected)
                val predicted = if (predictor == 2) applyPredictor(decompressed, tileW, tileH, samples, bytesPerSample) else decompressed
                val destX = tx * tileW
                val destY = ty * tileH
                val copyW = minOf(tileW, width - destX)
                val copyH = minOf(tileH, height - destY)
                for (row in 0 until copyH) {
                    val src = row * rowBytes
                    val dest = ((destY + row) * width + destX) * samples * bytesPerSample
                    val len = copyW * samples * bytesPerSample
                    if (src + len <= predicted.size && dest + len <= out.size) {
                        predicted.copyInto(out, dest, src, src + len)
                    }
                }
                index++
            }
        }
        return out
    }

    private fun packPixels(
        pixels: IntArray,
        raster: ByteArray,
        width: Int,
        height: Int,
        samples: Int,
        bitDepth: Int,
        photometric: Int,
        palette: IntArray?,
        extra: IntArray?
    ) {
        val bytesPerSample = if (bitDepth == 16) 2 else 1
        fun sample(offset: Int): Int {
            return if (bytesPerSample == 2) {
                val i = if (order == ByteOrder.LITTLE_ENDIAN) {
                    (raster[offset].toInt() and 0xFF) or ((raster[offset + 1].toInt() and 0xFF) shl 8)
                } else {
                    (raster[offset + 1].toInt() and 0xFF) or ((raster[offset].toInt() and 0xFF) shl 8)
                }
                i / 257
            } else {
                raster[offset].toInt() and 0xFF
            }
        }
        val hasAlpha = samples >= 4 || (extra != null && extra.isNotEmpty() && samples >= 2 && photometric != 3)
        var src = 0
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = when (photometric) {
                    0, 1 -> { // gray, 0 is white or black
                        val g = sample(src)
                        src += bytesPerSample * samples
                        val v = if (photometric == 0) 255 - g else g
                        val a = if (hasAlpha && samples >= 2) sample(src - bytesPerSample) else 255
                        Color.argb(a, v, v, v)
                    }
                    3 -> {
                        val index = sample(src)
                        src += bytesPerSample * samples
                        val r: Int
                        val g: Int
                        val b: Int
                        if (palette != null && palette.size >= 768) {
                            // TIFF palettes are 16-bit, length 3*256 typically
                            val n = palette.size / 3
                            val idx = index.coerceIn(0, n - 1)
                            r = palette[idx] / 257
                            g = palette[n + idx] / 257
                            b = palette[2 * n + idx] / 257
                        } else {
                            r = index; g = index; b = index
                        }
                        Color.argb(255, r, g, b)
                    }
                    else -> { // RGB
                        val r = sample(src)
                        val g = sample(src + bytesPerSample)
                        val b = sample(src + 2 * bytesPerSample)
                        val a = if (samples >= 4) sample(src + 3 * bytesPerSample) else 255
                        src += bytesPerSample * samples
                        Color.argb(a, r, g, b)
                    }
                }
                pixels[i++] = color
            }
        }
    }

    private fun decompress(input: ByteArray, compression: Int, expected: Int): ByteArray {
        return when (compression) {
            1 -> {
                if (input.size >= expected) input.copyOf(expected) else input
            }
            32773 -> packBits(input, expected)
            5 -> lzw(input, expected)
            8, 32946 -> inflate(input, expected)
            else -> throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
        }
    }

    private fun packBits(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var i = 0
        var o = 0
        while (i < input.size && o < expected) {
            val n = input[i++].toInt()
            when {
                n in 0..127 -> {
                    val count = n + 1
                    val end = minOf(input.size, i + count)
                    val len = minOf(end - i, expected - o)
                    input.copyInto(out, o, i, i + len)
                    o += len
                    i += count
                }
                n == -128 -> { /* no-op */ }
                else -> {
                    if (i >= input.size) break
                    val value = input[i++]
                    val count = 1 - n // n is in -127..-1
                    val len = minOf(count, expected - o)
                    java.util.Arrays.fill(out, o, o + len, value)
                    o += len
                }
            }
        }
        return out
    }

    private fun inflate(input: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArray(expected)
            val n = inflater.inflate(out)
            return if (n == expected) out else out.copyOf(n)
        } catch (t: Throwable) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE, t.message)
        } finally {
            inflater.end()
        }
    }

    private fun lzw(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val reader = LzwBits(input, order == ByteOrder.BIG_ENDIAN)
        val clear = 256
        val eoi = 257
        var bits = 9
        val table = Array(4096) { ByteArray(0) }
        for (i in 0..255) table[i] = byteArrayOf(i.toByte())
        var nextCode = 258
        var written = 0
        var prev: ByteArray? = null
        reader.bitCount = bits
        while (written < expected) {
            val code = reader.read(bits) ?: break
            if (code == eoi) break
            if (code == clear) {
                bits = 9
                reader.bitCount = bits
                nextCode = 258
                prev = null
                continue
            }
            val entry: ByteArray = when {
                code < nextCode -> table[code]
                code == nextCode && prev != null -> prev + byteArrayOf(prev[0])
                else -> break
            }
            val copy = minOf(entry.size, expected - written)
            entry.copyInto(out, written, 0, copy)
            written += copy
            if (prev != null && nextCode < 4096) {
                table[nextCode] = prev + byteArrayOf(entry[0])
                nextCode++
                when (nextCode) {
                    512 -> bits = 10
                    1024 -> bits = 11
                    2048 -> bits = 12
                }
                reader.bitCount = bits
            }
            prev = entry
        }
        return out
    }

    private fun applyPredictor(
        data: ByteArray,
        width: Int,
        rows: Int,
        samples: Int,
        bytesPerSample: Int
    ): ByteArray {
        val rowBytes = width * samples * bytesPerSample
        val out = data.copyOf()
        for (r in 0 until rows) {
            val rowStart = r * rowBytes
            val step = samples * bytesPerSample
            for (x in 1 until width) {
                val dest = rowStart + x * step
                val prev = dest - step
                for (s in 0 until step) {
                    out[dest + s] = ((out[dest + s].toInt() + out[prev + s].toInt()) and 0xFF).toByte()
                }
            }
        }
        return out
    }

    private fun applyTiffOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == 1) return bitmap
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun readIfd(offset: Int): TagMap {
        pos = offset
        val count = readUShort()
        val map = TagMap()
        repeat(count) {
            val tag = readUShort()
            val type = readUShort()
            val cnt = readUInt().toInt()
            val valueOffsetPos = pos
            val inline = readBytes(4)
            map.put(tag, readValues(tag, type, cnt, valueOffsetPos, inline))
        }
        return map
    }

    private fun readValues(tag: Int, type: Int, count: Int, valueOffsetPos: Int, inline: ByteArray): TagValue {
        val unit = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
            TYPE_SHORT, TYPE_SSHORT -> 2
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
            else -> 1
        }
        val size = unit * count
        val bytes = if (size <= 4) {
            inline.copyOf(size)
        } else {
            val off = asUInt(inline, order).toInt()
            if (off < 0 || off + size > data.size) ByteArray(0) else data.copyOfRange(off, off + size)
        }
        val bb = ByteBuffer.wrap(bytes).order(order)
        val ints = ArrayList<Long>()
        when (type) {
            TYPE_BYTE, TYPE_UNDEFINED, TYPE_ASCII, TYPE_SBYTE -> {
                for (b in bytes) ints.add((b.toInt() and 0xFF).toLong())
            }
            TYPE_SHORT, TYPE_SSHORT -> {
                while (bb.remaining() >= 2) ints.add(bb.short.toInt().and(0xFFFF).toLong())
            }
            TYPE_LONG, TYPE_SLONG -> {
                while (bb.remaining() >= 4) ints.add(bb.int.toLong() and 0xFFFFFFFFL)
            }
            TYPE_RATIONAL, TYPE_SRATIONAL -> {
                while (bb.remaining() >= 8) {
                    ints.add(bb.int.toLong() and 0xFFFFFFFFL)
                    ints.add(bb.int.toLong() and 0xFFFFFFFFL)
                }
            }
            else -> {}
        }
        return TagValue(type, ints.toLongArray(), bytes)
    }

    private fun readUShort(): Int {
        if (pos + 2 > data.size) throw EOFException()
        val v = asUShort(data, pos, order)
        pos += 2
        return v
    }

    private fun readUInt(): Long {
        if (pos + 4 > data.size) throw EOFException()
        val v = asUInt(data.copyOfRange(pos, pos + 4), order)
        pos += 4
        return v
    }

    private fun readBytes(n: Int): ByteArray {
        if (pos + n > data.size) throw EOFException()
        val slice = data.copyOfRange(pos, pos + n)
        pos += n
        return slice
    }
}

private class TagValue(val type: Int, val values: LongArray, val raw: ByteArray)

private class TagMap {
    private val map = HashMap<Int, TagValue>()
    fun put(tag: Int, value: TagValue) { map[tag] = value }
    fun int(tag: Int): Int? = map[tag]?.values?.firstOrNull()?.toInt()
    fun ints(tag: Int): IntArray? = map[tag]?.values?.map { it.toInt() }?.toIntArray()
    fun longs(tag: Int): LongArray? = map[tag]?.values
}

private fun asUShort(data: ByteArray, pos: Int, order: ByteOrder): Int {
    return if (order == ByteOrder.LITTLE_ENDIAN) {
        (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    } else {
        (data[pos + 1].toInt() and 0xFF) or ((data[pos].toInt() and 0xFF) shl 8)
    }
}

private fun asUInt(bytes: ByteArray, order: ByteOrder): Long {
    val b = ByteBuffer.wrap(bytes).order(order)
    return b.int.toLong() and 0xFFFFFFFFL
}

private class LzwBits(private val data: ByteArray, private val msbFirst: Boolean) {
    var bitCount = 9
    private var bitPos = 0

    fun read(bits: Int): Int? {
        val start = bitPos
        val end = start + bits
        if (end > data.size * 8) return null
        var acc = 0
        var remaining = bits
        var pos = start
        while (remaining > 0) {
            val byteIndex = pos / 8
            val bitIndex = pos % 8
            val take = minOf(8 - bitIndex, remaining)
            val byte = data[byteIndex].toInt() and 0xFF
            val slice = if (msbFirst) {
                (byte shr (8 - bitIndex - take)) and ((1 shl take) - 1)
            } else {
                (byte shr bitIndex) and ((1 shl take) - 1)
            }
            acc = if (msbFirst) {
                (acc shl take) or slice
            } else {
                acc or (slice shl (bits - remaining))
            }
            remaining -= take
            pos += take
        }
        bitPos = end
        return acc
    }
}
