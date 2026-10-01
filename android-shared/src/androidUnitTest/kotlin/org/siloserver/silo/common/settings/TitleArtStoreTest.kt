package org.siloserver.silo.common.settings

import org.siloserver.silo.domain.settings.TitleArtController
import org.siloserver.silo.domain.settings.TitleArtPreference
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleArtStoreTest {

    @Test
    fun `before any answer title surfaces keep logos`() {
        assertTrue(TitleArtState().showTitleArt)
        assertTrue(TitleArtState(TitleArtSupport.Unavailable).showTitleArt)
        // An older server's answer never turns logos off, whatever is stored.
        assertTrue(
            TitleArtState(TitleArtSupport.Unsupported, TitleArtPreference(showTitleArt = false)).showTitleArt,
        )
    }

    @Test
    fun `refresh adopts the device's effective value and source`() = runTest {
        val server = FakeTitleArtServer(profileValue = false)
        val store = storeFor(server)

        store.refresh()

        assertEquals(
            TitleArtState(TitleArtSupport.Supported, TitleArtPreference(showTitleArt = false, appliesToAllDevices = true)),
            store.state.value,
        )
        assertFalse(store.state.value.showTitleArt)
    }

    @Test
    fun `an older server hides the setting and is never written`() = runTest {
        val server = FakeTitleArtServer(
            capabilities = ApiResult.Success(SUPPORTED.copy(manifestRevision = 15)),
        )
        val store = storeFor(server)

        store.refresh()
        store.setShowTitleArt(false)
        store.setAppliesToAllDevices(true)
        runCurrent()

        assertEquals(TitleArtSupport.Unsupported, store.state.value.support)
        assertFalse(store.state.value.isSupported)
        assertTrue(store.state.value.showTitleArt)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the main switch writes this device and shows the change at once`() = runTest {
        val server = FakeTitleArtServer()
        server.putGate = CompletableDeferred()
        val store = storeFor(server)
        store.refresh()

        store.setShowTitleArt(false)
        // Optimistic: surfaces switch to text before the write lands.
        assertFalse(store.state.value.showTitleArt)
        assertTrue(store.isSaving.value)

        server.putGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("put profile_device false"), server.calls)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false), store.state.value.preference)
        assertFalse(store.isSaving.value)
    }

    @Test
    fun `while applied to all devices the main switch writes the profile`() = runTest {
        val server = FakeTitleArtServer(profileValue = true)
        val store = storeFor(server)
        store.refresh()

        store.setShowTitleArt(false)
        runCurrent()

        assertEquals(listOf("put profile false"), server.calls)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = true), store.state.value.preference)
    }

    @Test
    fun `apply to all devices carries the value on screen both ways`() = runTest {
        val server = FakeTitleArtServer(deviceValue = false)
        val store = storeFor(server)
        store.refresh()

        store.setAppliesToAllDevices(true)
        runCurrent()
        assertEquals(listOf("put profile false"), server.calls)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = true), store.state.value.preference)

        store.setAppliesToAllDevices(false)
        runCurrent()
        assertEquals(
            listOf("put profile false", "put profile_device false", "delete profile"),
            server.calls,
        )
        assertNull(server.profileValue)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false), store.state.value.preference)
    }

    @Test
    fun `a failed write restores the last confirmed state`() = runTest {
        val server = FakeTitleArtServer()
        val store = storeFor(server)
        store.refresh()
        server.failPuts = true

        store.setShowTitleArt(false)
        runCurrent()

        assertTrue(store.state.value.showTitleArt)
        assertEquals("Server error", store.lastError.value)
    }

    @Test
    fun `the cached answer paints before the network does`() = runTest {
        val cache = InMemoryTitleArtCache()
        storeFor(FakeTitleArtServer(deviceValue = false), cache).refresh()

        val offline = FakeTitleArtServer(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        val coldStart = storeFor(offline, cache)
        coldStart.hydrateIfNeeded()

        assertEquals(TitleArtSupport.Supported, coldStart.state.value.support)
        assertFalse(coldStart.state.value.showTitleArt)
    }

    @Test
    fun `a refresh during a pending write cannot reroute the next toggle`() = runTest {
        // Title art on, this device only.
        val server = FakeTitleArtServer(deviceValue = true)
        val store = storeFor(server)
        store.refresh()

        // Apply to all devices; hold its PUT profile=true in flight.
        server.putGate = CompletableDeferred()
        store.setAppliesToAllDevices(true)
        runCurrent()
        assertEquals(listOf("put profile true"), server.calls)

        // Settings reopens / the app foregrounds: the server still answers
        // with the pre-write value (device scope, not all devices).
        store.refresh()
        assertTrue(store.state.value.appliesToAllDevices)

        // Turning title art off must address the profile value, not this device.
        store.setShowTitleArt(false)
        server.putGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("put profile true", "put profile false"), server.calls)
        assertEquals(false, server.profileValue)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = true), store.state.value.preference)
    }

    @Test
    fun `a stalled hydration never holds back the next server`() = runTest {
        var server = "https://a.test"
        val stalled = CompletableDeferred<Unit>()
        val api = FakeTitleArtServer()
        api.capabilitiesFor = { if (server == "https://a.test") stalled.await() }
        val cache = InMemoryTitleArtCache().apply {
            write("https://a.test|profile-1", TitleArtState(TitleArtSupport.Supported, TitleArtPreference(showTitleArt = true)))
        }
        val identities = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val store = DefaultTitleArtStore.forTest(
            controller = TitleArtController(SettingsRepository(api)),
            scope = backgroundScope,
            getServerUrl = { server },
            cache = cache,
            identityChanges = identities,
        )
        runCurrent()

        // Server A: the identity collector and another caller both hydrate
        // against a capabilities request that never answers.
        identities.tryEmit(Unit)
        backgroundScope.launch { store.refresh() }
        runCurrent()
        assertEquals(TitleArtSupport.Supported, store.state.value.support)
        assertTrue(store.state.value.showTitleArt)

        // Switch to server B, where this device has title art off.
        server = "https://b.test"
        api.deviceValue = false
        identities.tryEmit(Unit)
        runCurrent()

        assertEquals(TitleArtSupport.Supported, store.state.value.support)
        assertFalse(store.state.value.showTitleArt)

        // A's answer arriving late must not repaint B.
        api.deviceValue = true
        stalled.complete(Unit)
        runCurrent()
        assertFalse(store.state.value.showTitleArt)
    }

    @Test
    fun `a write stalled on the previous server never holds back the next server's save`() = runTest {
        var server = "https://a.test"
        val api = FakeTitleArtServer()
        val identities = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val store = DefaultTitleArtStore.forTest(
            controller = TitleArtController(SettingsRepository(api)),
            scope = backgroundScope,
            getServerUrl = { server },
            identityChanges = identities,
        )
        runCurrent()
        store.refresh()

        // Server A: apply to all devices, with its PUT held on the wire.
        val heldOnA = CompletableDeferred<Unit>()
        api.putGate = heldOnA
        store.setAppliesToAllDevices(true)
        runCurrent()
        assertEquals(listOf("put profile true"), api.calls)

        // Switch to server B and let it hydrate.
        api.putGate = null
        server = "https://b.test"
        identities.tryEmit(Unit)
        runCurrent()
        assertTrue(store.state.value.isSupported)

        // B's change goes out while A's write is still held.
        store.setShowTitleArt(false)
        runCurrent()
        assertEquals(listOf("put profile true", "put profile_device false"), api.calls)
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false), store.state.value.preference)
        assertFalse(store.isSaving.value)

        // A's write finishing late leaves B's state alone.
        heldOnA.complete(Unit)
        runCurrent()
        assertEquals(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false), store.state.value.preference)
        assertFalse(store.isSaving.value)
    }

    @Test
    fun `one title art change stays on the profile it started on`() = runTest {
        var activeProfile = "profile-1"
        val server = FakeTitleArtServer(profileValue = true)
        val store = DefaultTitleArtStore.forTest(
            controller = TitleArtController(SettingsRepository(server)),
            scope = backgroundScope,
            getActiveProfileId = { activeProfile },
        )
        store.refresh()
        server.authorities.clear()
        // The profile switches while the device PUT is on the wire.
        server.afterPut = { activeProfile = "profile-2" }

        store.setAppliesToAllDevices(false)
        runCurrent()

        assertEquals(listOf("put profile_device true", "delete profile"), server.calls)
        assertEquals(
            listOf<Pair<String, String?>>(
                "put" to "profile-1",
                "delete" to "profile-1",
                "effective" to "profile-1",
            ),
            server.authorities,
        )
    }

    @Test
    fun `a change queued behind a failed write is dropped, not sent at the wrong scope`() = runTest {
        // Title art on, this device only.
        val server = FakeTitleArtServer(deviceValue = true)
        val store = storeFor(server)
        store.refresh()

        // Apply to all devices (held in flight), then turn title art off
        // while the first write is still pending.
        server.putGate = CompletableDeferred()
        store.setAppliesToAllDevices(true)
        store.setShowTitleArt(false)
        runCurrent()

        // The first PUT fails.
        server.failPuts = true
        server.putGate!!.complete(Unit)
        runCurrent()

        // The queued change was built on the failed one's unsaved state; it
        // must not create a profile-wide value.
        assertEquals(listOf("put profile true"), server.calls)
        assertNull(server.profileValue)
        assertEquals(TitleArtPreference(showTitleArt = true, appliesToAllDevices = false), store.state.value.preference)
        assertFalse(store.isSaving.value)
        assertEquals("Server error", store.lastError.value)

        // A new change after the failure goes through normally.
        server.failPuts = false
        store.setShowTitleArt(false)
        runCurrent()
        assertEquals(listOf("put profile true", "put profile_device false"), server.calls)
        assertFalse(store.state.value.showTitleArt)
    }

    @Test
    fun `a reset during the identity read never pairs the old profile with the new generation`() = runTest {
        var activeProfile = "profile-a"
        val identityRead = CompletableDeferred<Unit>()
        var holdNextIdentityRead = true
        val cache = InMemoryTitleArtCache().apply {
            // Profile A had title art off.
            write(
                "https://server.test|profile-a",
                TitleArtState(TitleArtSupport.Supported, TitleArtPreference(showTitleArt = false)),
            )
        }
        val server = FakeTitleArtServer(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        val store = DefaultTitleArtStore.forTest(
            controller = TitleArtController(SettingsRepository(server)),
            scope = backgroundScope,
            getActiveProfileId = {
                val profile = activeProfile
                if (holdNextIdentityRead) {
                    holdNextIdentityRead = false
                    identityRead.await()
                }
                profile
            },
            cache = cache,
        )

        // A refresh reads profile A's identity and stalls there.
        backgroundScope.launch { store.refresh() }
        runCurrent()

        // The profile switches to B (which has nothing cached) before it returns.
        activeProfile = "profile-b"
        store.clear()
        identityRead.complete(Unit)
        runCurrent()

        // Profile A's cached "off" must not be painted for profile B.
        assertTrue(store.state.value.showTitleArt)
        assertFalse(store.state.value.isSupported)
    }

    @Test
    fun `clear drops the previous profile's answer`() = runTest {
        val store = storeFor(FakeTitleArtServer(deviceValue = false))
        store.refresh()

        store.clear()

        assertEquals(TitleArtState(), store.state.value)
    }

    private fun TestScope.storeFor(
        server: FakeTitleArtServer,
        cache: TitleArtCache = InMemoryTitleArtCache(),
    ) = DefaultTitleArtStore.forTest(
        controller = TitleArtController(SettingsRepository(server)),
        scope = backgroundScope,
        cache = cache,
    )

    private companion object {
        val SUPPORTED = SettingsContractCapabilities(
            apiVersion = 1,
            manifestRevision = 16,
            supportsBatchedEffective = true,
        )
    }

    /** Stores `ui.title_art` per scope and resolves profile → profile_device → default. */
    private class FakeTitleArtServer(
        var capabilities: ApiResult<SettingsContractCapabilities> = ApiResult.Success(SUPPORTED),
        var profileValue: Boolean? = null,
        var deviceValue: Boolean? = null,
    ) : SettingsApi(
        org.siloserver.silo.network.apiv2.SettingsV2Api(
            HttpClient(),
            org.siloserver.silo.network.TokenManagerImpl(),
            org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
        ),
    ) {
        val calls = mutableListOf<String>()
        var failPuts = false
        var putGate: CompletableDeferred<Unit>? = null

        /** The profile each request was pinned to, in order. */
        val authorities = mutableListOf<Pair<String, String?>>()
        var afterPut: () -> Unit = {}

        /** Runs before each capabilities answer; a test can suspend it. */
        var capabilitiesFor: suspend () -> Unit = {}

        override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> {
            capabilitiesFor()
            return capabilities
        }

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<EffectiveSettingValuesResponse> {
            authorities += "effective" to authority?.profileId
            val (value, source) = profileValue?.let { it to "profile" }
                ?: deviceValue?.let { it to "profile_device" }
                ?: (true to EffectiveSettingValue.SOURCE_DEFAULT)
            return ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(
                        EffectiveSettingValue(SettingKeys.UI_TITLE_ART, JsonPrimitive(value), source),
                    ),
                ),
            )
        }

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            profileId: String?,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            val bool = (value as JsonPrimitive).content.toBooleanStrict()
            calls += "put ${scope.scope.wire} $bool"
            authorities += "put" to authority?.profileId
            putGate?.await()
            afterPut()
            if (failPuts) return ApiResult.Error(500, "boom", "Server error")
            when (scope.scope) {
                SettingScope.PROFILE -> profileValue = bool
                SettingScope.PROFILE_DEVICE -> deviceValue = bool
                else -> Unit
            }
            return ApiResult.Success(StoredSettingValue(key = key, scope = scope.scope.wire))
        }

        override suspend fun deleteValue(
            key: String,
            scope: SettingScopeIdentity,
            profileId: String?,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<Unit> {
            calls += "delete ${scope.scope.wire}"
            authorities += "delete" to authority?.profileId
            if (scope.scope == SettingScope.PROFILE) {
                val existed = profileValue != null
                profileValue = null
                if (!existed) return ApiResult.Error(404, "not_found", "Not Found")
            }
            return ApiResult.Success(Unit)
        }
    }
}
