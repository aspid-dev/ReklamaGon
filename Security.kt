package local.phonediag

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.File

object Spy {
    data class Risk(val pkg: String, val label: String, val score: Int, val why: List<String>)

    private fun split(s: String?) = (s ?: "").split(':').filter { it.isNotEmpty() }

    /** Чувствительные включённые возможности: службы доступности, доступ к уведомлениям, администраторы устройства. */
    fun sets(c: Context): Set<String> {
        val out = HashSet<String>()
        val cr = c.contentResolver
        split(Settings.Secure.getString(cr, "enabled_accessibility_services")).forEach {
            ComponentName.unflattenFromString(it)?.packageName?.let { p -> out.add("acc:$p") }
        }
        split(Settings.Secure.getString(cr, "enabled_notification_listeners")).forEach {
            ComponentName.unflattenFromString(it)?.packageName?.let { p -> out.add("ntf:$p") }
        }
        try {
            val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.forEach { out.add("adm:" + it.packageName) }
        } catch (e: Exception) { }
        return out
    }

    fun describe(entry: String): String {
        val p = entry.substringAfter(':')
        return when (entry.substringBefore(':')) {
            "acc" -> "$p включил службу доступности (может читать экран и нажимать вместо вас)"
            "ntf" -> "$p получил доступ к вашим уведомлениям"
            else -> "$p стал администратором устройства (его трудно удалить)"
        }
    }

    fun scan(c: Context, only: String? = null): List<Risk> {
        val pm = c.packageManager
        val rules = Rules.load(c)
        val st = sets(c)
        val stalk = Rules.list(rules, "stalkerware").toSet()
        val kw = Rules.list(rules, "keywords")
        val combos = rules.optJSONArray("combos")
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val stores = setOf("com.android.vending", "com.meizu.mstore", "com.sec.android.app.samsungapps", "com.amazon.venezia")
        val list: List<PackageInfo> = if (only != null) listOf(pm.getPackageInfo(only, PackageManager.GET_PERMISSIONS))
        else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val out = ArrayList<Risk>()
        for (pi in list) {
            val ai = pi.applicationInfo ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (pi.packageName == c.packageName) continue
            var score = 0
            val why = ArrayList<String>()
            val granted = HashSet<String>()
            val req = HashSet<String>()
            pi.requestedPermissions?.forEachIndexed { i, p ->
                val s = p.substringAfterLast('.')
                req.add(s)
                val fl = pi.requestedPermissionsFlags?.getOrNull(i) ?: 0
                if ((fl and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(s)
            }
            val pkg = pi.packageName
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pkg }
            if (pkg in stalk) { score += 60; why.add("в списке известных шпионских приложений") }
            if (kw.any { pkg.lowercase().contains(it) || label.lowercase().contains(it) }) { score += 15; why.add("подозрительное слово в названии") }
            if ("acc:$pkg" in st) { score += 35; why.add("включена служба доступности") }
            if ("ntf:$pkg" in st) { score += 25; why.add("читает ваши уведомления") }
            if ("adm:$pkg" in st) { score += 30; why.add("администратор устройства") }
            if (pkg !in launcher) { score += 20; why.add("нет значка запуска (скрытое приложение)") }
            if (combos != null) {
                for (i in 0 until combos.length()) {
                    val cb = combos.getJSONArray(i)
                    val names = (0 until cb.length()).map { cb.getString(it) }
                    if (names.all { it in granted }) { score += 25; why.add("опасное сочетание разрешений: ${names.joinToString("+")}"); break }
                }
            }
            if ("SYSTEM_ALERT_WINDOW" in granted) { score += 8; why.add("может рисовать поверх других приложений") }
            val inst = try { pm.getInstallerPackageName(pkg) } catch (e: Exception) { null }
            if (inst == null || inst !in stores) { score += 5; why.add("установлено не из магазина") }
            out.add(Risk(pkg, label, score.coerceAtMost(100), why))
        }
        return out.sortedByDescending { it.score }
    }

    fun format(list: List<Risk>): String {
        val hi = list.count { it.score >= 60 }; val md = list.count { it.score in 30..59 }
        val sb = StringBuilder("Проверено приложений: ${list.size}. Высокий риск: $hi, средний: $md\n")
        sb.append("Это анализ признаков, а не вердикт: легальные программы (родительский контроль, менеджеры паролей) тоже могут попасть в список.\n\n")
        list.filter { it.score >= 20 }.take(40).forEach {
            val tag = if (it.score >= 60) "ВЫСОКИЙ" else if (it.score >= 30) "средний" else "низкий"
            sb.append("[$tag ${it.score}] ${it.label}\n  ${it.pkg}\n  ${it.why.joinToString("; ")}\n\n")
        }
        if (list.none { it.score >= 20 }) sb.append("Подозрительных признаков не найдено.\n")
        return sb.toString()
    }

    fun deviceCheck(c: Context): String {
        val sb = StringBuilder()
        val rootPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk")
        val root = rootPaths.any { File(it).exists() } || (Build.TAGS?.contains("test-keys") == true)
        sb.append(if (root) "⚠ Признаки root-доступа или тестовой прошивки\n" else "• Признаков root нет\n")
        val adb = Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        sb.append(if (adb) "⚠ Отладка по USB включена: выключите, если не пользуетесь\n" else "• Отладка по USB выключена\n")
        val kg = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        sb.append(if (kg.isDeviceSecure) "• Блокировка экрана установлена\n" else "⚠ Нет пароля/PIN/отпечатка на блокировке\n")
        try {
            val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            sb.append(if (dpm.storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE) "• Шифрование хранилища включено\n" else "⚠ Шифрование хранилища не подтверждено\n")
        } catch (e: Exception) { }
        sb.append(Report.patchNote())
        val s = sets(c)
        sb.append("Служб доступности/доступа к уведомлениям/админов: ${s.size}\n")
        s.forEach { sb.append("  - ").append(describe(it)).append('\n') }
        return sb.toString()
    }
}

object Wifi {
    /** Блокирующий вызов: запускать в фоновом потоке. */
    fun scan(c: Context): String {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return "Wi-Fi выключен"
        @Suppress("DEPRECATION") wm.startScan()
        Thread.sleep(3500)
        val rs = wm.scanResults
        if (rs.isNullOrEmpty()) return "Сети не найдены. Нужны разрешение на геолокацию и включённая геолокация."
        val bySsid = rs.groupBy { it.SSID }
        val sb = StringBuilder("Найдено точек: ${rs.size}, сетей: ${bySsid.size}\n\n")
        rs.sortedByDescending { it.level }.forEach {
            val caps = it.capabilities ?: ""
            val flag = when {
                caps.contains("WEP") -> " ⚠ WEP: легко взламывается"
                !caps.contains("WPA") && !caps.contains("SAE") && !caps.contains("EAP") -> " ⚠ открытая сеть"
                caps.contains("WPA-") && !caps.contains("WPA2") -> " ⚠ устаревший WPA"
                else -> ""
            }
            val twin = if ((bySsid[it.SSID]?.map { r -> r.capabilities.contains("WPA") }?.distinct()?.size ?: 1) > 1) " ⚠ та же сеть с разной защитой (возможный двойник)" else ""
            sb.append("${if (it.SSID.isEmpty()) "(скрытая)" else it.SSID}  ${it.level} дБм  ${it.frequency} МГц\n  $caps$flag$twin\n")
        }
        return sb.toString()
    }
}
