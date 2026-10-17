package org.siloserver.silo.model.feature

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.RequestsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository

class RequestsFeatureStoreTest {

    @Test
    fun aBlockedProfileKeepsRequestsHidden() = runTest {
        val store = RequestsFeatureStore(RequestsRepository(FakeRequestsApi(ApiResult.Success(available(allowed = false)))))

        store.refresh()

        assertFalse(store.isEnabled.value)
    }

    @Test
    fun startsHiddenAndFollowsSuccessfulStatusProbe() = runTest {
        val store = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.Success(available()),
                ),
            ),
        )

        assertFalse(store.isEnabled.value)

        store.refresh()

        assertTrue(store.isEnabled.value)
    }

    @Test
    fun transientFailureKeepsPreviousCapabilityValue() = runTest {
        val api = FakeRequestsApi(
            ApiResult.Success(available()),
            ApiResult.NetworkError(IllegalStateException("offline")),
        )
        val store = RequestsFeatureStore(RequestsRepository(api))

        store.refresh()
        store.refresh()

        assertTrue(store.isEnabled.value)
    }

    @Test
    fun resetHidesRequestsBeforeNextProbe() = runTest {
        val repository = RequestsRepository(
            FakeRequestsApi(
                ApiResult.Success(available()),
            ),
        )
        val store = RequestsFeatureStore(repository)
        val seen = RequestMediaResult(mediaType = RequestMediaType.Movie, tmdbId = 7, title = "Seen")
        repository.cache.seed(seen)

        store.refresh()
        store.reset()

        assertFalse(store.isEnabled.value)
        // Sign-out and server or profile switches all reset here, so the
        // previous session's request pages go with it.
        assertNull(repository.cache.firstFrameDetail(RequestDetailCache.Key(RequestMediaType.Movie, 7)))
    }
}

class RequestsFeatureStoreBlockedMidSessionTest {

    @Test
    fun permissionDeniedFromARequestCallRefreshesTheCapability() = runTest {
        val api = FakeRequestsApi(
            ApiResult.Success(available()),
            ApiResult.Success(available(allowed = false)),
        )
        api.discoverResult = ApiResult.Error(403, "permission_denied", "Requests are not allowed for this profile")
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository)
        store.refresh()
        assertTrue(store.isEnabled.value)

        repository.discover()

        assertFalse(store.isEnabled.value)
    }

    @Test
    fun permissionDeniedFromADetailReadRefreshesTheCapability() = runTest {
        val api = FakeRequestsApi(
            ApiResult.Success(available()),
            ApiResult.Success(available(allowed = false)),
        )
        api.detailResult = ApiResult.Error(403, "permission_denied", "Requests are not allowed for this profile")
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository)
        store.refresh()

        repository.detail(RequestMediaType.Movie, 7)

        assertFalse(store.isEnabled.value)
    }

    @Test
    fun aRefusedPrefetchStopsTheBatchAfterOneProbe() = runTest {
        val api = FakeRequestsApi(
            ApiResult.Success(available()),
            ApiResult.Success(available(allowed = false)),
        )
        api.detailResult = ApiResult.Error(403, "permission_denied", "Requests are not allowed for this profile")
        val repository = RequestsRepository(api, RequestDetailCache(prefetchScope = backgroundScope))
        val store = RequestsFeatureStore(repository)
        store.refresh()

        repository.prefetch((1..3).map { ownRequest(tmdbId = it) })
        runCurrent()

        assertEquals(1, api.detailCalls)
        assertEquals(2, api.statusCalls)
        assertFalse(store.isEnabled.value)
    }

    @Test
    fun anOlderProbeAnsweringLateDoesNotRestoreRequests() = runTest {
        val olderAnswer = CompletableDeferred<ApiResult<RequestsFeatureStatus>>()
        val api = FakeRequestsApi(ApiResult.Success(available(allowed = false)))
        api.firstStatus = olderAnswer
        val store = RequestsFeatureStore(RequestsRepository(api))

        val older = launch { store.refresh() }
        runCurrent()
        store.refresh()
        olderAnswer.complete(ApiResult.Success(available()))
        older.join()

        assertFalse(store.isEnabled.value)
    }

    @Test
    fun aRefusalLandingAfterResetDoesNotProbe() = runTest {
        val api = FakeRequestsApi(ApiResult.Success(available()))
        val discoverAnswer = CompletableDeferred<Unit>()
        api.discoverGate = discoverAnswer
        api.discoverResult = ApiResult.Error(403, "permission_denied", "Requests are not allowed for this profile")
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository)
        store.refresh()

        val discover = launch { repository.discover() }
        runCurrent()
        store.reset()
        discoverAnswer.complete(Unit)
        discover.join()

        assertEquals(1, api.statusCalls)
    }

    @Test
    fun otherRefusalsDoNotProbe() = runTest {
        val api = FakeRequestsApi(ApiResult.Success(available()))
        api.discoverResult = ApiResult.Error(403, "profile_verification_required", "")
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository)
        store.refresh()

        repository.discover()

        assertTrue(store.isEnabled.value)
        assertEquals(1, api.statusCalls)
    }
}

private fun ownRequest(tmdbId: Int) = MediaRequest(
    id = "r$tmdbId",
    mediaType = RequestMediaType.Movie,
    tmdbId = tmdbId,
    title = "Movie $tmdbId",
    status = "pending",
    outcome = "active",
    createdAt = "2026-06-01T00:00:00Z",
    updatedAt = "2026-06-01T00:00:00Z",
)

private fun available(allowed: Boolean = true) =
    RequestsFeatureStatus(requestsEnabled = true, allowed = allowed, state = "available")

private class FakeRequestsApi(
    vararg statusResults: ApiResult<RequestsFeatureStatus>,
) : RequestsApi {
    private val statusResults = statusResults.toMutableList()
    var statusCalls = 0
    /** Answers the first status call when completed, ahead of [statusResults]. */
    var firstStatus: CompletableDeferred<ApiResult<RequestsFeatureStatus>>? = null
    var discoverResult: ApiResult<RequestsDiscoverResponse> = ApiResult.NetworkError(IllegalStateException("not used"))
    var discoverGate: CompletableDeferred<Unit>? = null
    var detailResult: ApiResult<RequestMediaDetail> = ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun status(): ApiResult<RequestsFeatureStatus> {
        statusCalls++
        firstStatus?.let { first ->
            firstStatus = null
            return first.await()
        }
        return if (statusResults.isNotEmpty()) {
            statusResults.removeAt(0)
        } else {
            ApiResult.NetworkError(IllegalStateException("no fake status result"))
        }
    }

    override suspend fun discover(): ApiResult<RequestsDiscoverResponse> {
        discoverGate?.await()
        return discoverResult
    }

    override suspend fun discoverSection(section: String, page: Int): ApiResult<RequestMediaPage> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun search(
        query: String,
        mediaType: String?,
        page: Int,
    ): ApiResult<RequestMediaPage> = ApiResult.NetworkError(IllegalStateException("not used"))

    var detailCalls = 0

    override suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> {
        detailCalls++
        return detailResult
    }

    override suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun mine(
        status: String?,
        outcome: String?,
        limit: Int?,
        offset: Int?,
    ): ApiResult<RequestsListResponse> = ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun get(id: String): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun cancel(id: String): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))
}
