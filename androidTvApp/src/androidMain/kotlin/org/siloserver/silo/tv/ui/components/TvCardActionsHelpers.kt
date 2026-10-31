package org.siloserver.silo.tv.ui.components

import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.withWatched
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * TV-side counterpart to
 * [org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions].
 * Builds a [TvMediaCardActions] backed by [MediaActionsCoordinator] for grids
 * (catalog, library detail, search) whose ViewModels don't manage these
 * actions directly. Optimistic state is held in Compose; a failed watched
 * write rolls back and says so in a toast.
 */
@Composable
fun rememberTvBrowseItemCardActions(
    item: BrowseItem,
): Triple<TvMediaCardActions, MediaItemUserState, Double?> {
    val coordinator: MediaActionsCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    // Key on userState too (phone parity): when refreshed data re-emits with a
    // changed userState, the card must reflect new watched/favorite/watchlist
    // badges instead of keeping the stale snapshot.
    var state by remember(item.contentId, item.userState, item.positionSeconds) {
        mutableStateOf(item)
    }

    val actions = remember(item.contentId, item.userState, item.positionSeconds, coordinator, scope) {
        TvMediaCardActions(
            onSetWatched = { watched ->
                val writeIntent = coordinator.beginWatched(item.contentId, watched)
                val previous = state
                state = state.withWatched(watched)
                scope.launch {
                    val result = coordinator.performPersonalWrite(writeIntent)
                    if (result !is ApiResult.Success && coordinator.isCurrent(writeIntent)) {
                        state = previous
                        // The menu has already closed, so without this the
                        // rollback is the only sign that the write failed.
                        val action = if (watched) "watched" else "unwatched"
                        Toast.makeText(
                            context,
                            "Couldn't mark as $action. ${result.errorMessage("Try again.")}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onToggleFavorite = { favorite ->
                val intent = coordinator.memberships.begin(item.contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE, favorite)
                scope.launch { coordinator.memberships.perform(intent) }
            },
            onToggleWatchlist = { inWatchlist ->
                val intent = coordinator.memberships.begin(item.contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.WATCHLIST, inWatchlist)
                scope.launch { coordinator.memberships.perform(intent) }
            },
        )
    }

    val membershipActions by coordinator.memberships.actions.collectAsState()
    var displayed = state.userState ?: MediaItemUserState()
    membershipActions.values.filter { it.intent.key.itemId == item.contentId && it.baseline != null && coordinator.memberships.current(it.intent) }.forEach {
        displayed = if (it.intent.key.kind == org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE)
            displayed.copy(isFavorite = it.baseline!!.present) else displayed.copy(inWatchlist = it.baseline!!.present)
    }
    return Triple(actions, displayed, state.positionSeconds)
}
