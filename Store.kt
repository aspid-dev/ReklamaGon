package local.phonediag

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object Store {
    fun p(c: Context): SharedPreferences = c.getSharedPreferences("diag", Context.MODE_PRIVATE)
    fun mode(c: Context) = p(c).getInt("mode", 0)
    fun digestMin(c: Context) = p(c).getInt("digest", 21 * 60)
    fun retention(c: Context) = p(c).getInt("retention", 30)
    fun designMah(c: Context) = p(c).getInt("design_mah", 3010)
    fun guardOn(c: Context) = p(c).getBoolean("guard", false)
    val modeNames = listOf("Эконом", "Обычный", "Усиленный")
}

/** Журнал событий: только на телефоне. Уровни: 0 справка, 1 мелкое, 2 серьёзное. */
object Journal {
    data class E(val ts: Long, val level: Int, val text: String)

    private fun f(c: Context) = File(c.filesDir, "journal.log")

    @Synchronized
    fun add(c: Context, level: Int, text: String) {
        f(c).appendText("${System.currentTimeMillis()}\t$level\t${text.replace('\n', ' ').replace('\t', ' ')}\n")
    }

    @Synchronized
    fun read(c: Context, since: Long = 0): List<E> {
        if (!f(c).exists()) return emptyList()
        return f(c).readLines().mapNotNull { l ->
            val a = l.split('\t', limit = 3)
            if (a.size < 3) null
            else a[0].toLongOrNull()?.let { t -> if (t >= since) E(t, a[1].toIntOrNull() ?: 0, a[2]) else null }
        }
    }

    @Synchronized
    fun prune(c: Context) {
        val keep = read(c, System.currentTimeMillis() - Store.retention(c) * 86_400_000L)
        f(c).writeText(keep.joinToString("") { "${it.ts}\t${it.level}\t${it.text}\n" })
    }

    @Synchronized
    fun clear(c: Context) { f(c).delete() }
}

/** Самообучение: онлайн-статистика Велфорда по каждому показателю. Возвращает z-оценку или null, пока учится. */
object Learner {
    fun update(c: Context, key: String, v: Double): Double? {
        val p = Store.p(c)
        val raw = p.getString("L_$key", null)
        var n = 0; var mean = 0.0; var m2 = 0.0
        if (raw != null) {
            val a = raw.split(",")
            n = a[0].toInt(); mean = a[1].toDouble(); m2 = a[2].toDouble()
        }
        var z: Double? = null
        if (n >= 3) {
            val sd = max(max(sqrt(m2 / (n - 1)), abs(mean) * 0.08), 1e-6)
            z = (v - mean) / sd
        }
        n++
        val d = v - mean
        mean += d / n
        m2 += d * (v - mean)
        p.edit().putString("L_$key", "$n,$mean,$m2").apply()
        return z
    }

    fun reset(c: Context) {
        val p = Store.p(c)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("L_") }.forEach { e.remove(it) }
        e.apply()
    }
}

/** Правила поиска шпионов. Можно заменить импортом файла. */
object Rules {
    fun load(c: Context): JSONObject {
        val f = File(c.filesDir, "rules.json")
        val t = if (f.exists()) f.readText() else c.assets.open("rules.json").bufferedReader().use { it.readText() }
        return JSONObject(t)
    }

    fun list(j: JSONObject, key: String): List<String> {
        val a = j.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun importText(c: Context, text: String): String = try {
        val j = JSONObject(text)
        require(j.has("version") && j.has("keywords") && j.has("combos")) { "нет полей version/keywords/combos" }
        File(c.filesDir, "rules.json").writeText(text)
        "Правила импортированы, версия ${j.getInt("version")}"
    } catch (e: Exception) {
        "Файл отклонён: ${e.message}"
    }
}
