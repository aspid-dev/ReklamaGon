package local.phonediag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

object Report {
    @Volatile private var sink = 0.0

    fun fmtTime(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

    fun system(c: Context): String = buildString {
        fun h(t: String) { append("\n== ").append(t).append(" ==\n") }
        h("СИСТЕМА")
        append("Модель: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Патч безопасности: ${Build.VERSION.SECURITY_PATCH}\n")
        val s = SystemClock.elapsedRealtime() / 1000
        append("Аптайм: ${s / 86400} д ${s % 86400 / 3600} ч ${s % 3600 / 60} мин\n")
        append("Ядро: ${System.getProperty("os.version")}\n")
        append("Сборка: ${Build.FINGERPRINT}\n")
        append("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        h("CPU")
        append("Ядер: ${Runtime.getRuntime().availableProcessors()}\n")
        try {
            File("/proc/cpuinfo").readLines().filter { it.startsWith("Hardware") || it.startsWith("Features") }
                .distinct().forEach { append(it).append('\n') }
        } catch (e: Exception) { }
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000
                append("Ядро ${i + 1}: $f МГц\n")
            } catch (e: Exception) { }
        }
        h("ПАМЯТЬ")
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        append("RAM всего: ${gb(mi.totalMem)}, доступно: ${gb(mi.availMem)} (${mi.availMem * 100 / mi.totalMem}%)\n")
        append("Режим нехватки памяти: ${if (mi.lowMemory) "ДА" else "нет"}\n")
        val st = StatFs(Environment.getDataDirectory().path)
        val tot = st.totalBytes; val fr = st.availableBytes
        append("Хранилище: всего ${gb(tot)}, свободно ${gb(fr)} (${fr * 100 / tot}%)\n")
        if (fr * 100 / tot < 10) append("⚠ Свободно меньше 10%: запись замедляется.\n")
        h("БАТАРЕЯ")
        append(battery(c).first)
        h("ПАТЧ")
        append(patchNote())
    }

    private fun gb(b: Long) = "%.2f ГБ".format(b / 1073741824.0)

    fun patchNote(): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)!!
        val days = (System.currentTimeMillis() - d.time) / 86_400_000L
        "Патчу безопасности ${days / 365} лет ${days % 365 / 30} мес.${if (days > 365) " ⚠ Известные уязвимости не закрыты." else ""}\n"
    } catch (e: Exception) { "Дата патча неизвестна\n" }

    /** Текст и оценка полной ёмкости (мА·ч) по счётчику заряда, если доступно. */
    fun battery(c: Context): Pair<String, Double?> {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0) lvl * 100 / sc else -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "ПЕРЕГРЕВ"
            BatteryManager.BATTERY_HEALTH_DEAD -> "НЕИСПРАВНА"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "перенапряжение"
            BatteryManager.BATTERY_HEALTH_COLD -> "холодная"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "сбой"
            else -> "неизвестно"
        }
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val est: Double? = if (cc > 0 && pct in 15..95) cc / 1000.0 / (pct / 100.0) else null
        val sb = StringBuilder()
        sb.append("Заряд $pct%, ${if (plugged) "на зарядке" else "от батареи"}\n")
        sb.append("Температура %.1f °C, напряжение %d мВ, состояние: %s\n".format(temp, volt, health))
        if (cur != 0 && cur != Int.MIN_VALUE) sb.append("Ток: ${cur / 1000} мА (знак зависит от прошивки)\n")
        if (est != null) {
            val design = Store.designMah(c)
            sb.append("Оценка ёмкости: %.0f мА·ч из %d (%.0f%%), оценка грубая\n".format(est, design, est * 100 / design))
        } else sb.append("Оценка ёмкости: нужен заряд 15–95% и поддержка счётчика\n")
        if (temp >= 45) sb.append("⚠ Батарея горячая\n")
        return Pair(sb.toString(), est)
    }

    fun cpuBench(): Double {
        val t = System.nanoTime(); var x = 0.0
        for (i in 1..4_000_000) x += Math.sqrt(i.toDouble()) * Math.sin(i.toDouble())
        sink = x
        return (System.nanoTime() - t) / 1e6
    }

    fun memBench(): Double {
        val t = System.nanoTime()
        val a = DoubleArray(2_000_000)
        var i = 0
        while (i < a.size) { a[i] = i.toDouble(); i += 64 }
        sink = a[1000]
        return (System.nanoTime() - t) / 1e6
    }

    /** Запись и чтение 64 МБ с проверкой целостности. Возвращает МБ/с записи, МБ/с чтения, целостность. */
    fun flashBench(c: Context): Triple<Double, Double, Boolean> {
        val f = File(c.cacheDir, "bench.bin")
        val block = ByteArray(1 shl 20) { (it * 31).toByte() }
        val cw = CRC32(); val t0 = System.nanoTime()
        FileOutputStream(f).use { o -> repeat(64) { o.write(block); cw.update(block) }; o.fd.sync() }
        val w = 64 / ((System.nanoTime() - t0) / 1e9)
        val cr = CRC32(); val t1 = System.nanoTime()
        FileInputStream(f).use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = i.read(buf); if (n < 0) break; cr.update(buf, 0, n) }
        }
        val r = 64 / ((System.nanoTime() - t1) / 1e9)
        f.delete()
        return Triple(w, r, cw.value == cr.value)
    }

    /** Полная проверка: самопроверка, CPU, память, флеш, батарея, патч. Результат сохраняется. */
    fun fullCheck(c: Context, log: (String) -> Unit): String {
        val lines = ArrayList<String>()
        var score = 100
        fun add(s: String) { lines.add(s); log(s) }
        fun judge(key: String, v: Double, badHigh: Int, label: String, fmt: String) {
            val z = Learner.update(c, key, v)
            val txt = fmt.format(v)
            if (z == null) { add("• $label: $txt (учусь)"); return }
            val bad = if (badHigh == 0) Math.abs(z) else badHigh * z
            if (bad > 3) { score -= 10; add("⚠ $label: $txt, отклонение от вашей нормы"); Journal.add(c, 1, "$label: $txt, отклонение от нормы") }
            else add("• $label: $txt, в норме")
        }
        // самопроверка и аудит приватности
        val perms = try {
            c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (perms.contains("android.permission.INTERNET")) { score -= 30; add("⚠ Приложение запрашивает INTERNET: гарантия приватности нарушена!") }
        else add("• Приватность: разрешения INTERNET нет, отправить данные приложение не может")
        var a = 0L; for (i in 1..1000) a += i.toLong() * i
        if (a != 333833500L) { score -= 30; add("⚠ Самопроверка: арифметика даёт сбой") } else add("• Самопроверка: вычисления корректны")
        add("")
        judge("cpu_ms", cpuBench(), 1, "CPU", "%.0f мс")
        judge("mem_ms", memBench(), 1, "Выделение памяти", "%.1f мс")
        try {
            val (w, r, ok) = flashBench(c)
            if (!ok) { score -= 30; add("⚠ Флеш-память: данные при чтении не совпали!"); Journal.add(c, 2, "Флеш-память: ошибка целостности") }
            else add("• Флеш-память: целостность подтверждена")
            judge("flash_w", w, -1, "Запись", "%.0f МБ/с")
            judge("flash_r", r, -1, "Чтение", "%.0f МБ/с")
        } catch (e: Exception) { score -= 10; add("⚠ Тест флеш-памяти не удался: ${e.message}") }
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes * 100 / st.totalBytes
        if (free < 10) { score -= 10; add("⚠ Свободно $free% хранилища: меньше 10%") } else add("• Свободно $free% хранилища")
        val (btxt, est) = battery(c)
        add(btxt.trimEnd())
        if (btxt.contains("ПЕРЕГРЕВ") || btxt.contains("НЕИСПРАВНА")) score -= 20
        if (est != null) judge("cap_est", est, -1, "Ёмкость батареи", "%.0f мА·ч")
        val pw = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pw.isPowerSaveMode) add("• Включено энергосбережение: результаты CPU могут быть занижены")
        val pn = patchNote()
        add(pn.trimEnd())
        if (pn.contains("⚠")) score -= 10
        score = score.coerceIn(0, 100)
        val txt = lines.joinToString("\n") + "\n\nИтог: $score/100"
        Store.p(c).edit().putInt("last_score", score).putLong("last_ts", System.currentTimeMillis())
            .putString("last_report", txt).apply()
        Journal.add(c, 0, "Полная проверка: $score/100")
        return txt
    }
}
