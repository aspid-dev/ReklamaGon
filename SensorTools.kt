package local.phonediag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

object SensorTools {
    fun passport(sm: SensorManager): String = sm.getSensorList(Sensor.TYPE_ALL).joinToString("\n\n") { s ->
        "${s.name}${if (s.isWakeUpSensor) " [wakeup]" else ""}\n  тип ${s.type} (${s.stringType}), ${s.vendor}\n" +
            "  диапазон ${s.maximumRange}, шаг ${s.resolution}, ${s.power} мА\n  задержка ${s.minDelay} .. ${s.maxDelay} мкс"
    }

    private fun stat(v: List<Double>): Pair<Double, Double> {
        if (v.isEmpty()) return Pair(0.0, 0.0)
        val m = v.average()
        return Pair(m, sqrt(v.sumOf { (it - m) * (it - m) } / v.size))
    }

    /** Проверка на покое (телефон лежит неподвижно). Вызывать из главного потока. */
    fun restCheck(c: Context, sm: SensorManager, done: (String) -> Unit) {
        val acc = ArrayList<Double>(); val gyr = ArrayList<Double>(); val mag = ArrayList<Double>()
        fun l(dst: ArrayList<Double>) = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                dst.add(sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val la = l(acc); val lg = l(gyr); val lm = l(mag)
        val sa = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sg = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val smg = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sa != null) sm.registerListener(la, sa, SensorManager.SENSOR_DELAY_GAME)
        if (sg != null) sm.registerListener(lg, sg, SensorManager.SENSOR_DELAY_GAME)
        if (smg != null) sm.registerListener(lm, smg, SensorManager.SENSOR_DELAY_GAME)
        Handler(Looper.getMainLooper()).postDelayed({
            sm.unregisterListener(la); sm.unregisterListener(lg); sm.unregisterListener(lm)
            val sb = StringBuilder("Проверка на покое (2,5 с, телефон неподвижен)\n\n")
            fun judge(key: String, v: Double, fmt: String, label: String) {
                val z = Learner.update(c, key, v)
                sb.append("$label: ").append(fmt.format(v))
                sb.append(if (z == null) " (учусь)\n" else if (Math.abs(z) > 3) " ⚠ отклонение от вашей нормы\n" else " в норме\n")
            }
            if (acc.size > 5) {
                val (m, sd) = stat(acc)
                sb.append("Акселерометр: ${acc.size} замеров, |a| = %.3f м/с² (ожидается ≈9,81)%s\n".format(m, if (m in 9.4..10.2) "" else " ⚠"))
                judge("acc_noise", sd, "%.4f", "  шум акселерометра")
            } else sb.append("Акселерометр: нет данных\n")
            if (gyr.size > 5) {
                val (m, sd) = stat(gyr)
                sb.append("Гироскоп: смещение нуля %.4f рад/с%s\n".format(m, if (m < 0.05) "" else " ⚠ (телефон двигался или дрейф)"))
                judge("gyro_bias", m, "%.4f", "  смещение гироскопа")
                judge("gyro_noise", sd, "%.5f", "  шум гироскопа")
            } else sb.append("Гироскоп: нет данных\n")
            if (mag.size > 3) {
                val (m, _) = stat(mag)
                sb.append("Магнитометр: |B| = %.1f мкТл (норма 25–65)%s\n".format(m, if (m in 20.0..70.0) "" else " ⚠ рядом магнит/металл или сбой"))
                judge("mag_abs", m, "%.1f", "  поле")
            } else sb.append("Магнитометр: нет данных\n")
            done(sb.toString())
        }, 2500)
    }
}
