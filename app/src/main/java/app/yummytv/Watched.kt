package app.yummytv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Scope, который живёт дольше экранов (нужен, чтобы запрос не отменялся при переходе в плеер) */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Простой кэш в памяти с временем жизни */
class TtlCache<K, V>(private val ttlMs: Long) {
    private val map = HashMap<K, Pair<Long, V>>()

    fun get(k: K): V? {
        val e = map[k] ?: return null
        return if (System.currentTimeMillis() - e.first < ttlMs) e.second else null
    }

    fun put(k: K, v: V): V {
        map[k] = System.currentTimeMillis() to v
        return v
    }

    fun remove(k: K) {
        map.remove(k)
    }

    fun clear() {
        map.clear()
    }
}

/** Просмотренные серии (хранятся на устройстве) + выбранные плеер/озвучка для каждого аниме */
object Watched {
    private lateinit var sp: SharedPreferences

    var ids: Set<Long> by mutableStateOf(emptySet<Long>())
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("watched", Context.MODE_PRIVATE)
        ids = sp.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun save() {
        sp.edit().putStringSet("ids", ids.map { it.toString() }.toSet()).apply()
    }

    fun add(videoId: Long) {
        if (videoId == 0L || videoId in ids) return
        ids = ids + videoId
        save()
        Cache.home.remove("watching") // «Продолжить просмотр» нужно обновить
    }

    fun addAll(list: Collection<Long>) {
        val n = ids + list
        if (n.size == ids.size) return
        ids = n
        save()
    }

    /** Номер серии, которую открывали последней (для кнопки «Смотреть N серию») */
    fun lastEp(animeId: Long): String? = sp.getString("e_$animeId", null)

    fun lastEpTime(animeId: Long): Long = sp.getLong("t_$animeId", 0L)

    fun setLastEp(animeId: Long, number: String?) {
        sp.edit()
            .putString("e_$animeId", number)
            .putLong("t_$animeId", System.currentTimeMillis() / 1000)
            .apply()
    }

    fun choice(animeId: Long): Pair<String?, String?> =
        sp.getString("p_$animeId", null) to sp.getString("d_$animeId", null)

    fun setChoice(animeId: Long, player: String?, dub: String?) {
        sp.edit().putString("p_$animeId", player).putString("d_$animeId", dub).apply()
    }
}

/** Отправляем отметку «просмотрено» на сайт (если пользователь вошёл) */
suspend fun syncWatched(videoId: Long, duration: Int?) {
    try {
        Net.api.markWatched(videoId, WatchBody(date = System.currentTimeMillis() / 1000, time = 0, duration = duration))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
    }
}

private fun collectVideoIds(e: JsonElement?, out: MutableSet<Long>) {
    if (e == null || e.isJsonNull) return
    if (e.isJsonArray) {
        e.asJsonArray.forEach { collectVideoIds(it, out) }
    } else if (e.isJsonObject) {
        val o = e.asJsonObject
        val v = o.get("video_id")
        var videoId = 0L
        if (v != null && v.isJsonPrimitive) {
            try {
                videoId = v.asLong
                out.add(videoId)
            } catch (ex: Exception) {
            }
        }
        // если в записи есть и аниме, и номер серии — запоминаем «где остановились»
        try {
            val aid = o.get("anime_id")?.takeIf { it.isJsonPrimitive }?.asLong
            var ep: String? = null
            for (k in listOf("ep_title", "episode", "number")) {
                val x = o.get(k)
                if (x != null && x.isJsonPrimitive && x.asString.isNotBlank()) {
                    ep = x.asString
                    break
                }
            }
            val date = o.get("date")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            if (aid != null && ep != null) Cache.noteServerLast(aid, ep, date, videoId)
        } catch (ex: Exception) {
        }
        for ((_, child) in o.entrySet()) {
            if (child.isJsonArray || child.isJsonObject) collectVideoIds(child, out)
        }
    }
}

/** Подтягиваем историю просмотров с сайта (формат ответа в документации не описан, поэтому читаем «на ощупь») */
suspend fun importWatchHistory() {
    try {
        val found = HashSet<Long>()
        for (page in 0 until 3) {
            val r = Net.api.watchHistory(limit = 100, offset = page * 100)
            val before = found.size
            collectVideoIds(r, found)
            if (found.size == before) break
        }
        if (found.isNotEmpty()) Watched.addAll(found)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
    }
}
