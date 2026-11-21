package local.phonediag

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object Hw {
    fun cameras(c: Context): String {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val face = when (ch.get(CameraCharacteristics.LENS_FACING)) { 0 -> "фронтальная"; 1 -> "основная"; else -> "внешняя" }
            val sz = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val mp = if (sz != null) "%.1f Мп".format(sz.width * sz.height / 1e6) else "?"
            val flash = if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "есть вспышка" else "без вспышки"
            sb.append("Камера $id: $face, $mp, $flash\n")
        }
        return sb.toString()
    }

    /** Открывает и закрывает каждую камеру. Вызывать из главного потока. */
    fun openAll(c: Context, done: (String) -> Unit) {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList.toList()
        val sb = StringBuilder()
        val h = Handler(Looper.getMainLooper())
        fun next(i: Int) {
            if (i >= ids.size) { done(sb.toString()); return }
            var fin = false
            val t0 = SystemClock.elapsedRealtime()
            try {
                @Suppress("MissingPermission")
                cm.openCamera(ids[i], object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (fin) { d.close(); return }
                        fin = true; sb.append("Камера ${ids[i]}: открылась за ${SystemClock.elapsedRealtime() - t0} мс\n"); d.close(); next(i + 1)
                    }
                    override fun onDisconnected(d: CameraDevice) {
                        d.close(); if (fin) return
                        fin = true; sb.append("Камера ${ids[i]}: отключилась ⚠\n"); next(i + 1)
                    }
                    override fun onError(d: CameraDevice, e: Int) {
                        d.close(); if (fin) return
                        fin = true; sb.append("Камера ${ids[i]}: ОШИБКА $e ⚠\n"); next(i + 1)
                    }
                }, h)
            } catch (e: Exception) { fin = true; sb.append("Камера ${ids[i]}: ${e.message} ⚠\n"); next(i + 1); return }
            h.postDelayed({ if (!fin) { fin = true; sb.append("Камера ${ids[i]}: таймаут ⚠\n"); next(i + 1) } }, 5000)
        }
        next(0)
    }

    /** Блокирующий: фоновый поток. */
    fun mic(): String {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2)
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return "Микрофон не открылся (занят другим приложением или нет доступа) ⚠" }
        ar.startRecording()
        val buf = ShortArray(2048)
        var sum = 0.0; var n = 0; var peak = 0
        val end = SystemClock.elapsedRealtime() + 1500
        while (SystemClock.elapsedRealtime() < end) {
            val r = ar.read(buf, 0, buf.size)
            for (i in 0 until r) { val v = buf[i].toInt(); sum += v.toDouble() * v; n++; peak = max(peak, abs(v)) }
        }
        ar.stop(); ar.release()
        val rms = sqrt(sum / max(n, 1))
        return "Микрофон: уровень %.0f, пик %d из 32767\n%s".format(rms, peak,
            if (peak == 0) "Полная тишина: микрофон не работает или заблокирован ⚠" else "Сигнал есть. Для проверки произнесите что-нибудь и повторите: пик должен вырасти.")
    }

    fun tone(): String {
        val rate = 22050
        val data = ShortArray(rate) { (sin(2 * PI * 440 * it / rate) * 12000).toInt().toShort() }
        @Suppress("DEPRECATION")
        val t = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, data.size * 2, AudioTrack.MODE_STATIC)
        t.write(data, 0, data.size); t.play()
        Thread.sleep(1100); t.release()
        return "Тон 440 Гц, 1 с. Вы его слышали? Если нет, проверьте громкость мультимедиа."
    }

    fun gps(c: Context, done: (String) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { done("GPS выключен: включите геолокацию в шторке"); return }
        val t0 = SystemClock.elapsedRealtime()
        var sats = 0; var used = 0; var finished = false
        val h = Handler(Looper.getMainLooper())
        val gs = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                sats = s.satelliteCount
                used = (0 until s.satelliteCount).count { s.usedInFix(it) }
            }
        }
        lateinit var ll: LocationListener
        fun end(msg: String) {
            if (finished) return
            finished = true
            try { lm.removeUpdates(ll); lm.unregisterGnssStatusCallback(gs) } catch (e: Exception) { }
            done(msg)
        }
        ll = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                end("Фикс за ${(SystemClock.elapsedRealtime() - t0) / 1000} с, точность ${l.accuracy} м, спутников видно $sats, в решении $used")
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            @Suppress("MissingPermission")
            lm.registerGnssStatusCallback(gs, h)
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, ll, Looper.getMainLooper())
        } catch (e: Exception) { done("Ошибка GPS: ${e.message}"); return }
        h.postDelayed({ end("Фикса нет за 60 с, видно спутников: $sats ⚠ Выйдите на открытое место.") }, 60000)
    }

    fun cell(c: Context, done: (String) -> Unit) {
        val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sim = when (tm.simState) { TelephonyManager.SIM_STATE_READY -> "готова"; TelephonyManager.SIM_STATE_ABSENT -> "нет SIM"; else -> "состояние ${tm.simState}" }
        val base = "Оператор: ${tm.networkOperatorName}, страна ${tm.networkCountryIso}, роуминг ${tm.isNetworkRoaming}, SIM: $sim"
        var finished = false
        val l = object : PhoneStateListener() {
            @Deprecated("old api")
            override fun onSignalStrengthsChanged(s: SignalStrength) {
                if (finished) return
                finished = true
                tm.listen(this, PhoneStateListener.LISTEN_NONE)
                done("$base\nУровень сигнала: ${s.level}/4")
            }
        }
        tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished) { finished = true; tm.listen(l, PhoneStateListener.LISTEN_NONE); done("$base\nСигнал не получен за 4 с") }
        }, 4000)
    }
}

/** Видимый на 10 секунд GL-вид: паспорт GPU, проверка пикселя, нагрузка и поиск троттлинга. */
class GpuProbe(private val ctx: Context, private val onDone: (String) -> Unit) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    private var prog = 0; private var loc = 0; private var tLoc = 0
    private var w = 1; private var h = 1
    private var info = ""
    private var t0 = 0L
    private var f1 = 0; private var f2 = 0; private var frames = 0
    private var pixOk: Boolean? = null
    private var finished = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val vs = "attribute vec4 p; void main(){ gl_Position = p; }"
    private val fs = "precision mediump float; uniform float t; void main(){ float x = gl_FragCoord.x*0.01 + t; " +
        "for(int i=0;i<150;i++){ x = sin(x)*1.0001 + cos(x*0.5); } gl_FragColor = vec4(0.2,0.4,0.6,1.0) + vec4(x*0.0); }"

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun sh(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
        info = "GPU: ${GLES20.glGetString(GLES20.GL_RENDERER)}\nПроизводитель: ${GLES20.glGetString(GLES20.GL_VENDOR)}\n" +
            "Версия: ${GLES20.glGetString(GLES20.GL_VERSION)}\nРасширений: ${GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.size ?: 0}"
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        loc = GLES20.glGetAttribLocation(prog, "p")
        tLoc = GLES20.glGetUniformLocation(prog, "t")
        t0 = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        if (finished) return
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(tLoc, frames * 0.01f)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (frames == 5) {
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(w / 2, h / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
            val r = b.get(0).toInt() and 255; val g = b.get(1).toInt() and 255; val bl = b.get(2).toInt() and 255
            pixOk = abs(r - 51) <= 3 && abs(g - 102) <= 3 && abs(bl - 153) <= 3
        }
        GLES20.glFinish()
        frames++
        val el = SystemClock.elapsedRealtime() - t0
        if (el < 3000) f1++ else if (el >= 7000) f2++
        if (el >= 10000) {
            finished = true
            val fps1 = f1 / 3.0; val fps2 = f2 / 3.0
            val z = Learner.update(ctx, "gpu_fps_end", fps2)
            val drop = if (fps1 > 0) (1 - fps2 / fps1) * 100 else 0.0
            val txt = info + "\nПроверка пикселя: ${if (pixOk == true) "верно" else "ОШИБКА ⚠ (артефакты рендера)"}\n" +
                "Кадров/с: в начале %.0f, в конце %.0f (падение %.0f%%)%s\n%s".format(fps1, fps2, drop,
                    if (drop > 25) " ⚠ троттлинг от перегрева" else "",
                    if (z == null) "Норма вашего GPU: учусь" else if (z < -3) "⚠ Ниже вашей обычной нормы" else "В пределах вашей нормы")
            post { onDone(txt) }
        }
    }
}
