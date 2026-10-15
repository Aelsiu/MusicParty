package com.thornex.musicparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
// androidx.media:media 兼容库是「混合包名」：
//   MediaMetadataCompat / MediaSessionCompat / PlaybackStateCompat → android.support.v4.media.*
//   MediaStyle / MediaButtonReceiver                             → androidx.media.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通知栏/锁屏媒体控制器。
 * Web 端通过 JS 桥把 {title,artist,position,duration,paused,coverUrl} 推到这里，
 * 原生建立 MediaSession + MediaStyle 通知；通知按钮经 MediaButtonReceiver
 * 触发 session 回调 → onControl 桥回 Web 端执行播放/暂停/下一首。
 */
object MediaSessionManager {

    const val NOTIF_ID = 1   // 与前台服务共用同一通知 ID，避免双通知

    private const val CHANNEL_ID = "media_session"

    private lateinit var appContext: Context
    private var session: MediaSessionCompat? = null
    private var notification: Notification? = null
    private val mediaGeneration = java.util.concurrent.atomic.AtomicLong()
    private var videoContext: JSONObject? = null
    private var mediaMode = "NONE"
    private var actionSequence = 0L
    private var publicationSequence = 0L
    private var bridgeGeneration = 0L
    @Synchronized fun bridgeGeneration(): Long = bridgeGeneration
    @Synchronized fun resetWebContext() {
        bridgeGeneration++; publicationSequence = 0; videoContext = null; mediaMode = "NONE"
        mediaGeneration.incrementAndGet()
        session?.isActive = false
        session?.setPlaybackState(PlaybackStateCompat.Builder().setActions(0L).setState(PlaybackStateCompat.STATE_NONE,0L,0f).build())
        session?.setMetadata(null)
        publishIdleNotification()
    }
    @Synchronized fun dispatchNotification(action: String, context: String, mode: String, generation: Long) {
        if (generation != bridgeGeneration || mode != mediaMode
            || context != (videoContext?.toString() ?: "")) return
        dispatch(action)
    }
    @Synchronized private fun dispatch(action: String) {
        if (session?.isActive != true) return
        val context = videoContext
        if (context != null) {
            if (mediaMode == "VIDEO" && action != "play") return
            val envelope = JSONObject(context.toString()).put("action", action).put("actionSequence", ++actionSequence)
            onControl?.invoke(envelope.toString())
        } else if (mediaMode == "MUSIC") onControl?.invoke(action)
    }

    /** Web 端控制回调：通知按钮 → 执行 Web 端动作（由 MainActivity 注入） */
    var onControl: ((String) -> Unit)? = null

    /** 初始化（幂等）：建 session + 频道 + 初始通知。由 Service 与 MainActivity 都可调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (session != null) return

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media Session", NotificationManager.IMPORTANCE_LOW)
            )
        }

        session = MediaSessionCompat(appContext, "MusicPartySession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { dispatch("play") }
                override fun onPause() { dispatch("pause") }
                override fun onSkipToNext() { dispatch("next") }
            })
            isActive = false
        }
        notification = buildIdleNotification()
    }

    /** 前台服务启动时需要的当前通知 */
    fun currentNotification(): Notification = notification
        ?: NotificationCompat.Builder(appContext, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play).build()

    /** 由 JS 桥调用：Web 端推送播放状态 */
    @Synchronized fun update(json: String) {
        val s = session ?: return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        if (o.has("bridgeVersion") && o.optInt("bridgeVersion") != 2) return
        if (!o.has("bridgeVersion") && o.optString("mediaMode", "MUSIC") != "MUSIC") return
        if (o.optInt("bridgeVersion") == 2) {
            val sequence = o.optLong("publicationSequence", -1)
            if (o.optLong("bridgeGeneration", -1) != bridgeGeneration || sequence <= publicationSequence) return
            val mode = o.optString("mediaMode")
            if (mode !in listOf("MUSIC", "VIDEO", "NONE")) return
            if (mode != "NONE") {
                val context = o.optJSONObject("context") ?: return
                if (context.optInt("bridgeVersion") != 2 || context.optLong("bridgeGeneration", -1) != bridgeGeneration
                    || context.optString("mediaMode") != mode || context.optString("ownerToken").isBlank()
                    || context.optString("roomId").isBlank() || !context.has("clientGeneration")
                    || !context.has("playbackEpoch") || !context.has("modeEpoch") || !context.has("playbackId")
                    || !context.has("rendererGeneration")) return
            }
            publicationSequence = sequence
        }
        val generation = mediaGeneration.incrementAndGet()
        mediaMode = o.optString("mediaMode", "MUSIC")
        videoContext = if (o.optInt("bridgeVersion") == 2) o.optJSONObject("context") else null
        if (mediaMode == "NONE" || (mediaMode == "VIDEO" && videoContext == null)) {
            s.isActive = false
            s.setMetadata(null)
            s.setPlaybackState(PlaybackStateCompat.Builder().setActions(0L).setState(PlaybackStateCompat.STATE_NONE, 0L, 0f).build())
            publishIdleNotification()
            return
        }
        s.isActive = true
        val title = o.optString("title", "MirthParty")
        val artist = o.optString("artist", "")
        val positionSec = o.optLong("position", 0L)
        val durationSec = o.optLong("duration", 0L)
        val paused = o.optBoolean("paused", false)
        val coverUrl = o.optString("coverUrl", "")

        s.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
            .build())

        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    or (if (mediaMode == "MUSIC") PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L)
            )
            .setState(
                if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                positionSec * 1000L, 1f
            )
            .build())

        notification = buildNotification(title, artist, paused, null)
        NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification!!)

        // 异步加载封面作大图（失败不影响控制）
        if (coverUrl.isNotEmpty()) loadArtworkAsync(coverUrl, title, artist, paused, generation)
    }

    @Synchronized fun destroy() {
        mediaGeneration.incrementAndGet()
        onControl = null
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun loadArtworkAsync(coverUrl: String, title: String, artist: String, paused: Boolean, generation: Long) {
        Thread {
            var stream: java.io.InputStream? = null
            try {
                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Referer", "https://music.163.com/")
                stream = conn.inputStream
                val bmp = BitmapFactory.decodeStream(stream)
                synchronized(MediaSessionManager) {
                if (bmp != null && generation == mediaGeneration.get()) {
                    val s = session ?: return@Thread
                    // MediaMetadataCompat 没有 buildUpon()：用拷贝构造 Builder(旧元数据)
                    val cur = s.controller.metadata
                    val meta = (if (cur != null) MediaMetadataCompat.Builder(cur)
                                else MediaMetadataCompat.Builder())
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                        .build()
                    s.setMetadata(meta)
                    notification = buildNotification(title, artist, paused, bmp)
                    NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification!!)
                }
                }
            } catch (e: Exception) {
                // 封面加载失败不影响控制
            } finally {
                try { stream?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun buildNotification(title: String, artist: String, paused: Boolean, art: Bitmap?): Notification {
        val style = MediaStyle()
            .setMediaSession(session!!.sessionToken)
            .setShowActionsInCompactView(*(if (mediaMode == "MUSIC") intArrayOf(0, 1) else if (paused) intArrayOf(0) else intArrayOf()))

        val b = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(style)

        if (art != null) b.setLargeIcon(art)

        if (mediaMode == "MUSIC") b.addAction(mediaAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "播放" else "暂停",
            if (paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE))
        else if (paused) b.addAction(mediaAction(android.R.drawable.ic_media_play, "恢复观看", PlaybackStateCompat.ACTION_PLAY))
        if (mediaMode == "MUSIC") b.addAction(mediaAction(android.R.drawable.ic_media_next, "下一首", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))

        return b.build()
    }

    private fun mediaAction(icon: Int, title: String, action: Long): NotificationCompat.Action {
        val actionName = when (action) {
            PlaybackStateCompat.ACTION_PLAY -> "play"
            PlaybackStateCompat.ACTION_PAUSE -> "pause"
            else -> "next"
        }
        val context = videoContext?.toString() ?: ""
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$bridgeGeneration|$mediaMode|$context|$actionName".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val intent = Intent(appContext, MediaControlReceiver::class.java)
            .setData(Uri.parse("musicparty://media-action/$identity"))
            .putExtra("action", actionName).putExtra("context", context)
            .putExtra("mode", mediaMode).putExtra("bridgeGeneration", bridgeGeneration)
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(icon, title, pi)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(appContext, 0,
        Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildIdleNotification(): Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("MirthParty")
        .setContentIntent(contentIntent()).setOnlyAlertOnce(true).setOngoing(true).build()

    private fun publishIdleNotification() {
        if (!::appContext.isInitialized || session == null) return
        notification = buildIdleNotification()
        NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification!!)
    }
}
