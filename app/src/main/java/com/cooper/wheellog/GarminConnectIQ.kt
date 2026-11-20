package com.cooper.wheellog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cooper.wheellog.utils.Alarms
import com.cooper.wheellog.utils.Constants
import com.cooper.wheellog.utils.*
import com.cooper.wheellog.utils.SomeUtil.playBeep
import com.cooper.wheellog.voice.AndroidSpeechToText
import com.cooper.wheellog.voice.FedVoiceAnnounce
import com.cooper.wheellog.voice.OggOpusDecoder
import com.cooper.wheellog.voice.VoiceBridgeHandler
import com.cooper.wheellog.voice.VoiceBridgeServer
import com.cooper.wheellog.voice.VoicePipeline
import com.cooper.wheellog.voice.VoiceServerClient
import com.cooper.wheellog.voice.VoiceSessionStore
import com.cooper.wheellog.voice.VoiceSpeaker
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.*
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.*

class GarminConnectIQ : Service(), IQApplicationInfoListener, IQDeviceEventListener, IQApplicationEventListener, ConnectIQListener {
    private val appConfig: AppConfig by inject()
    private val notifications: NotificationUtil by inject()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var announceLoopRunning = false
    private var mSdkReady = false
    private var mConnectIQ: ConnectIQ? = getInstance(this, IQConnectType.WIRELESS)
    @Volatile private var mDevice: IQDevice? = null
    @Volatile private var deviceConnected = false
    private var mApp: IQApp? = null
    private val voiceApp = IQApp(FedVoiceAnnounce.APP_ID)
    @Volatile private var mWebServer: GarminConnectIQWebServer? = null
    private var useBeta = appConfig.useGarminBetaCompanion

    // FedVoice
    @Volatile private var voiceServer: VoiceBridgeServer? = null
    private var voiceSpeaker: VoiceSpeaker? = null
    private var voiceStt: AndroidSpeechToText? = null
    private var voicePipeline: VoicePipeline? = null
    private var voiceScope: CoroutineScope? = null
    private var debugReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent): IBinder? {
        Timber.i("onBind")
        return null
    }

    // intent is null when the system restarts a START_STICKY service
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")
        instance = this

        // Setup Connect IQ
        mApp = if (useBeta) {
            IQApp(BETA_APP_ID)
        } else {
            IQApp(STABLE_APP_ID)
        }
        startForegroundCompat()
        if (mConnectIQ != null && !mSdkReady) {
            try {
                mConnectIQ?.initialize(this, false, this)
            } catch (e: Exception) {
                Timber.e(e, "ConnectIQ initialize failed")
            }
        }
        startVoiceBridge()
        startAnnounceLoop()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = notifications.notification
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || notification == null) {
            startForeground(Constants.MAIN_NOTIFICATION_ID, notification)
            return
        }
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // The microphone type lets the speech recognizer receive our audio while the app is in background.
        val canMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canMic) {
            try {
                ServiceCompat.startForeground(this, Constants.MAIN_NOTIFICATION_ID, notification,
                    base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                Timber.tag(VoicePipeline.TAG).i("foreground service: connectedDevice|microphone")
                return
            } catch (e: Exception) {
                Timber.tag(VoicePipeline.TAG).w(e, "foreground service: microphone type refused")
            }
        } else {
            Timber.tag(VoicePipeline.TAG).w("foreground service: RECORD_AUDIO not granted, no microphone type")
        }
        ServiceCompat.startForeground(this, Constants.MAIN_NOTIFICATION_ID, notification, base)
    }

    override fun onDestroy() {
        Timber.d("onDestroy")
        super.onDestroy()
        stopAnnounceLoop()
        try {
            mConnectIQ?.unregisterAllForEvents()
            mConnectIQ?.shutdown(this)
        } catch (e: InvalidStateException) {
            // This is usually because the SDK was already shut down
            // so no worries.
        }
        stopWebServer()
        stopVoiceBridge()
        unregisterWithDevice()
        instance = null
    }

    // General METHODS
    private fun populateDeviceList() {
        Timber.d("populateDeviceList")
        try {
            val mDevices = mConnectIQ?.knownDevices
            if (mDevices != null && mDevices.isNotEmpty()) {
                mDevice = mDevices[0]
                registerWithDevice()
            }
        } catch (e: InvalidStateException) {
            // This generally means you forgot to call initialize(), but since
            // we are in the callback for initialize(), this should never happen
        } catch (e: ServiceUnavailableException) {
            // This will happen if for some reason your app was not able to connect
            // to the ConnectIQ service running within Garmin Connect Mobile.  This
            // could be because Garmin Connect Mobile is not installed or needs to
            // be upgraded.
            Toast.makeText(this, R.string.garmin_connectiq_service_unavailable_message, Toast.LENGTH_LONG).show()
        }
    }

    private fun registerWithDevice() {
        Timber.d("registerWithDevice")
        if (mDevice != null && mSdkReady) {
            // Register for device status updates
            try {
                mConnectIQ?.registerForDeviceEvents(mDevice, this)
            } catch (e: InvalidStateException) {
                Timber.wtf("InvalidStateException:  We should not be here!")
            }

            // Register for application status updates
            try {
                if (useBeta) {
                    mConnectIQ?.getApplicationInfo(BETA_APP_ID, mDevice, this)
                } else {
                    mConnectIQ?.getApplicationInfo(STABLE_APP_ID, mDevice, this)
                }
            } catch (e1: InvalidStateException) {
                Timber.d("e1: ${e1.message}")
            } catch (e1: ServiceUnavailableException) {
                Timber.d("e2: ${e1.message}")
            }

            // Register to receive messages from the device
            try {
                mConnectIQ?.registerForAppEvents(mDevice, mApp, this)
            } catch (e: InvalidStateException) {
                Toast.makeText(this, "ConnectIQ is not in a valid state", Toast.LENGTH_LONG).show()
            }
            // FedVoice: receive its hello messages to answer with the voice port right away
            try {
                mConnectIQ?.registerForAppEvents(mDevice, voiceApp, this)
            } catch (e: Exception) {
                Timber.tag(VoicePipeline.TAG).w(e, "registerForAppEvents(FedVoice) failed")
            }
        }
    }

    private fun unregisterWithDevice() {
        Timber.d("unregisterWithDevice")
        if (mDevice != null && mSdkReady) {
            // It is a good idea to unregister everything and shut things down to
            // release resources and prevent unwanted callbacks.
            try {
                mConnectIQ?.unregisterForDeviceEvents(mDevice)
                if (mApp != null) {
                    mConnectIQ?.unregisterForApplicationEvents(mDevice, mApp)
                }
                mConnectIQ?.unregisterForApplicationEvents(mDevice, voiceApp)
            } catch (ignored: InvalidStateException) {

            }
        }
    }

    // IQApplicationInfoListener METHODS
    override fun onApplicationInfoReceived(app: IQApp) {
        Timber.d("onApplicationInfoReceived")
        Timber.d(app.toString())
    }

    override fun onApplicationNotInstalled(arg0: String) {
        // Sideloaded watch apps may be reported as not installed, so neither stop announcing
        // nor push the store page here.
        Timber.w("onApplicationNotInstalled: $arg0 (ignored, sideloaded apps are reported this way)")
    }

    // IQDeviceEventListener METHODS
    override fun onDeviceStatusChanged(device: IQDevice, status: IQDeviceStatus) {
        Timber.d("onDeviceStatusChanged")
        Timber.d("status is: ${status.name}")
        if (status.name == "CONNECTED") {
            deviceConnected = true
            startWebServer()
            voiceAnnouncer.reset()
        } else {
            deviceConnected = false
            stopWebServer()
        }
    }

    // IQApplicationEventListener
    override fun onMessageReceived(device: IQDevice, app: IQApp, message: List<Any>, status: IQMessageStatus) {
        Timber.d("onMessageReceived from ${app.applicationId}: $message")
        // Data goes through the web servers; any message is treated as "hello, what is your port?"
        mainHandler.post {
            if (app.applicationId.equals(FedVoiceAnnounce.APP_ID, ignoreCase = true)) {
                voiceAnnouncer.reset()
                sendVoicePort()
            } else {
                portAnnouncer.reset()
                sendPort()
            }
        }
    }

    // ConnectIQListener METHODS
    override fun onInitializeError(errStatus: IQSdkErrorStatus) {
        Timber.d("sdk initialization error")
        mSdkReady = false
    }

    override fun onSdkReady() {
        Timber.d("sdk is ready")
        mSdkReady = true
        populateDeviceList()
    }

    override fun onSdkShutDown() {
        Timber.d("sdk shut down")
        mSdkReady = false
    }

    private val portAnnouncer = GarminPortAnnouncer()
    private val voiceAnnouncer = GarminPortAnnouncer()

    private fun startWebServer() {
        Timber.d("startWebServer")
        portAnnouncer.reset()
        if (mWebServer != null) return
        try {
            mWebServer = GarminConnectIQWebServer(applicationContext) {
                portAnnouncer.onWatchRequest(System.currentTimeMillis())
            }
            Timber.d("port is: ${mWebServer!!.listeningPort}")
            sendPort()
        } catch (e: IOException) {
            Timber.e(e, "IOException happened while starting ConnectIQ web server")
        }
    }

    // Upstream sent the port only once, so a watch app opened later never connected.
    // Re-send it (with backoff) while the watch is not polling the web server. Runs on the main
    // thread: Connect IQ calls must not throw on a Timer thread (that kills the whole app).
    private val announceTick = object : Runnable {
        override fun run() {
            if (!announceLoopRunning) return
            val now = System.currentTimeMillis()
            try {
                if (mWebServer != null && portAnnouncer.shouldAnnounce(now)) sendPort()
                if (voiceServer != null && deviceConnected && voiceAnnouncer.shouldAnnounce(now)) sendVoicePort()
            } catch (e: Exception) {
                Timber.e(e, "port announce failed")
            }
            mainHandler.postDelayed(this, ANNOUNCE_TICK_MS)
        }
    }

    private fun startAnnounceLoop() {
        if (announceLoopRunning) return
        announceLoopRunning = true
        mainHandler.postDelayed(announceTick, ANNOUNCE_TICK_MS)
    }

    private fun stopAnnounceLoop() {
        announceLoopRunning = false
        mainHandler.removeCallbacks(announceTick)
    }

    private fun sendPort() {
        val server = mWebServer ?: return
        portAnnouncer.onAnnounced(System.currentTimeMillis())
        sendToWatch(mApp, server.listeningPort, "port")
    }

    private fun sendVoicePort() {
        val server = voiceServer ?: return
        if (mDevice == null) return
        voiceAnnouncer.onAnnounced(System.currentTimeMillis())
        sendToWatch(voiceApp, FedVoiceAnnounce.message(server.listeningPort), "fv port")
    }

    private fun sendToWatch(app: IQApp?, message: Any, what: String) {
        val device = mDevice ?: return
        try {
            mConnectIQ?.sendMessage(device, app, message) { _: IQDevice?, _: IQApp?, status: IQMessageStatus ->
                Timber.d("$what message status: ${status.name}")
            }
        } catch (e: InvalidStateException) {
            Timber.e("ConnectIQ is not in a valid state")
        } catch (e: ServiceUnavailableException) {
            Timber.e("ConnectIQ service is unavailable. Is Garmin Connect Mobile installed and running?")
        } catch (e: Exception) {
            Timber.e(e, "ConnectIQ sendMessage($what) failed")
        }
    }

    private fun stopWebServer() {
        Timber.d("stopWebServer")
        mWebServer?.stop()
        mWebServer = null
    }

    // region FedVoice
    private fun startVoiceBridge() {
        if (voiceServer != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.tag(VoicePipeline.TAG).w("voice bridge needs Android 10+ (Ogg Opus decode)")
            return
        }
        val speaker = VoiceSpeaker(applicationContext).also { voiceSpeaker = it }
        val stt = AndroidSpeechToText(applicationContext).also { voiceStt = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { voiceScope = it }
        val store = VoiceSessionStore()
        val pipeline = VoicePipeline(
            store = store,
            decoder = OggOpusDecoder(cacheDir),
            stt = stt,
            agent = VoiceServerClient(baseUrl = { appConfig.voiceServerUrl }, token = { appConfig.voiceServerToken }),
            speaker = speaker,
            scope = scope,
        ).also { voicePipeline = it }
        try {
            voiceServer = VoiceBridgeServer(VoiceBridgeHandler(store, pipeline)) {
                voiceAnnouncer.onWatchRequest(System.currentTimeMillis())
            }
            Timber.tag(VoicePipeline.TAG).i("voice server on 127.0.0.1:${voiceServer!!.listeningPort}, " +
                "server url ${if (appConfig.voiceServerUrl.isBlank()) "NOT SET" else "set"}, " +
                "RECORD_AUDIO=${ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}")
        } catch (e: IOException) {
            Timber.tag(VoicePipeline.TAG).e(e, "cannot start voice server")
        }
        try {
            stt.logDiagnostics()
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).w(e, "diag failed")
        }
        if (BuildConfig.DEBUG) registerDebugReceiver(store)
    }

    /**
     * Debug-only adb hooks, e.g.:
     *  adb shell am broadcast -a com.cooper.wheellog.debug.FEDVOICE --es speak "Привет"
     *  adb shell am broadcast -a com.cooper.wheellog.debug.FEDVOICE --es file /data/local/tmp/q.ogg
     *  adb shell am broadcast -a com.cooper.wheellog.debug.FEDVOICE --es ask "Который час"
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDebugReceiver(store: VoiceSessionStore) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val scope = voiceScope ?: return
                intent.getStringExtra("speak")?.let { voiceSpeaker?.speak(it) }
                intent.getStringExtra("file")?.let { path ->
                    scope.launch {
                        try {
                            val bytes = File(path).readBytes()
                            val id = store.start()
                            store.end(id)
                            Timber.tag(VoicePipeline.TAG).i("debug: file $path (${bytes.size} bytes) as session $id")
                            voicePipeline?.process(id, bytes)
                            Timber.tag(VoicePipeline.TAG).i("debug: result ${store.result(id)}")
                        } catch (e: Exception) {
                            Timber.tag(VoicePipeline.TAG).e(e, "debug: file failed")
                        }
                    }
                }
                intent.getStringExtra("ask")?.let { text ->
                    scope.launch {
                        try {
                            val answer = VoiceServerClient({ appConfig.voiceServerUrl }, { appConfig.voiceServerToken }).ask(text, "debug")
                            Timber.tag(VoicePipeline.TAG).i("debug: ask -> $answer")
                            voiceSpeaker?.speak(answer.reply)
                        } catch (e: Exception) {
                            Timber.tag(VoicePipeline.TAG).e(e, "debug: ask failed")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(DEBUG_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
        Timber.tag(VoicePipeline.TAG).i("debug receiver registered: $DEBUG_ACTION")
    }

    private fun stopVoiceBridge() {
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        debugReceiver = null
        voiceServer?.stop()
        voiceServer = null
        voiceScope?.cancel()
        voiceScope = null
        voiceSpeaker?.shutdown()
        voiceSpeaker = null
        voiceStt = null
        voicePipeline = null
    }
    // endregion

    companion object {
        const val STABLE_APP_ID = "487e6172-972c-4f93-a4db-26fd689f935a"
        const val BETA_APP_ID = "433c30dc-f316-4d11-a16e-de153d297705"
        private const val ANNOUNCE_TICK_MS = 1_000L
        private const val DEBUG_ACTION = "com.cooper.wheellog.debug.FEDVOICE"

        private var instance: GarminConnectIQ? = null
        val isInstanceCreated: Boolean
            get() = instance != null
    }
}

internal class GarminConnectIQWebServer(
    context: Context,
    private val onWatchRequest: () -> Unit = {},
) : NanoHTTPD("127.0.0.1", 0), KoinComponent {
    private var applicationContext: Context
    private val appConfig: AppConfig by inject()

    init {
        start(SOCKET_READ_TIMEOUT, false)
        applicationContext = context
    }

    private fun playHorn() {
        playBeep(appConfig.hornMode == 1, false)
    }

    private val speedStr
        get() = run {
            val speed = if (!appConfig.useMph) {
                WheelData.getInstance().speedDouble
            } else {
                MathsUtil.kmToMiles(WheelData.getInstance().speedDouble)
            }
            if (speed.toString().length > 3) {
                ((speed * 10).toInt().toFloat() / 10).toString()
            } else speed.toString()
        }

    private val topSpeed
        get() = if (!appConfig.useMph) {
            WheelData.getInstance().topSpeed
        } else {
            MathsUtil.kmToMiles(WheelData.getInstance().topSpeed.toDouble()).toInt()
        }

    private val temperature
        get() = if (!appConfig.useMph) {
            WheelData.getInstance().temperature
        } else {
            MathsUtil.celsiusToFahrenheit(WheelData.getInstance().temperature.toDouble()).toInt()
        }

    private val avgSpeed
        get() = if (!appConfig.useMph) {
            WheelData.getInstance().averageSpeedDouble.toInt()
        } else {
            MathsUtil.kmToMiles(WheelData.getInstance().averageSpeedDouble).toInt()
        }

    private val avgRidingSpeed
        get() = if (!appConfig.useMph) {
            WheelData.getInstance().averageRidingSpeedDouble.toInt()
        } else {
            MathsUtil.kmToMiles(WheelData.getInstance().averageRidingSpeedDouble).toInt()
        }

    override fun serve(session: IHTTPSession): Response {
        onWatchRequest()
        val wd = WheelData.getInstance()
        val ac = appConfig
      
        return when (session.method) {
            Method.GET -> {
                when (session.uri) {
                    "/data/main" -> {
                        val message = JSONObject()
                        message.put("speed", speedStr)
                        message.put("topSpeed", ((topSpeed / 10).toFloat() / 10).toString())
                        message.put("speedLimit", ac.maxSpeed)
                        message.put("useMph", ac.useMph)
                        message.put("battery", wd.batteryLevel)
                        message.put("temp", temperature)
                        message.put("pwm", String.format("%02.0f", wd.calculatedPwm))
                        message.put("maxPwm", String.format("%02.0f", wd.maxPwm))
                        message.put("connectedToWheel", wd.isConnected)
                        message.put("wheelModel", wd.model)

                        return newFixedLengthResponse(Response.Status.OK, "application/json", message.toString()) // Send data
                    }
                    "/data/details" -> {
                        val message = JSONObject()
                        message.put("useMph", ac.useMph)
                        message.put("avgRidingSpeed", avgRidingSpeed)
                        message.put("avgSpeed", avgSpeed)
                        message.put("topSpeed", ((topSpeed / 10).toFloat() / 10).toString())
                        message.put("voltage", wd.voltageDouble.toString())
                        message.put("maxVoltage", wd.maxVoltageForWheel.toString())
                        message.put("battery", wd.batteryLevel)
                        message.put("ridingTime", wd.ridingTimeString)
                        message.put("distance", wd.distance)
                        message.put("pwm", String.format("%02.0f", wd.calculatedPwm))
                        message.put("maxPwm", String.format("%02.0f", wd.maxPwm))
                        message.put("torque", wd.torque)
                        message.put("power", wd.powerDouble)
                        message.put("maxPower", wd.maxPowerDouble)

                        message.put("connectedToWheel", wd.isConnected)

                        return newFixedLengthResponse(Response.Status.OK, "application/json", message.toString()) // Send data
                    }
                    "/data/alarms" -> {
                        val message = "${Alarms.alarm}"
                        newFixedLengthResponse(Response.Status.OK, "application/json", message) // Send data
                    }
                    else -> {
                        Timber.i("404 Wrong endpoint")
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404: File not found")
                    }
                }
            }
            Method.POST -> {
                when (session.uri) {
                    "/actions/triggerHorn" -> {
                        playHorn()
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Executed!") // Send data
                    }
                    "/actions/frontLight/enable" -> {
                        wd.updateLight(true)
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Executed!") // Send data
                    }
                    "/actions/frontLight/disable" -> {
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Executed!") // Send data
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Error 404, action not found.")
                }
            }
            else -> {
                Timber.i("404 Wrong method")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404: File not found")
            }
        }
    }
}