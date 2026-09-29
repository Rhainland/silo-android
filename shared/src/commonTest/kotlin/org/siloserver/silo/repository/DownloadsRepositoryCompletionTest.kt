package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatusEvent
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.SiloDeviceMetadata
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DownloadsApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.DownloadCreationV2Api
import org.siloserver.silo.network.apiv2.DownloadRegistryV2Api
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private object CompletionTestNoDevices : DeviceMetadataProvider {
    override suspend fun current(): SiloDeviceMetadata? = null
}

private fun registryStub() = DownloadRegistryV2Api(HttpClient(), TokenManagerImpl(), CompletionTestNoDevices, ApiV2Gate.Unrestricted)

private class RegistryFake : DownloadsApi(
    registry = registryStub(),
    tokens = TokenManagerImpl(),
    creation = DownloadCreationV2Api(HttpClient(), TokenManagerImpl(), CompletionTestNoDevices, registryStub(), ApiV2Gate.Unrestricted),
) {
    var server: List<DownloadRecord> = emptyList()
    var reportAnswer: (DownloadStatusEvent) -> ApiResult<DownloadRecord> = { ApiResult.NetworkError(IllegalStateException("offline")) }
    val reports = mutableListOf<Pair<String, DownloadStatusEvent>>()

    override suspend fun list(scope: AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
        ApiResult.Success(DownloadsListResponse(server))

    override suspend fun reportStatus(id: String, event: DownloadStatusEvent, scope: AuthScopeSnapshot?): ApiResult<DownloadRecord> {
        reports += id to event
        return reportAnswer(event)
    }
}

private fun entry(status: String, revision: Int? = 1, bytes: Long = 0, completedAt: String? = null, quality: String? = "original") = DownloadRecord(
    id = "dl", contentId = "movie", mediaFileId = 42, fileSize = 1000, bytesSent = bytes, kind = "queued",
    status = status, createdAt = "2026-09-29T00:00:00Z", completedAt = completedAt, revision = revision,
    quality = quality, effectiveQuality = quality,
)

class DownloadsRepositoryCompletionTest {

    @Test
    fun `refresh keeps a local completion while the server still says ready`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("ready")) }
        val repo = DownloadsRepository(api)
        repo.refresh()
        // The worker publishes the finished file before the server hears about it.
        repo.upsertLocal(entry("completed", bytes = 1000, completedAt = "2026-09-29T00:01:00Z"))

        repo.refresh()

        val row = repo.records.value.single()
        assertEquals("completed", row.status)
        assertEquals(1000, row.bytesSent)
        assertEquals("2026-09-29T00:01:00Z", row.completedAt)
    }

    @Test
    fun `refresh keeps a completion seeded from local metadata without a revision`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("downloading")) }
        val repo = DownloadsRepository(api)
        repo.seedFromSidecars(listOf(entry("completed", revision = null, bytes = 1000)))

        repo.refresh()

        assertEquals("completed", repo.records.value.single().status)
    }

    @Test
    fun `a replaced server revision wins over the old local completion`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("ready", revision = 2)) }
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", revision = 1, bytes = 1000))

        repo.refresh()

        val row = repo.records.value.single()
        assertEquals("ready", row.status)
        assertEquals(2, row.revision)
    }

    @Test
    fun `a completion saved without a revision does not cover a replaced target`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("ready", revision = 2, quality = "2mbps")) }
        val repo = DownloadsRepository(api)
        repo.seedFromSidecars(listOf(entry("completed", revision = null, bytes = 1000)))

        repo.refresh()

        assertEquals("ready", repo.records.value.single().status)
    }

    @Test
    fun `server failure states are not masked by a local completion`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("failed")) }
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", bytes = 1000))

        repo.refresh()

        assertEquals("failed", repo.records.value.single().status)
    }

    @Test
    fun `a completion report records the server acknowledgement and keeps local state`() = runTest {
        val api = RegistryFake()
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", bytes = 1000))
        val event = DownloadStatusEvent("completed", "2026-09-29T00:01:00.000Z", 1)
        api.reportAnswer = { entry("completed", completedAt = "2026-09-29T00:01:00Z").copy(statusEventAt = it.updatedAt).let { ApiResult.Success(it) } }

        assertIs<ApiResult.Success<DownloadRecord>>(repo.reportStatus("dl", event))

        assertEquals(listOf("dl" to event), api.reports)
        val row = repo.records.value.single()
        assertEquals("completed", row.status)
        assertEquals(1000, row.bytesSent)
        assertEquals("2026-09-29T00:01:00Z", row.completedAt)
        assertEquals("2026-09-29T00:01:00.000Z", row.statusEventAt)
    }

    @Test
    fun `a late downloading answer does not undo a local completion`() = runTest {
        val api = RegistryFake()
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", bytes = 1000))
        api.reportAnswer = { ApiResult.Success(entry("downloading")) }

        repo.reportStatus("dl", DownloadStatusEvent("downloading", "2026-09-29T00:00:30.000Z", 1))

        assertEquals("completed", repo.records.value.single().status)
    }

    @Test
    fun `a late downloading answer does not revive a failed transfer`() = runTest {
        val api = RegistryFake()
        val repo = DownloadsRepository(api)
        // The worker failed permanently and cleaned up after the report was sent.
        repo.upsertLocal(entry("failed"))
        api.reportAnswer = { ApiResult.Success(entry("downloading").copy(statusEventAt = it.updatedAt)) }

        repo.reportStatus("dl", DownloadStatusEvent("downloading", "2026-09-29T00:00:30.000Z", 1))

        val row = repo.records.value.single()
        assertEquals("failed", row.status)
        assertEquals("2026-09-29T00:00:30.000Z", row.statusEventAt)
    }

    @Test
    fun `an acknowledgement for a superseded revision leaves the replacement alone`() = runTest {
        val api = RegistryFake().apply { server = listOf(entry("ready", revision = 2, quality = "2mbps")) }
        val repo = DownloadsRepository(api)
        repo.refresh()
        api.reportAnswer = { ApiResult.Success(entry("completed", completedAt = "2026-09-29T00:01:00Z").copy(statusEventAt = it.updatedAt)) }

        repo.reportStatus("dl", DownloadStatusEvent("completed", "2026-09-29T00:01:00.000Z", 1))

        assertEquals(entry("ready", revision = 2, quality = "2mbps"), repo.records.value.single())
    }

    @Test
    fun `an older acknowledgement keeps the newer status event time`() = runTest {
        val api = RegistryFake()
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", bytes = 1000).copy(statusEventAt = "2026-09-29T00:01:00.000Z"))
        api.reportAnswer = { ApiResult.Success(entry("downloading").copy(statusEventAt = it.updatedAt)) }

        repo.reportStatus("dl", DownloadStatusEvent("downloading", "2026-09-29T00:00:30.000Z", 1))

        assertEquals("2026-09-29T00:01:00.000Z", repo.records.value.single().statusEventAt)
    }

    @Test
    fun `a failed report leaves the cache untouched`() = runTest {
        val api = RegistryFake()
        val repo = DownloadsRepository(api)
        repo.upsertLocal(entry("completed", bytes = 1000))
        api.reportAnswer = { ApiResult.Error(409, "conflict", "revision changed") }

        val result = repo.reportStatus("dl", DownloadStatusEvent("completed", "2026-09-29T00:01:00.000Z", 1))

        assertEquals(409, assertIs<ApiResult.Error>(result).code)
        assertEquals(entry("completed", bytes = 1000), repo.records.value.single())
    }
}
