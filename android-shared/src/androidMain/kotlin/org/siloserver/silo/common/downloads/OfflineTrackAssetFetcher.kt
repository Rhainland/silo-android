package org.siloserver.silo.common.downloads

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.OfflineManifestSubtitle
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.download.OfflineTrackInfo
import org.siloserver.silo.model.download.decodeOfflineManifestTracks
import org.siloserver.silo.model.download.isOfflineSubtitleFetchUrl
import org.siloserver.silo.model.download.offlineSubtitleExtension
import org.siloserver.silo.model.download.offlineSubtitleFormat
import org.siloserver.silo.model.download.toOfflineTrackInfo
import org.siloserver.silo.playback.orNullIfBlank
import java.io.File
import java.io.IOException

/**
 * Captures what offline video playback needs once the media bytes are down:
 * the offline manifest's audio tracks (positions inside the delivered file) and
 * the subtitle sidecars it lists, each fetched once into private storage.
 *
 * Everything here is best effort. A missing manifest, an older server, or a
 * sidecar that fails to fetch never fails the video download; the download
 * then plays with whatever was captured (or the legacy offline behaviour).
 */
internal class OfflineTrackAssetFetcher(
    private val httpClient: HttpClient,
    private val storage: DownloadStorage,
) {
    suspend fun fetch(
        downloadId: String,
        serverId: String,
        profileId: String,
        fileId: Int,
        configure: HttpRequestBuilder.() -> Unit,
    ): OfflineTrackInfo? {
        val manifest = try {
            val response = httpClient.get("/api/v2/downloads/${downloadId.encodeURLPathPart()}/manifest") {
                configure()
            }
            if (response.status != HttpStatusCode.OK) {
                Log.i(TAG, "manifest unavailable id=$downloadId status=${response.status.value}")
                return null
            }
            decodeOfflineManifestTracks(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "manifest fetch failed id=$downloadId", e)
            return null
        } ?: return null

        val directory = storage.offlineSubtitleDirectory(serverId, profileId, fileId)
        // A replaced download (new revision) must not keep the previous
        // revision's sidecars around under the same file slot.
        directory?.deleteRecursively()
        val saved = if (directory == null || manifest.subtitles.isEmpty()) {
            emptyList()
        } else {
            manifest.subtitles.mapIndexedNotNull { ordinal, subtitle ->
                fetchSubtitle(downloadId, ordinal, subtitle, directory, configure)
            }
        }
        return manifest.toOfflineTrackInfo(saved)
    }

    private suspend fun fetchSubtitle(
        downloadId: String,
        ordinal: Int,
        subtitle: OfflineManifestSubtitle,
        directory: File,
        configure: HttpRequestBuilder.() -> Unit,
    ): OfflineSubtitleFile? {
        val format = offlineSubtitleFormat(subtitle.format) ?: return null
        if (!isOfflineSubtitleFetchUrl(subtitle.fetchUrl)) {
            Log.w(TAG, "skipping subtitle with unexpected reference id=$downloadId ordinal=$ordinal")
            return null
        }
        val target = File(directory, "$ordinal.${offlineSubtitleExtension(format)}")
        val partial = File(directory, "$ordinal.part")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create $directory")
            httpClient.prepareGet(subtitle.fetchUrl.trim()) {
                configure()
                // Embedded ASS/PGS sidecars are extracted from the source on
                // demand, so the first byte can take a while; keep only an idle
                // timeout, like the media transfer itself.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = SUBTITLE_IDLE_TIMEOUT_MS
                }
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw IOException("HTTP ${response.status.value}")
                }
                var written = 0L
                response.bodyAsChannel().toInputStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_SUBTITLE_BYTES) throw IOException("subtitle exceeds $MAX_SUBTITLE_BYTES bytes")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (written == 0L) throw IOException("empty subtitle")
            }
            if (!partial.renameTo(target)) throw IOException("could not publish $target")
            OfflineSubtitleFile(
                path = target.absolutePath,
                format = format,
                language = subtitle.language.orNullIfBlank(),
                title = subtitle.title.orNullIfBlank(),
                forced = subtitle.forced,
                hearingImpaired = subtitle.hearingImpaired,
            )
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "subtitle fetch failed id=$downloadId ordinal=$ordinal format=$format", e)
            partial.delete()
            target.delete()
            null
        }
    }

    companion object {
        private const val TAG = "OfflineTrackAssets"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SUBTITLE_IDLE_TIMEOUT_MS = 120_000L

        /** PGS tracks for a feature run to tens of MB; text is far smaller. */
        private const val MAX_SUBTITLE_BYTES = 256L * 1024 * 1024

        /** Only video downloads have tracks to capture. */
        fun appliesTo(mediaType: String?): Boolean =
            when (DownloadMediaType.fromWire(mediaType)) {
                DownloadMediaType.Movie, DownloadMediaType.TvShow, DownloadMediaType.Unknown -> true
                DownloadMediaType.Audiobook, DownloadMediaType.Ebook -> false
            }
    }
}
