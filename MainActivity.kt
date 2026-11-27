package com.universalremote.app

import android.content.Context
import android.hardware.ConsumerIrManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

// ============================================================================
// UNIVERSAL TV REMOTE — EDICIÓN FÍSICA LISTA PARA INSTALAR (ANDROID + SAMSUNG)
// Incluye:
// 1. Escáner real en Red Local (Subred /24 puerto 8001 + SSDP UPnP)
// 2. Driver real Samsung Tizen WebSocket Seguro (:8002 WSS + fallback :8001 WS)
// 3. Guardado automático de Token de autorización y encendido Wake-on-LAN
// 4. Emisor Infrarrojo real (ConsumerIrManager Samsung NECx2 38 kHz 0x0707)
// 5. Interfaz Oscura Premium con D-Pad, Números 0-9, Apps, Gestos y Teclado
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2563EB),
                    background = Color(0xFF07090F),
                    surface = Color(0xFF121725)
                )
            ) {
                UniversalRemotePhysicalApp()
            }
        }
    }
}

// ============================================================================
// 1. MODELOS Y TABLA OFICIAL DE CÓDIGOS SAMSUNG (WI-FI TIZEN + INFRARROJO IR)
// ============================================================================

enum class ControlMode { WIFI_LAN, INFRARED }

enum class ActiveTab { REMOTE, TOUCHPAD, APPS_TEXT, DISCOVERY }

data class RealTvDevice(
    val name: String,
    val model: String,
    val ip: String,
    val mac: String? = null
)

object SamsungCodes {
    // Mapa de comandos -> (Código Tizen Wi-Fi, Hexadecimal Infrarrojo Samsung 0x0707)
    val COMMANDS: Map<String, Pair<String, Int>> = mapOf(
        "POWER"   to ("KEY_POWER"   to 0x02),
        "MUTE"    to ("KEY_MUTE"    to 0x0F),
        "VOL_UP"  to ("KEY_VOLUP"   to 0x07),
        "VOL_DOWN" to ("KEY_VOLDOWN" to 0x0B),
        "CH_UP"   to ("KEY_CHUP"    to 0x12),
        "CH_DOWN" to ("KEY_CHDOWN"  to 0x10),
        "UP"      to ("KEY_UP"      to 0x60),
        "DOWN"    to ("KEY_DOWN"    to 0x61),
        "LEFT"    to ("KEY_LEFT"    to 0x65),
        "RIGHT"   to ("KEY_RIGHT"   to 0x62),
        "OK"      to ("KEY_ENTER"   to 0x68),
        "BACK"    to ("KEY_RETURN"  to 0x58),
        "HOME"    to ("KEY_HOME"    to 0x79),
        "MENU"    to ("KEY_MENU"    to 0x1A),
        "SETTINGS" to ("KEY_TOOLS"  to 0x1A),
        "SOURCE"  to ("KEY_SOURCE"  to 0x01),
        "PLAY"    to ("KEY_PLAY"    to 0x47),
        "PAUSE"   to ("KEY_PAUSE"   to 0x4A),
        "REW"     to ("KEY_REWIND"  to 0x45),
        "FF"      to ("KEY_FF"      to 0x48),
        "NUM_0"   to ("KEY_0"       to 0x11),
        "NUM_1"   to ("KEY_1"       to 0x04),
        "NUM_2"   to ("KEY_2"       to 0x05),
        "NUM_3"   to ("KEY_3"       to 0x06),
        "NUM_4"   to ("KEY_4"       to 0x08),
        "NUM_5"   to ("KEY_5"       to 0x09),
        "NUM_6"   to ("KEY_6"       to 0x0A),
        "NUM_7"   to ("KEY_7"       to 0x0C),
        "NUM_8"   to ("KEY_8"       to 0x0D),
        "NUM_9"   to ("KEY_9"       to 0x0E)
    )

    // IDs reales de aplicaciones en televisores Samsung Smart TV (Tizen OS)
    val APPS: List<Triple<String, String, Color>> = listOf(
        Triple("Netflix",     "3201907018807", Color(0xFFDC2626)),
        Triple("YouTube",     "111299001912",  Color(0xFFE11D48)),
        Triple("Prime Video", "3201910019365", Color(0xFF0284C7)),
        Triple("Disney+",     "3201901017640", Color(0xFF1D4ED8))
    )
}

// ============================================================================
// 2. CONTROLADOR NATIVO DE HARDWARE Y RED REAL (TIZEN WSS + IR 38kHz + WoL)
// ============================================================================

class RealSamsungController(private val context: Context) {

    private val prefs = context.getSharedPreferences("samsung_remote_prefs", Context.MODE_PRIVATE)
    private val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var hasIrHardware: Boolean = irManager?.hasIrEmitter() == true
        private set

    var isWifiConnected by mutableStateOf(false)
        private set

    var isPairingWaitingOnTv by mutableStateOf(false)
        private set

    var statusBanner by mutableStateOf("Toca 'Buscar TV' para detectar tu Samsung en casa")
        private set

    var activeTv by mutableStateOf<RealTvDevice?>(null)
        private set

    var discoveredTvs = mutableStateListOf<RealTvDevice>()

    private var webSocket: WebSocket? = null
    private val lanHttpClient: OkHttpClient by lazy { buildLanTrustClient() }

    init {
        // Restaurar automáticamente el último televisor Samsung conectado
        val savedIp = prefs.getString("last_tv_ip", null)
        val savedName = prefs.getString("last_tv_name", null)
        val savedModel = prefs.getString("last_tv_model", "Samsung Smart TV") ?: "Samsung Smart TV"
        val savedMac = prefs.getString("last_tv_mac", null)
        if (!savedIp.isNullOrBlank() && !savedName.isNullOrBlank()) {
            val saved = RealTvDevice(savedName, savedModel, savedIp, savedMac)
            activeTv = saved
            discoveredTvs.add(saved)
            connectToSamsungWifi(saved)
        }
    }

    /** Vibra suavemente al tocar cualquier botón del control remoto */
    fun vibrateClick(strong: Boolean = false) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(if (strong) 26L else 12L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
    }

    /** Escanea tu red doméstica combinando barrido rápido del puerto Samsung :8001 + SSDP */
    suspend fun scanHomeNetworkForSamsungTvs() = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            statusBanner = "Escaneando tu red Wi-Fi doméstica..."
        }

        val localIp = findPhoneWifiIpv4()
        if (localIp == null) {
            withContext(Dispatchers.Main) {
                statusBanner = "Conecta tu celular al Wi-Fi de tu casa e inténtalo de nuevo."
            }
            return@withContext
        }

        val prefix = localIp.substringBeforeLast(".")
        val foundMap = LinkedHashMap<String, RealTvDevice>()

        // 1. Barrido paralelo ultrarrápido en la subred local buscando la API Samsung (:8001/api/v2/)
        // Esto encuentra televisores Samsung incluso si el router bloquea paquetes Multicast.
        coroutineScope {
            (1..254).chunked(32).forEach { batch ->
                batch.map { hostNum ->
                    async {
                        val candidateIp = "$prefix.$hostNum"
                        if (candidateIp != localIp) {
                            probeSamsungTvAtIp(candidateIp)?.let { tv ->
                                synchronized(foundMap) { foundMap[tv.ip] = tv }
                                withContext(Dispatchers.Main) {
                                    if (discoveredTvs.none { it.ip == tv.ip }) {
                                        discoveredTvs.add(tv)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // 2. Respaldo adicional por SSDP UPnP por si el modelo responde en otro puerto
        runSsdpQuickPass().forEach { ip ->
            if (!foundMap.containsKey(ip)) {
                val tv = probeSamsungTvAtIp(ip) ?: RealTvDevice("Smart TV ($ip)", "Red Local", ip, null)
                foundMap[ip] = tv
                withContext(Dispatchers.Main) {
                    if (discoveredTvs.none { it.ip == tv.ip }) discoveredTvs.add(tv)
                }
            }
        }

        withContext(Dispatchers.Main) {
            statusBanner = if (discoveredTvs.isEmpty()) {
                "No se detectó el TV automáticamente. Verifica que esté encendido o ingresa su IP."
            } else {
                "¡${discoveredTvs.size} televisor(es) encontrado(s)! Toca uno para conectar."
            }
        }
    }

    /** Consulta el endpoint oficial local de Samsung (:8001/api/v2/) para leer nombre, modelo y MAC */
    suspend fun probeSamsungTvAtIp(ip: String): RealTvDevice? = withContext(Dispatchers.IO) {
        if (!isPrivateLanIp(ip)) return@withContext null
        runCatching {
            Socket().use { s -> s.connect(InetSocketAddress(ip, 8001), 380) }

            val conn = (URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 700
                requestMethod = "GET"
            }
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val deviceObj = root.optJSONObject("device")
            val rawName = deviceObj?.optString("name")?.takeIf { it.isNotBlank() }
                ?: root.optString("name", "Samsung Smart TV")
            val model = deviceObj?.optString("modelName", "Samsung Tizen") ?: "Samsung Tizen"
            val wifiMac = deviceObj?.optString("wifiMac")
                ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                ?.uppercase(Locale.ROOT)

            RealTvDevice(name = rawName, model = model, ip = ip, mac = wifiMac)
        }.getOrNull()
    }

    /** Abre el WebSocket seguro hacia tu TV Samsung (:8002 WSS con fallback automático a :8001 WS) */
    fun connectToSamsungWifi(tv: RealTvDevice, forcePlainWsFallback: Boolean = false) {
        if (!isPrivateLanIp(tv.ip)) {
            statusBanner = "Solo se permiten direcciones IP domésticas privadas (ej. 192.168.x.x)."
            return
        }

        webSocket?.close(1000, null)
        activeTv = tv
        isWifiConnected = false

        // Persistir datos básicos del televisor elegido
        prefs.edit()
            .putString("last_tv_ip", tv.ip)
            .putString("last_tv_name", tv.name)
            .putString("last_tv_model", tv.model)
            .apply()
        if (!tv.mac.isNullOrBlank()) {
            prefs.edit().putString("last_tv_mac", tv.mac).apply()
        }

        val appNameB64 = Base64.encodeToString(
            "Universal TV Remote".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val savedToken = prefs.getString("token_${tv.ip}", null)
        val tokenQuery = if (!savedToken.isNullOrBlank()) "&token=$savedToken" else ""

        val url = if (!forcePlainWsFallback) {
            "wss://${tv.ip}:8002/api/v2/channels/samsung.remote.control?name=$appNameB64$tokenQuery"
        } else {
            "ws://${tv.ip}:8001/api/v2/channels/samsung.remote.control?name=$appNameB64"
        }

        isPairingWaitingOnTv = savedToken.isNullOrBlank()
        statusBanner = if (savedToken.isNullOrBlank()) {
            "Mira tu TV Samsung y selecciona 'PERMITIR' arriba a la derecha..."
        } else {
            "Conectando con ${tv.name}..."
        }

        val request = Request.Builder().url(url).build()
        webSocket = lanHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("event")) {
                        "ms.channel.connect" -> {
                            val newToken = json.optJSONObject("data")?.optString("token")
                            if (!newToken.isNullOrBlank()) {
                                prefs.edit().putString("token_${tv.ip}", newToken).apply()
                            }
                            mainScope.launch {
                                isPairingWaitingOnTv = false
                                isWifiConnected = true
                                statusBanner = "Conectado por Wi-Fi a ${tv.name}"
                            }
                        }
                        "ms.channel.unauthorized" -> {
                            prefs.edit().remove("token_${tv.ip}").apply()
                            mainScope.launch {
                                isPairingWaitingOnTv = false
                                isWifiConnected = false
                                statusBanner = "Acceso denegado en la TV. Toca Conectar y pulsa 'Permitir' en tu Samsung."
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainScope.launch {
                    if (!forcePlainWsFallback) {
                        // Modelos Samsung 2015/2016 sin puerto 8002 usan ws://:8001
                        connectToSamsungWifi(tv, forcePlainWsFallback = true)
                    } else {
                        isPairingWaitingOnTv = false
                        isWifiConnected = false
                        statusBanner = "Sin respuesta de ${tv.name}. ¿Está encendida la TV?"
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainScope.launch { isWifiConnected = false }
            }
        })
    }

    /** Envía cualquier botón por Wi-Fi (Tizen WebSocket) o por Infrarrojo (ConsumerIrManager) */
    fun sendRemoteKey(commandId: String, mode: ControlMode) {
        vibrateClick(strong = commandId == "POWER" || commandId == "OK")
        val pair = SamsungCodes.COMMANDS[commandId] ?: return

        if (mode == ControlMode.INFRARED) {
            transmitSamsungIr(pair.second, commandId)
            return
        }

        // Si el usuario pulsa POWER y el TV está apagado/desconectado, enviar también Wake-on-LAN
        val currentTv = activeTv
        if (commandId == "POWER" && !isWifiConnected && currentTv != null) {
            val macToWake = currentTv.mac ?: prefs.getString("last_tv_mac", null)
            if (!macToWake.isNullOrBlank()) {
                mainScope.launch {
                    sendWakeOnLanPacket(macToWake)
                    statusBanner = "Paquete de encendido (Wake-on-LAN) enviado a ${currentTv.name}..."
                    delay(3500)
                    connectToSamsungWifi(currentTv)
                }
                return
            }
        }

        if (!isWifiConnected || webSocket == null) {
            statusBanner = "Aún no conectado por Wi-Fi. Pulsa 'TVs' para vincular tu Samsung."
            return
        }

        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", pair.first)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }.toString()

        val ok = webSocket?.send(payload) == true
        if (ok) {
            statusBanner = "Wi-Fi enviado -> ${pair.first}"
        } else {
            isWifiConnected = false
            statusBanner = "Conexión perdida. Reconectando..."
            currentTv?.let { connectToSamsungWifi(it) }
        }
    }

    /** Escribe texto directamente en buscadores del TV Samsung (YouTube / Navegador / Smart Hub) */
    fun sendTextToSamsung(text: String, pressEnter: Boolean) {
        vibrateClick()
        if (!isWifiConnected) {
            statusBanner = "El teclado virtual requiere conexión Wi-Fi activa."
            return
        }
        val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", b64)
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            })
        }.toString()
        webSocket?.send(payload)
        if (pressEnter) {
            mainScope.launch {
                delay(120)
                sendRemoteKey("OK", ControlMode.WIFI_LAN)
            }
        }
        statusBanner = "Texto enviado al televisor"
    }

    /** Abre Netflix, YouTube, Prime Video o Disney+ directamente en el TV Samsung */
    fun launchSamsungApp(appName: String, appId: String) {
        vibrateClick(strong = true)
        val tv = activeTv
        if (tv == null || !isWifiConnected) {
            statusBanner = "Conecta primero tu Samsung por Wi-Fi para abrir $appName."
            return
        }

        mainScope.launch(Dispatchers.IO) {
            val restOk = runCatching {
                val conn = (URL("http://${tv.ip}:8001/api/v2/applic
