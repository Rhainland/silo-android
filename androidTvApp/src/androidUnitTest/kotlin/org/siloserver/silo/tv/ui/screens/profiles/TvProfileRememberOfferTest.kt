package org.siloserver.silo.tv.ui.screens.profiles

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.data.preferences.InMemoryPrefs
import org.siloserver.silo.tv.data.preferences.ProfileLaunchBehavior
import org.siloserver.silo.tv.data.preferences.TvProfileLaunchPreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvProfileRememberOfferTest {
    private val profile = Profile(id = "profile-a", name = "Ana")

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an offer for a profile cleared while it was open neither saves nor opens Home`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val repository = OneProfileRepository(barrier, profile)
        val preferences = TvProfileLaunchPreferences(InMemoryPrefs()).apply {
            settleFirstLaunch(hasRememberedProfile = false)
        }
        val viewModel = TvProfileSelectionViewModel(
            repository,
            consumeSkipsSingleProfile = { false },
            launchPreferences = preferences,
        )
        advanceUntilIdle()

        viewModel.onProfileSelected(profile)
        advanceUntilIdle()
        assertEquals(profile, viewModel.uiState.value.rememberOffer)

        // What profile-verification recovery does while the dialog is up.
        repository.clearProfile()
        viewModel.answerRememberOffer(remember = true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedProfileId)
        assertNull(viewModel.uiState.value.rememberOffer)
        assertTrue(preferences.isRememberOfferPending)
        assertEquals(ProfileLaunchBehavior.EveryTime, preferences.state.value.behavior)
    }

    @Test
    fun `answering the offer for the active profile saves it and opens Home`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val repository = OneProfileRepository(barrier, profile)
        val preferences = TvProfileLaunchPreferences(InMemoryPrefs()).apply {
            settleFirstLaunch(hasRememberedProfile = false)
        }
        val viewModel = TvProfileSelectionViewModel(
            repository,
            consumeSkipsSingleProfile = { false },
            launchPreferences = preferences,
        )
        advanceUntilIdle()

        viewModel.onProfileSelected(profile)
        viewModel.answerRememberOffer(remember = true)
        advanceUntilIdle()

        assertEquals(profile.id, viewModel.uiState.value.selectedProfileId)
        assertEquals(ProfileLaunchBehavior.Automatic, preferences.state.value.behavior)
    }
}

private class OneProfileRepository(
    barrier: DefaultIdentityTransitionBarrier,
    private val profile: Profile,
) : ProfileRepository(
    profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
    tokenManager = TokenManagerImpl(barrier),
    identityTransitions = barrier,
) {
    override suspend fun listProfiles(): ApiResult<List<Profile>> = ApiResult.Success(listOf(profile))
}
