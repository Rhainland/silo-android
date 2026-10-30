package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvSecondaryPillButton

/**
 * The box set row on a movie's page: the box set's title with a View box
 * set button, over its movies in release order. The movie on screen is
 * outlined and tagged, and the row starts scrolled to it.
 *
 * The row sits between the trailers and More Like This. Focus: entering the
 * row lands on the movie on screen the first time ([restoreFocusRequester]
 * at [restoreFocusIndex]); Up from a card reaches View box set, and Up again
 * moves to the rail above, or calls [onReturnToHero] when it is set.
 */
@Composable
internal fun TvBoxSetRowSection(
    boxSet: CatalogBoxSet,
    items: List<SectionItem>,
    currentContentId: String,
    viewBoxSetFocus: FocusRequester,
    onViewBoxSet: () -> Unit,
    onReturnToHero: (() -> Boolean)?,
    onMovieClick: (contentId: String) -> Unit,
    restoreFocusIndex: Int,
    restoreFocusRequester: FocusRequester?,
    restoreFocusRequest: Int,
    onItemFocused: ((SectionItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvDetailHorizontalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvDetailSectionHeader(title = boxSet.title, modifier = Modifier.weight(1f))
            TvSecondaryPillButton(
                icon = Icons.Outlined.VideoLibrary,
                title = "View box set",
                onClick = onViewBoxSet,
                focusRequester = viewBoxSetFocus,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) onReturnToHero?.invoke() ?: false else false
                },
            )
        }
        TvMediaRow(
            title = boxSet.title,
            showHeader = false,
            items = items,
            showProgress = true,
            onItemClick = onMovieClick,
            style = TvRowStyle.Poster,
            horizontalPadding = TvDetailHorizontalInset,
            rowTopPadding = 0.dp,
            upFocusRequester = viewBoxSetFocus,
            restoreFocusIndex = restoreFocusIndex,
            restoreFocusRequester = restoreFocusRequester,
            restoreFocusRequest = restoreFocusRequest,
            onItemFocused = onItemFocused,
            markedContentId = currentContentId,
            markedBadge = "THIS FILM",
        )
    }
}
