package app.yummytv

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Управление плеером с беспроводных (и проводных) наушников.
 *
 * Наушники отправляют системе «медиа-кнопки». Система передаёт их активной медиа-сессии,
 * поэтому на время просмотра мы регистрируем свою сессию и считаем её «играющей».
 * Одно нажатие — пауза/продолжить, двойное — следующая серия, тройное — предыдущая
 * (двойное и тройное нажатие разбирает сама система), долгое нажатие на «вперёд/назад» — перемотка.
 */
class HeadsetControls(
    context: Context,
    private val onToggle: () -> Unit,
    private val onStep: (Int) -> Unit,
    private val onSeek: (Int) -> Unit
) {
    private var session: MediaSession? = MediaSession(context, "TVYM-player")
    private var playing = true

    init {
        val s = session!!
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSession.Callback() {
            // Реальное состояние плеера внутри страницы нам недоступно, поэтому любое «играть/пауза» — переключение
            override fun onPlay() = toggle()
            override fun onPause() = toggle()
            override fun onSkipToNext() = onStep(1)
            override fun onSkipToPrevious() = onStep(-1)
            override fun onFastForward() = onSeek(1)
            override fun onRewind() = onSeek(-1)
        })
        update()
        s.isActive = true
    }

    private fun toggle() {
        onToggle()
        flip()
    }

    /** Вызывается, когда пауза/воспроизведение переключены не из сессии (например, кнопкой пульта) */
    fun flip() {
        playing = !playing
        update()
    }

    fun setNowPlaying(title: String, subtitle: String) {
        try {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    private fun update() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        try {
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (playing) 1f else 0f
                    )
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            session?.isActive = false
            session?.release()
        } catch (e: Exception) {
        }
        session = null
    }
}
