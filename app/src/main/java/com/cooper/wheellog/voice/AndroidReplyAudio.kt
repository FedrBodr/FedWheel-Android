package com.cooper.wheellog.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import timber.log.Timber
import java.io.File

/**
 * MediaPlayer-backed [ReplyAudio] with the same ducking audio focus as [VoiceSpeaker].
 * One clip at a time: starting a new one stops the previous. Server mp3 goes through a temp file in cacheDir.
 */
class AndroidReplyAudio(private val context: Context) : ReplyAudio {
    private val focus = SpeechFocus(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private var player: MediaPlayer? = null

    override fun playMp3(bytes: ByteArray, onFailed: () -> Unit): Boolean = synchronized(this) {
        release()
        val file = File(context.cacheDir, "fv_reply_${System.nanoTime()}.mp3")
        return try {
            file.writeBytes(bytes)
            start(onFailed, file) { setDataSource(file.absolutePath) }
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).w(e, "reply audio: mp3 start failed")
            file.delete()
            false
        }
    }

    override fun playRaw(name: String, onFailed: () -> Unit): Boolean = synchronized(this) {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) return false
        release()
        return try {
            context.resources.openRawResourceFd(id).use { afd ->
                start(onFailed, null) { setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            }
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).w(e, "reply audio: raw $name start failed")
            false
        }
    }

    private fun start(onFailed: () -> Unit, file: File?, source: MediaPlayer.() -> Unit): Boolean {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(focus.attributes)
            mp.source()
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            file?.delete()
            throw e
        }
        mp.setOnCompletionListener { done(it, file) }
        mp.setOnErrorListener { p, what, extra ->
            Timber.tag(VoicePipeline.TAG).w("reply audio: playback error what=$what extra=$extra")
            done(p, file)
            onFailed()
            true
        }
        val granted = focus.request()
        Timber.tag(VoicePipeline.TAG).i("reply audio: start ${mp.duration} ms (focus=${if (granted) "granted" else "denied"})")
        player = mp
        mp.start()
        return true
    }

    private fun done(mp: MediaPlayer, file: File?) = synchronized(this) {
        if (player === mp) {
            player = null
            focus.abandon()
        }
        try { mp.release() } catch (_: Exception) {}
        file?.delete()
    }

    private fun release() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
            focus.abandon()
        }
        player = null
        context.cacheDir.listFiles { f -> f.name.startsWith("fv_reply") }?.forEach { it.delete() }
    }

    fun shutdown() = synchronized(this) { release() }
}
