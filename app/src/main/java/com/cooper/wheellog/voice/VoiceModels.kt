package com.cooper.wheellog.voice

/** State of one watch voice session as exposed by GET /voice/result. */
data class VoiceResult(
    val state: String,
    val text: String = "",
    val reply: String = "",
    val needsConfirm: Boolean = false,
) {
    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val ERROR = "error"

        fun error(message: String, text: String = "") = VoiceResult(ERROR, text, message, false)
    }
}

data class PendingAction(val id: String, val summary: String)

data class AskResult(val reply: String, val pendingAction: PendingAction?)

/** Decoded mono 16-bit PCM. */
class Pcm(val samples: ShortArray, val sampleRate: Int)

/** Error with a short Russian message suitable for the watch screen and TTS. */
class VoiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AudioDecoder {
    fun decode(bytes: ByteArray): Pcm
}

/** Speech recognizer abstraction, so Android SpeechRecognizer can be swapped for Whisper later. */
interface SpeechToText {
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String
}

/** The server agent (LLM calls live there, never on the phone). */
interface VoiceAgent {
    fun ask(text: String, session: String): AskResult
    fun confirm(actionId: String, confirm: Boolean): String
}

interface Speaker {
    fun speak(text: String)
}

object FedVoiceAnnounce {
    const val APP_ID = "28cd12eb-0005-43c3-ae75-2e90bc0e3f9f"

    /** Connect IQ message announcing the voice server port to the FedVoice watch app. */
    fun message(port: Int): List<Any> = listOf("fv", port)

    /**
     * Messages for the FedWheel watch app (wheel dashboard with push-to-talk): its wheel
     * server port (plain Number, as WheelLog always sent) and, when the voice bridge runs,
     * the voice port in the same `["fv", port]` shape FedVoice uses.
     */
    fun fedWheelMessages(wheelPort: Int, voicePort: Int?): List<Any> =
        if (voicePort == null) listOf(wheelPort) else listOf(wheelPort, message(voicePort))
}
