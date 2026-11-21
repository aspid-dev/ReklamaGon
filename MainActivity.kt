package local.phonediag

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private var pending: (() -> Unit)? = null
    private var exportText = ""
    private var live: Live? = null
    private val tabs = listOf("Главная", "Железо", "Датчики", "Безопасность", "Журнал", "Настройки")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 14f, mono: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(0xFFECEEF2.toInt()); setPadding(0, dp(4), 0, dp(4))
        if (mono) typeface = Typeface.MONOSPACE
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun add(v: View) = content.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
    private fun title(t: String) = add(tv(t, 20f, bold = true))
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Notif.channels(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF101216.toInt()) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, n -> bar.addView(Button(this).apply { text = n; isAllCaps = false; textSize = 13f; setOnClickListener { show(i) } }) }
        root.addView(HorizontalScrollView(this).apply { addView(bar) }, LinearLayout.LayoutParams(MATCH, WRAP))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        show(intent.getIntExtra("tab", 0))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); show(i.getIntExtra("tab", 0)) }

    override fun onPause() { stopLive(); super.onPause() }

    private fun stopLive() {
        live?.let { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        live = null
    }

    private fun show(tab: Int) {
        stopLive(); content.removeAllViews()
        when (tab) { 0 -> home(); 1 -> hardware(); 2 -> sensors(); 3 -> security(); 4 -> journal(); else -> settings() }
    }

    // ---------- разрешения, файлы ----------
    private fun needs(perms: Array<String>, go: () -> Unit) {
        val miss = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) go() else { pending = go; requestPermissions(miss.toTypedArray(), 21) }
    }

    override fun onRequestPermissionsResult(rq: Int, p: Array<out String>, r: IntArray) {
        val g = pending; pending = null
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) g?.invoke() else toast("Разрешение не выдано")
    }

    private fun export(name: String, text: String) {
        exportText = text
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name), 11)
    }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        val u: Uri = d?.data ?: return
        if (rs != RESULT_OK) return
        try {
            if (rq == 11) contentResolver.openOutputStream(u)?.use { it.write(exportText.toByteArray()) }.also { toast("Сохранено") }
            if (rq == 12) {
                val t = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""
                toast(Rules.importText(this, t))
            }
        } catch (e: Exception) { toast("Ошибка файла: ${e.message}") }
    }

    private fun bg(out: TextView, work: () -> String) {
        out.text = "Выполняю…"
        Thread {
            val r = try { work() } catch (e: Exception) { "Ошибка: ${e.message}" }
            runOnUiThread { out.text = r }
        }.start()
    }

    // ---------- Главная ----------
    private fun home() {
        val p = Store.p(this)
        title("Состояние телефона")
        val score = p.getInt("last_score", -1)
        add(tv(if (score < 0) "Полная проверка ещё не запускалась" else "Оценка здоровья: $score/100 (${Report.fmtTime(p.getLong("last_ts", 0))})", 18f))
        add(tv(if (Store.guardOn(this)) "Страж включён, режим «${Store.modeNames[Store.mode(this)]}»" else "Страж выключен"))
        val out = tv(p.getString("last_report", "") ?: "", 12f, true)
        add(btn("Полная проверка") {
            out.text = ""
            Thread {
                val txt = Report.fullCheck(this) { s -> runOnUiThread { out.append(s + "\n") } }
                runOnUiThread { show(0) }
            }.start()
        })
        add(btn(if (Store.guardOn(this)) "Остановить стража" else "Запустить стража") {
            if (Store.guardOn(this)) { Guard.stopService(this); p.edit().putBoolean("guard", false).apply() }
            else { p.edit().putBoolean("guard", true).apply(); Guard.startService(this); toast("Страж запущен. Разрешите автозапуск в настройках Flyme (вкладка «Настройки»).") }
            show(0)
        })
        add(btn("Глубокая проверка приложений сейчас") { show(3) })
        add(out)
    }

    // ---------- Железо ----------
    private fun hardware() {
        title("Железо")
        val out = tv("", 12f, true)
        add(btn("Паспорт: система, CPU, память, батарея") { bg(out) { Report.system(this) } })
        add(btn("Камеры: паспорт и тест открытия") {
            needs(arrayOf(Manifest.permission.CAMERA)) { out.text = Hw.cameras(this); Hw.openAll(this) { out.append("\n" + it) } }
        })
        add(btn("Микрофон (1,5 с)") { needs(arrayOf(Manifest.permission.RECORD_AUDIO)) { bg(out) { Hw.mic() } } })
        add(btn("Динамик: тестовый тон") { bg(out) { Hw.tone() } })
        add(btn("GPS: время до фикса (до 60 с)") {
            needs(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) { out.text = "Ищу спутники…"; Hw.gps(this) { out.text = it } }
        })
        add(btn("Сотовая связь") {
            needs(arrayOf(Manifest.permission.READ_PHONE_STATE)) { out.text = "Измеряю…"; Hw.cell(this) { out.text = it } }
        })
        add(btn("GPU: паспорт и нагрузка (10 с)") {
            out.text = "Нагружаю GPU 10 секунд…"
            var probe: GpuProbe? = null
            probe = GpuProbe(this) { txt -> out.text = txt; content.removeView(probe) }
            content.addView(probe, LinearLayout.LayoutParams(MATCH, dp(160)))
        })
        add(btn("Экспорт отчёта в файл") {
            export("diag-report.txt", Report.system(this) + "\n" + (Store.p(this).getString("last_report", "") ?: ""))
        })
        add(out)
    }

    // ---------- Датчики ----------
    private fun sensors() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        title("Датчики")
        val out = tv("", 12f, true)
        add(btn("Паспорт всех датчиков") { out.text = SensorTools.passport(sm) })
        add(btn("Проверка на покое (положите телефон)") { out.text = "Не трогайте телефон 2,5 с…"; SensorTools.restCheck(this, sm) { out.text = it } })
        add(btn("Экспорт паспорта в файл") { export("sensors.txt", SensorTools.passport(sm)) })
        add(tv("Живые показания: выберите датчик", 15f, bold = true))
        sm.getSensorList(Sensor.TYPE_ALL).filter { !it.isWakeUpSensor }.forEach { s -> add(btn(s.name) { liveView(sm, s) }) }
        add(out)
    }

    private inner class Live(val s: Sensor, val out: TextView) : SensorEventListener {
        val rows = ArrayList<String>()
        var n = 0
        val mn = FloatArray(6) { Float.MAX_VALUE }; val mx = FloatArray(6) { -Float.MAX_VALUE }; val sum = DoubleArray(6)
        var last = FloatArray(0)
        override fun onSensorChanged(e: SensorEvent) {
            val k = minOf(e.values.size, 6)
            last = e.values.copyOf(); n++
            for (i in 0 until k) { val v = e.values[i]; if (v < mn[i]) mn[i] = v; if (v > mx[i]) mx[i] = v; sum[i] += v.toDouble() }
            if (rows.size < 3000) rows.add("${e.timestamp}," + e.values.joinToString(","))
            if (n % 4 == 1) {
                val sb = StringBuilder("Замеров: $n\n")
                for (i in 0 until k) sb.append("v$i = %.4f   мин %.4f  макс %.4f  сред %.4f\n".format(last[i], mn[i], mx[i], sum[i] / n))
                out.text = sb.toString()
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun liveView(sm: SensorManager, s: Sensor) {
        stopLive(); content.removeAllViews()
        title(s.name)
        add(tv("${s.stringType}, диапазон ${s.maximumRange}, шаг ${s.resolution}", 12f))
        val out = tv("Жду данные…", 13f, true)
        fun start(delay: Int) {
            stopLive()
            val l = Live(s, out); live = l
            if (!sm.registerListener(l, s, delay)) out.text = "Не удалось подписаться на датчик"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Обычно") { start(SensorManager.SENSOR_DELAY_UI) })
        row.addView(btn("Быстро") { start(SensorManager.SENSOR_DELAY_GAME) })
        row.addView(btn("Макс.") { start(SensorManager.SENSOR_DELAY_FASTEST) })
        add(row)
        add(btn("Снимок в журнал") { live?.let { Journal.add(this, 0, "Датчик ${s.name}: " + it.last.joinToString(", ")); toast("Записано") } })
        add(btn("Экспорт записи (CSV)") { live?.let { export("sensor.csv", "t_ns,v0,v1,v2...\n" + it.rows.joinToString("\n")) } })
        add(btn("Назад") { show(2) })
        add(out)
        start(SensorManager.SENSOR_DELAY_UI)
    }

    // ---------- Безопасность ----------
    private fun security() {
        title("Безопасность")
        val out = tv("", 12f, true)
        add(btn("Сканировать приложения (шпионы, вредоносы)") { bg(out) { val r = Spy.scan(this); Journal.add(this, 0, "Скан приложений: ${r.size}"); Spy.format(r) } })
        add(btn("Проверка устройства: root, USB-отладка, патч") { bg(out) { Spy.deviceCheck(this) } })
        add(btn("Сканировать Wi-Fi") { needs(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) { bg(out) { Wifi.scan(this) } } })
        add(btn("Импорт файла правил (JSON)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12)
        })
        add(out)
    }

    // ---------- Журнал ----------
    private fun journal() {
        title("Журнал событий")
        val ev = Journal.read(this).takeLast(200).reversed()
        val txt = if (ev.isEmpty()) "Пока пусто" else ev.joinToString("\n\n") {
            "${Report.fmtTime(it.ts)} ${listOf("·", "•", "⚠")[it.level.coerceIn(0, 2)]} ${it.text}"
        }
        add(btn("Экспорт журнала") { export("journal.txt", txt) })
        add(btn("Очистить журнал") { Journal.clear(this); show(4) })
        add(tv(txt, 13f))
    }

    // ---------- Настройки ----------
    private fun settings() {
        val p = Store.p(this)
        title("Настройки")
        add(tv("Режим стража: ${Store.modeNames[Store.mode(this)]}", 15f, bold = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Store.modeNames.forEachIndexed { i, n ->
            row.addView(btn(n) { p.edit().putInt("mode", i).apply(); if (Store.guardOn(this)) Guard.startService(this); show(5) })
        }
        add(row)
        val m = Store.digestMin(this)
        add(btn("Вечерняя сводка: %02d:%02d (изменить)".format(m / 60, m % 60)) {
            TimePickerDialog(this, { _, h, mi -> p.edit().putInt("digest", h * 60 + mi).apply(); Guard.scheduleDigest(this); show(5) }, m / 60, m % 60, true).show()
        })
        add(btn("Хранить журнал дней: ${Store.retention(this)} (изменить)") {
            val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(Store.retention(this@MainActivity).toString()) }
            AlertDialog.Builder(this).setTitle("Дней хранения").setView(e).setPositiveButton("OK") { _, _ ->
                e.text.toString().toIntOrNull()?.let { p.edit().putInt("retention", it.coerceIn(1, 365)).apply() }; show(5)
            }.show()
        })
        add(btn("Заводская ёмкость батареи: ${Store.designMah(this)} мА·ч (изменить)") {
            val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(Store.designMah(this@MainActivity).toString()) }
            AlertDialog.Builder(this).setTitle("мА·ч").setView(e).setPositiveButton("OK") { _, _ ->
                e.text.toString().toIntOrNull()?.let { p.edit().putInt("design_mah", it.coerceIn(500, 10000)).apply() }; show(5)
            }.show()
        })
        add(btn("Сбросить самообучение") { Learner.reset(this); toast("Модель очищена, учусь заново") })
        add(tv("Для работы стража", 15f, bold = true))
        add(tv("1) Выдайте «Статистика использования» (слежка за трафиком).\n2) Разрешите приложению автозапуск и работу в фоне, а то Flyme его закроет: Безопасность → Разрешения → Автозапуск, и Батарея → Фоновая активность.\n3) Отключите оптимизацию батареи для приложения.", 13f))
        add(btn("Статистика использования") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        add(btn("Оптимизация батареи") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        add(btn("Настройки этого приложения") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        add(tv("Приватность: в манифесте нет разрешения INTERNET, приложение не может отправить данные никому. Всё хранится только на телефоне. Версия ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }}.", 12f))
    }
}
