package app.yummytv

import com.google.gson.JsonElement

data class ObjResp<T>(val response: T? = null)
data class ListResp<T>(val response: List<T>? = null)

data class Poster(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val huge: String? = null,
    val fullsize: String? = null
)

data class Status(val title: String? = null, val alias: String? = null)
data class TypeInfo(val name: String? = null)

// rating бывает и числом, и объектом — поэтому JsonElement
data class TopInfo(val global: Int? = null, val category: Int? = null)

data class AnimeCard(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val year: Int? = null,
    val description: String? = null,
    val anime_status: Status? = null,
    val type: TypeInfo? = null,
    val rating: JsonElement? = null,
    val views: Long? = null,
    val top: TopInfo? = null,
    val date: Long? = null
)

data class Episodes(val aired: Int? = null, val count: Int? = null)

data class ScheduleItem(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val episodes: Episodes? = null
)

data class NewVideo(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val ep_title: String? = null,
    val dub_title: String? = null,
    val player_title: String? = null,
    val video_id: Long = 0
)

data class LastWatch(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val ep_title: String? = null,
    val video_id: Long = 0,
    val end_time: Double? = null,
    val duration: Double? = null,
    val date: Double? = null
)

data class Feed(
    val new: List<AnimeCard>? = null,
    val announcements: List<AnimeCard>? = null,
    val recommends: List<AnimeCard>? = null,
    val new_videos: List<NewVideo>? = null,
    val last_watches: List<LastWatch>? = null,
    val schedule: List<ScheduleItem>? = null
)

data class VideoData(val dubbing: String? = null, val player: String? = null)

data class VideoItem(
    val video_id: Long = 0,
    val iframe_url: String? = null,
    val data: VideoData? = null,
    val number: String? = null,
    val index: Double? = null,
    val duration: Int? = null
)

data class ViewingData(val id: Long? = null, val index: Double? = null, val text: String? = null)

data class ViewingItem(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val year: Int? = null,
    val type: TypeInfo? = null,
    val anime_status: Status? = null,
    val data: ViewingData? = null
)

data class AnimeDetail(
    val anime_id: Long = 0,
    val anime_url: String? = null,
    val title: String? = null,
    val poster: Poster? = null,
    val year: Int? = null,
    val description: String? = null,
    val anime_status: Status? = null,
    val type: TypeInfo? = null,
    val rating: JsonElement? = null,
    val videos: List<VideoItem>? = null,
    val viewing_order: List<ViewingItem>? = null
)

data class LoginBody(
    val login: String,
    val password: String,
    val need_json: Boolean = true,
    val recaptcha_response: String? = null
)

data class LoginData(val success: Boolean? = null, val token: String? = null)
data class LoginResp(val response: LoginData? = null)
data class Profile(val id: Long = 0, val nickname: String? = null)
data class TokenData(val token: String? = null)
data class MyList(val list: Int? = null)
data class SetListBody(val list: Int, val date: Long? = null)
data class WatchBody(val date: Long, val time: Int = 0, val duration: Int? = null)

fun JsonElement?.ratingValue(): Double? {
    if (this == null || this.isJsonNull) return null
    return runCatching {
        when {
            this.isJsonPrimitive -> this.asDouble
            this.isJsonObject -> this.asJsonObject.get("average")?.takeIf { !it.isJsonNull }?.asDouble
            else -> null
        }
    }.getOrNull()
}
