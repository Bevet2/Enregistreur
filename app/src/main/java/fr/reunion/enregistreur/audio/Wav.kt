package fr.reunion.enregistreur.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Format audio unique de l'application : WAV PCM 16 bits, mono, 16 kHz. */
object Wav {
    const val SAMPLE_RATE = 16000
    const val HEADER_SIZE = 44
    const val BYTES_PER_SAMPLE = 2

    fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val data = dataBytes.coerceAtMost(0xFFFFFFFFL - 36).toInt()
        b.put("RIFF".toByteArray()); b.putInt(36 + data)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(1) // mono
        b.putInt(SAMPLE_RATE)
        b.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
        b.putShort(BYTES_PER_SAMPLE.toShort())
        b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        return b.array()
    }

    fun durationMs(file: File): Long {
        val data = (file.length() - HEADER_SIZE).coerceAtLeast(0)
        return data / BYTES_PER_SAMPLE * 1000L / SAMPLE_RATE
    }

    /**
     * Réécrit l'en-tête d'après la taille réelle du fichier : répare un enregistrement
     * interrompu brutalement (plantage, téléphone éteint…).
     */
    fun repairHeader(file: File) {
        if (!file.exists() || file.length() < HEADER_SIZE) return
        var data = file.length() - HEADER_SIZE
        if (data % 2L != 0L) data -= 1
        RandomAccessFile(file, "rw").use {
            it.setLength(HEADER_SIZE + data)
            it.seek(0)
            it.write(header(data))
        }
    }
}

/** Écriture au fil de l'eau : chaque bloc lu du micro est écrit immédiatement sur disque. */
class WavWriter(private val file: File) : AutoCloseable {
    private val raf: RandomAccessFile
    private var dataBytes: Long

    init {
        file.parentFile?.mkdirs()
        val existed = file.exists() && file.length() >= Wav.HEADER_SIZE
        raf = RandomAccessFile(file, "rw")
        if (existed) {
            dataBytes = file.length() - Wav.HEADER_SIZE
            raf.seek(file.length())
        } else {
            raf.setLength(0)
            raf.write(Wav.header(0))
            dataBytes = 0
        }
    }

    val samplesWritten: Long get() = dataBytes / Wav.BYTES_PER_SAMPLE

    fun write(pcm: ShortArray, count: Int) {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(pcm[i])
        raf.write(buf.array())
        dataBytes += count * 2L
    }

    /** Met à jour l'en-tête et force l'écriture physique (appelé régulièrement). */
    fun sync() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(Wav.header(dataBytes))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun close() {
        try { sync() } finally { raf.close() }
    }
}

/** Lecture aléatoire d'échantillons float [-1, 1] dans un WAV de l'application. */
class WavReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    val numSamples: Long = (file.length() - Wav.HEADER_SIZE).coerceAtLeast(0) / 2

    fun read(start: Long, count: Int): FloatArray {
        val s = start.coerceIn(0, numSamples)
        val n = count.toLong().coerceAtMost(numSamples - s).toInt().coerceAtLeast(0)
        val bytes = ByteArray(n * 2)
        raf.seek(Wav.HEADER_SIZE + s * 2)
        raf.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { bb.get(it) / 32768f }
    }

    override fun close() = raf.close()
}
