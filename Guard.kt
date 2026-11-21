package local.phonediag

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.telephony.TelephonyManager
import java.util.Calendar

object Notif {
    const val CH_ALERT = "alert"
    const val CH_GUARD = "guard"
    const val CH_DIGEST = "digest"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Тревоги стража", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Страж работает", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "Вечерняя сводка", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun open(c: Context, tab: Int): PendingIntent = PendingIntent.getActivity(
        c, tab, Intent(c, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT)

    fun post(c: Context, ch: String, id: Int, title: String, text: String, tab: Int) {
        val n = Notification.Builder(c, ch).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(c, tab)).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

object Guard {
    fun startService(c: Context) {
        c.startForegroundService(Intent(c, GuardService::class.java))
        schedule(c)
        scheduleDigest(c)
    }

    fun stopService(c: Context) {
        c.stopService(Intent(c, GuardService::class.java))
        val js = c.getSystemService(JobScheduler::class.java)
        js.cancel(1); js.cancel(2)
    }

    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        val every = when (Store.mode(c)) { 2 -> 15; 1 -> 20; else -> 30 }
        js.schedule(JobInfo.Builder(1, ComponentName(c, GuardJob::class.java))
            .setPeriodic(every * 60_000L).setPersisted(true).build())
        js.schedule(JobInfo.Builder(2, ComponentName(c, GuardJob::class.java))
            .setPeriodic(24 * 3_600_000L).setRequiresCharging(true).setRequiresDeviceIdle(true).setPersisted(true).build())
    }

    fun scheduleDigest(c: Context) {
        val m = Store.digestMin(c)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(c, 100, Intent(c, DigestReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        c.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    /** Серьёзное: журнал + уведомление. Не чаще раза в 30 минут по одному ключу. */
    fun alert(c: Context, key: String, text: String) {
        val p = Store.p(c); val now = System.currentTimeMillis()
        if (now - p.getLong("al_$key", 0) < 30 * 60_000L) return
        p.edit().putLong("al_$key", now).apply()
        Journal.add(c, 2, text)
        Notif.post(c, Notif.CH_ALERT, 1000 + (key.hashCode() and 0xFFF), "Страж: внимание", text, 4)
    }

    fun minor(c: Context, text: String) = Journal.add(c, 1, text)

    private fun lowBattery(c: Context): Boolean {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val charging = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return pm.isPowerSaveMode || (!charging && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 20)
    }

    /** Лёгкая периодическая проверка. */
    fun periodic(c: Context) {
        val cpu0 = Process.getElapsedCpuTime()
        val cur = Spy.sets(c)
        val prev = Store.p(c).getStringSet("sens_prev", null)
        if (prev != null) cur.filter { it !in prev }.forEach { alert(c, "sens:$it", Spy.describe(it)) }
        Store.p(c).edit().putStringSet("sens_prev", cur).apply()
        if (!lowBattery(c)) netCheck(c)
        // самодиагностика стража: учёт собственного расхода процессора
        val used = Process.getElapsedCpuTime() - cpu0
        val day = System.currentTimeMillis() / 86_400_000L
        val p = Store.p(c)
        val total = (if (p.getLong("cpu_day", 0) == day) p.getLong("cpu_ms", 0) else 0L) + used
        p.edit().putLong("cpu_day", day).putLong("cpu_ms", total).apply()
        if (total > 20_000 && !p.getBoolean("cpu_warned_$day", false)) {
            p.edit().putBoolean("cpu_warned_$day", true).apply()
            minor(c, "Страж сегодня израсходовал ${total / 1000} с процессора, это больше бюджета. Советую режим «Эконом».")
        }
    }

    /** Глубокое сканирование (на зарядке или по запросу). */
    fun heavy(c: Context) {
        val r = Spy.scan(c)
        r.filter { it.score >= 60 }.forEach { alert(c, "spy:${it.pkg}", "Высокий риск (${it.score}): ${it.label}: ${it.why.joinToString("; ")}") }
        r.filter { it.score in 30..59 }.forEach { minor(c, "Средний риск (${it.score}): ${it.label}: ${it.why.joinToString("; ")}") }
        Journal.add(c, 0, "Глубокое сканирование: ${r.size} приложений")
    }

    private fun hasUsage(c: Context): Boolean {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ao.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun netCheck(c: Context) {
        val p = Store.p(c)
        if (!hasUsage(c)) {
            if (!p.getBoolean("warned_usage", false)) {
                p.edit().putBoolean("warned_usage", true).apply()
                minor(c, "Нет доступа «Статистика использования»: слежка за трафиком выключена. Включите в Настройках приложения.")
            }
            return
        }
        val now = System.currentTimeMillis()
        val last = p.getLong("net_last", now - 30 * 60_000L)
        p.edit().putLong("net_last", now).apply()
        val hours = Math.max((now - last) / 3_600_000.0, 0.25)
        val nsm = c.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val bytes = HashMap<Int, Long>()
        fun q(type: Int, sub: String?) {
            try {
                val st = nsm.querySummary(type, sub, last, now)
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) { st.getNextBucket(b); bytes[b.uid] = (bytes[b.uid] ?: 0L) + b.rxBytes + b.txBytes }
                st.close()
            } catch (e: Exception) { }
        }
        q(ConnectivityManager.TYPE_WIFI, null)
        val sub = try { (c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).subscriberId } catch (e: Exception) { null }
        if (sub != null) q(ConnectivityManager.TYPE_MOBILE, sub)
        val pm = c.packageManager
        val screenOff = !(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        for ((uid, b) in bytes) {
            if (b <= 0 || uid < 10000) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            if (pkg == c.packageName) continue
            val mb = b / 1e6
            val z = Learner.update(c, "net:$pkg", mb / hours) ?: continue
            if (z > 5 && mb > 30) {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                val t = "$label передал(а) %.0f МБ за %.1f ч, это намного больше обычного".format(mb, hours)
                if (screenOff) alert(c, "net:$pkg", t + " при выключенном экране") else minor(c, t)
            }
        }
    }

    fun digest(c: Context) {
        val ev = Journal.read(c, System.currentTimeMillis() - 24 * 3_600_000L).filter { it.level >= 1 }
        val serious = ev.count { it.level == 2 }
        val text = if (ev.isEmpty()) "За день ничего подозрительного." else
            "Событий: ${ev.size} (серьёзных: $serious). Последние:\n" + ev.takeLast(5).joinToString("\n") { "• " + it.text.take(90) }
        Notif.channels(c)
        Notif.post(c, Notif.CH_DIGEST, 2000, "Сводка за день", text, 4)
        Journal.prune(c)
    }
}

class GuardService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var camCb: CameraManager.AvailabilityCallback? = null
    private var recCb: AudioManager.AudioRecordingCallback? = null
    private var pkgRx: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        start()
        return START_STICKY
    }

    private fun start() {
        Notif.channels(this)
        val n = Notification.Builder(this, Notif.CH_GUARD).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Страж работает").setContentText("Режим: ${Store.modeNames[Store.mode(this)]}")
            .setContentIntent(Notif.open(this, 0)).setOngoing(true).build()
        startForeground(1, n)
        if (camCb != null) return
        val pw = getSystemService(Context.POWER_SERVICE) as PowerManager
        camCb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(id: String) {
                if (!pw.isInteractive) Guard.alert(this@GuardService, "cam", "Камера занята при выключенном экране (id $id). Если вы ничего не снимали, проверьте приложения.")
            }
        }
        (getSystemService(Context.CAMERA_SERVICE) as CameraManager).registerAvailabilityCallback(camCb!!, h)
        recCb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (configs.isNotEmpty() && !pw.isInteractive)
                    Guard.alert(this@GuardService, "mic", "Идёт запись с микрофона при выключенном экране. Это может быть голосовой помощник, а может быть и шпион.")
            }
        }
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioRecordingCallback(recCb!!, h)
        pkgRx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                if (pkg == c.packageName) return
                Thread {
                    val r = try { Spy.scan(c, pkg).firstOrNull() } catch (e: Exception) { null }
                    if (r == null) Guard.minor(c, "Установлено или обновлено приложение $pkg")
                    else if (r.score >= 60) Guard.alert(c, "spy:$pkg", "Установлено «${r.label}»: высокий риск (${r.score}): ${r.why.joinToString("; ")}")
                    else Guard.minor(c, "Установлено или обновлено «${r.label}» (риск ${r.score})${if (r.why.isEmpty()) "" else ": " + r.why.joinToString("; ")}")
                }.start()
            }
        }
        registerReceiver(pkgRx, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
    }

    override fun onDestroy() {
        try {
            camCb?.let { (getSystemService(Context.CAMERA_SERVICE) as CameraManager).unregisterAvailabilityCallback(it) }
            recCb?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(it) }
            pkgRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        camCb = null; recCb = null; pkgRx = null
        super.onDestroy()
    }
}

class GuardJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            try { if (p.jobId == 1) Guard.periodic(this) else Guard.heavy(this) } catch (e: Exception) { }
            jobFinished(p, false)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean = false
}

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Guard.digest(c)
        Guard.scheduleDigest(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.guardOn(c)) Guard.startService(c)
    }
}
