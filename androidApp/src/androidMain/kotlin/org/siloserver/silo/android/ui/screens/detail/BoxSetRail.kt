package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.boxSetResumePosition
import org.siloserver.silo.model.catalog.rowCaption
import org.siloserver.silo.overlays.OverlayDataExtractor

/**
 * The box set row on a movie's page: the box set's title and watched count,
 * a link to the box set page, and its movies in release order with the movie
 * being viewed outlined and tagged. Starts scrolled to that movie.
 */
@Composable
fun BoxSetRail(
    boxSet: CatalogBoxSetDetail,
    currentContentId: String,
    onOpenBoxSet: (CatalogBoxSet) -> Unit,
    onMovieClick: (contentId: String, libraryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (boxSet.items.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SafePadding, end = SafePadding - 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = boxSet.boxSet.title,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailPrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = boxSet.boxSet.rowCaption(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SiloSecondaryText,
                    maxLines = 1,
                )
            }
            TextButton(onClick = { onOpenBoxSet(boxSet.boxSet) }) {
                Text("View box set", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DetailPrimaryText)
            }
        }
        val currentIndex = boxSet.items.indexOfFirst { it.contentId == currentContentId }
        // The previous film peeks in at the start, so the row reads as a sequence.
        val rowState = remember(boxSet.boxSet.id, currentContentId) {
            androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
        }
        DeferImagePresentationWhileScrolling(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = SafePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(boxSet.items, key = { it.contentId }, contentType = { "box-set-movie" }) { item ->
                    BoxSetRailCard(
                        item = item,
                        isCurrent = item.contentId == currentContentId,
                        onClick = { onMovieClick(item.contentId, boxSet.boxSet.libraryId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxSetRailCard(item: BrowseItem, isCurrent: Boolean, onClick: () -> Unit) {
    MediaCard(
        title = item.title,
        posterUrl = item.posterUrl,
        posterThumbhash = item.posterThumbhash,
        detailBackdropUrl = item.backdropUrl,
        detailBackdropThumbhash = item.backdropThumbhash,
        year = item.year?.takeIf { it > 0 },
        type = item.type,
        userState = item.userState,
        progress = boxSetMovieProgress(item),
        // The movie on screen opens nothing new; tapping it is a no-op.
        onClick = if (isCurrent) ({}) else onClick,
        overlay = OverlayDataExtractor.fromBrowseItem(item),
        sharedContentId = item.contentId.takeUnless { isCurrent },
        // Bottom corner: the top corners hold the quality overlays and watched check.
        cornerLabel = "THIS FILM".takeIf { isCurrent },
        highlighted = isCurrent,
    )
}

/** An unfinished movie's watched share, or null to draw no progress bar. */
internal fun boxSetMovieProgress(item: BrowseItem): Float? {
    val position = item.boxSetResumePosition() ?: return null
    val duration = item.durationSeconds?.takeIf { it > 0 } ?: return null
    return (position / duration).toFloat()
}
