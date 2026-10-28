package app.yummytv

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

interface YummyApi {
    @GET("feed")
    suspend fun feed(): ObjResp<Feed>

    @GET("search")
    suspend fun search(
        @Query("q") q: String,
        @Query("limit") limit: Int = 40,
        @Query("offset") offset: Int = 0
    ): ListResp<AnimeCard>

    @GET("anime/{url}")
    suspend fun anime(
        @Path("url") url: String,
        @Query("need_videos") needVideos: Boolean = true
    ): ObjResp<AnimeDetail>

    @POST("profile/login")
    suspend fun login(@Body body: LoginBody): Response<LoginResp>

    @GET("profile")
    suspend fun profile(): ObjResp<Profile>

    @GET("profile/token")
    suspend fun refreshToken(): ObjResp<TokenData>

    @GET("users/{id}/lists/{list}")
    suspend fun userList(@Path("id") id: Long, @Path("list") list: Int): ListResp<AnimeCard>

    @GET("anime/{id}/list")
    suspend fun myList(@Path("id") id: Long): ObjResp<MyList>

    @PUT("anime/{id}/list")
    suspend fun setList(@Path("id") id: Long, @Body body: SetListBody): ObjResp<JsonElement>

    @DELETE("anime/{id}/list")
    suspend fun removeFromList(@Path("id") id: Long): Response<ResponseBody>
}

object Net {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                    .header("X-Application", Config.APP_TOKEN)
                    .header("Accept", "application/json")
                    .header("Lang", "ru")
                val t = Session.token
                if (!t.isNullOrBlank()) b.header("Authorization", "Bearer $t")
                chain.proceed(b.build())
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val api: YummyApi by lazy {
        Retrofit.Builder()
            .baseUrl(Config.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YummyApi::class.java)
    }
}

fun errText(e: Throwable): String = when (e) {
    is HttpException -> when (e.code()) {
        401 -> "Нужно войти заново (401)"
        403 -> "Доступ запрещён (403). Проверьте токен приложения в файле Config.kt"
        420 -> "Сайт требует капчу (420)"
        else -> "Ошибка сервера: ${e.code()}"
    }
    is IOException -> "Нет соединения с сервером"
    is JsonParseException -> "Неожиданный формат ответа: ${e.message}"
    else -> e.message ?: e.toString()
}
