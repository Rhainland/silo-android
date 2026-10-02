package org.siloserver.silo.tv.ui.screens.detail

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DefaultMetadataAiApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.SettingsV2Api
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.MetadataAiRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An access change refreshes an open TV detail page. A refusal replaces the
 * page with the error; a later change must bring back a title the viewer
 * regains, and must not bring back the durable cached copy of one the server
 * still refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvItemDetailAccessChangeTest {

    @Test
    fun `an access change reloads a refused title once access returns`() = runDetailTest {
        var available = true
        val viewModel = createViewModel(available = { available })
        viewModel.loadAll()
        advanceUntilIdle()
        assertEquals(CONTENT_ID, viewModel.uiState.value.detail?.contentId)

        available = false
        viewModel.refreshAfterAccessChange()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.detail)

        available = true
        viewModel.refreshAfterAccessChange()
        advanceUntilIdle()

        assertEquals(CONTENT_ID, viewModel.uiState.value.detail?.contentId)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `an access change on a still refused title does not repaint its cached detail`() = runDetailTest {
        var available = true
        val viewModel = createViewModel(
            available = { available },
            cached = ItemDetail(contentId = CONTENT_ID, type = "movie", title = "Cached"),
        )
        viewModel.loadAll()
        advanceUntilIdle()

        available = false
        viewModel.refreshAfterAccessChange()
        advanceUntilIdle()
        // A second, unrelated access change runs the full load, which paints
        // the durable cached copy before the live request is refused again.
        viewModel.refreshAfterAccessChange()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.detail)
        assertTrue(viewModel.uiState.value.error != null)
    }

    // ------------------------------------------------------------------

    private val createdViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun runDetailTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            // Cancel viewModelScope work and let the cancellations finish
            // while Main is still the test dispatcher.
            createdViewModels.forEach { it.viewModelScope.cancel() }
            createdViewModels.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.createViewModel(
        available: () -> Boolean,
        cached: ItemDetail? = null,
    ): TvItemDetailViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        when {
                            request.url.encodedPath != "/api/v2/catalog/items/$CONTENT_ID" -> respond(
                                """{"type":"about:blank","title":"not_found","status":404,"detail":"not found"}""",
                                HttpStatusCode.NotFound,
                                PROBLEM_HEADERS,
                            )
                            available() -> respond(
                                """{"content_id":"$CONTENT_ID","type":"movie","title":"Movie","cast":[],"crew":[],"versions":[],"subtitles":[]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                            else -> respond(
                                """{"type":"about:blank","title":"not_found","status":404,"detail":"Item not found"}""",
                                HttpStatusCode.NotFound,
                                PROBLEM_HEADERS,
                            )
                        }
                    }
                },
            ),
        ) { install(ContentNegotiation) { json(SiloJson) } }
        val tokenManager = TokenManagerImpl()
        return TvItemDetailViewModel(
            catalogRepository = CatalogRepository(
                CatalogApi(client),
                catalogCache = object : CatalogCachePort {
                    override suspend fun getCachedItemDetail(contentId: String): ItemDetail? = cached
                },
                requestDispatcher = dispatcher,
            ),
            personalDataRepository = PersonalDataRepository(PersonalDataApi(client)),
            playerSettingsStore = FakePlayerSettingsStore(),
            profileRepository = ProfileRepository(ProfileApi(client, ApiV2Gate.Unrestricted), tokenManager),
            profileSettings = ProfileSettingsController(
                SettingsRepository(SettingsApi(SettingsV2Api(client, tokenManager, ApiV2Gate.Unrestricted))),
            ),
            metadataAiRepository = MetadataAiRepository(DefaultMetadataAiApi(client, gate = ApiV2Gate.Unrestricted)),
            contentId = CONTENT_ID,
            tokenManager = tokenManager,
            identityTransitions = DefaultIdentityTransitionBarrier(),
        ).also { createdViewModels += it }
    }

    private companion object {
        const val CONTENT_ID = "movie-1"
        val PROBLEM_HEADERS = headersOf(HttpHeaders.ContentType, "application/problem+json")
    }
}
