/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.retromusicprovider.xposed

import android.content.Context
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.io.File

/**
 * Lyricon provider for Retro Music Player 6.6.x.
 *
 * Retro Music exposes its current Song from MusicService.currentSong and
 * stores external synced lyrics through LyricUtil. We hook the point where
 * Retro Music refreshes its MediaSession metadata, then mirror that Song and
 * its local/embedded lyrics into Lyricon.
 */
object RetroMusic : YukiBaseHooker() {
    private const val TAG = Constants.LOG_TAG
    private const val MUSIC_SERVICE = "code.name.monkey.retromusic.service.MusicService"
    private const val LYRIC_UTIL = "code.name.monkey.retromusic.util.LyricUtil"

    private var provider: LyriconProvider? = null
    private var lastSongKey: String? = null

    override fun onHook() {
        YLog.info(tag = TAG, msg = "Starting Retro Music provider")

        onAppLifecycle {
            onCreate {
                initProvider(this)
            }
            onTerminate {
                provider?.unregister()
                provider = null
                lastSongKey = null
            }
        }

        hookRetroMusicService()
        hookMediaSession()
    }

    private fun initProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            register()
        }
        YLog.info(tag = TAG, msg = "Lyricon provider registered")
    }

    private fun hookRetroMusicService() {
        MUSIC_SERVICE.toClassOrNull()
            ?.resolve()
            ?.apply {
                firstMethod {
                    name = "updateMediaSessionMetaData"
                }.hook {
                    after {
                        updateCurrentSong(instance)
                    }
                }
            }

        YLog.info(tag = TAG, msg = "Hooked MusicService.updateMediaSessionMetaData")
    }

    private fun updateCurrentSong(service: Any?) {
        if (service == null) return

        try {
            val retroSong = XposedHelpers.callMethod(service, "getCurrentSong") ?: return

            val id = getLong(retroSong, "getId")
            val title = getString(retroSong, "getTitle")
            val artist = getString(retroSong, "getArtistName")
            val duration = getLong(retroSong, "getDuration")
            val data = getString(retroSong, "getData")

            if (id == -1L || title.isNullOrBlank()) {
                provider?.player?.setSong(null)
                return
            }

            val songKey = "$id|$title|$artist|$duration|$data"
            if (songKey == lastSongKey) return
            lastSongKey = songKey

            // First clear any lyrics from the previous track.
            provider?.player?.setSong(
                Song(
                    id = id.toString(),
                    name = title,
                    artist = artist,
                    duration = duration
                )
            )

            val rawLyrics = loadRetroMusicLyrics(retroSong)
            if (rawLyrics.isNullOrBlank()) {
                YLog.debug(tag = TAG, msg = "No local/embedded lyrics: $title - $artist")
                return
            }

            val document = EnhanceLrcParser.parse(rawLyrics, duration)
            val lines = document.lines.filter { !it.text.isNullOrBlank() }

            if (lines.isEmpty()) {
                YLog.debug(tag = TAG, msg = "Lyrics parsed but contain no lines: $title")
                return
            }

            provider?.player?.setSong(
                Song(
                    id = id.toString(),
                    name = title,
                    artist = artist,
                    duration = duration,
                    lyrics = lines
                )
            )

            YLog.info(tag = TAG, msg = "Lyrics loaded: $title - $artist")
        } catch (e: Throwable) {
            YLog.error(tag = TAG, msg = "Failed to update Retro Music song", e = e)
        }
    }

    /**
     * Reuse Retro Music's own lyric lookup so the provider follows the same
     * priority as the player itself:
     *   1. song-directory .lrc
     *   2. RetroMusic/lyrics/title - artist.lrc
     *   3. embedded synchronized LYRICS tag
     */
    private fun loadRetroMusicLyrics(song: Any): String? {
        return try {
            val loader = appClassLoader ?: return null
            val utilClass = loader.loadClass(LYRIC_UTIL)
            val instance = utilClass.getDeclaredField("INSTANCE").apply {
                isAccessible = true
            }.get(null)

            val syncedFileMethod = utilClass.getDeclaredMethod(
                "getSyncedLyricsFile",
                song.javaClass
            ).apply { isAccessible = true }

            val syncedFile = syncedFileMethod.invoke(instance, song) as? File
            if (syncedFile?.isFile == true) {
                return syncedFile.readText(Charsets.UTF_8)
            }

            val data = getString(song, "getData")
            if (data.isNullOrBlank()) return null

            val embeddedMethod = utilClass.getDeclaredMethod(
                "getEmbeddedSyncedLyrics",
                String::class.java
            ).apply { isAccessible = true }

            embeddedMethod.invoke(instance, data) as? String
        } catch (e: Throwable) {
            YLog.error(tag = TAG, msg = "Retro Music lyric lookup failed", e = e)
            null
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState ?: return@after
                    provider?.player?.setPlaybackState(state)
                }
            }
        }
    }

    private fun getString(obj: Any, getter: String): String? = try {
        XposedHelpers.callMethod(obj, getter)?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun getLong(obj: Any, getter: String): Long = try {
        (XposedHelpers.callMethod(obj, getter) as? Number)?.toLong() ?: 0L
    } catch (_: Throwable) {
        0L
    }
}
