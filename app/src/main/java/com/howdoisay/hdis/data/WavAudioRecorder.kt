package com.howdoisay.hdis.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class WavAudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var outputFile: File? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val recorderMutex = Mutex()

    suspend fun start(scope: CoroutineScope): Result<Unit> = recorderMutex.withLock { withContext(Dispatchers.IO) {
        if (isRecording.get()) return@withContext Result.failure(IllegalStateException("Already recording"))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(SecurityException("Microphone permission is required"))
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minimum <= 0) return@withContext Result.failure(IllegalStateException("Microphone unavailable"))

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minimum.coerceAtLeast(BUFFER_BYTES) * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Result.failure(IllegalStateException("Microphone unavailable"))
        }

        val file = File.createTempFile("hdis-", ".wav", context.cacheDir)
        FileOutputStream(file).use { it.write(ByteArray(WAV_HEADER_BYTES.toInt())) }
        audioRecord = recorder
        outputFile = file
        isRecording.set(true)
        recorder.startRecording()
        recordingJob = scope.launch(Dispatchers.IO) { writePcm(recorder, file) }
        Result.success(Unit)
    } }

    suspend fun stop(): File? = recorderMutex.withLock { withContext(Dispatchers.IO) {
        if (!isRecording.compareAndSet(true, false)) return@withContext null
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        recordingJob?.join()
        recorder?.release()
        audioRecord = null
        recordingJob = null
        outputFile?.also { writeWavHeader(it) }.also { outputFile = null }
    } }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        val file = stop()
        file?.delete()
    }

    private fun writePcm(recorder: AudioRecord, file: File) {
        val buffer = ByteArray(BUFFER_BYTES)
        FileOutputStream(file, true).use { output ->
            while (isRecording.get()) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) output.write(buffer, 0, count)
            }
        }
    }

    private fun writeWavHeader(file: File): File {
        val pcmLength = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0)
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeIntLE((pcmLength + 36).toInt())
            wav.writeBytes("WAVEfmt ")
            wav.writeIntLE(16)
            wav.writeShortLE(1)
            wav.writeShortLE(CHANNELS.toShort())
            wav.writeIntLE(SAMPLE_RATE)
            wav.writeIntLE(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
            wav.writeShortLE((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
            wav.writeShortLE(BITS_PER_SAMPLE.toShort())
            wav.writeBytes("data")
            wav.writeIntLE(pcmLength.toInt())
        }
        return file
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        write(byteArrayOf(value.toByte(), (value.toInt() shr 8).toByte()))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_BYTES = 3_200
        const val WAV_HEADER_BYTES = 44L
    }
}
