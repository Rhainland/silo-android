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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.rowCaption
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvSecondaryPillButton

/**
 * The box set row on a movie's page: the box set's title and watched count
 * with a View box set button, over its movies in release order. The movie on
 * screen is outlined and tagged, and the row starts scrolled to it.
 *
 * Focus: Down from the hero enters the row, landing on the movie on screen
 * the first time ([restoreFocusRequester] at [restoreFocusIndex]); Up from a
 * card reaches View box set, and Up again returns to the hero.
 */
@Composable
internal fun TvBoxSetRowSection(
    boxSet: CatalogBoxSet,
    items: List<SectionItem>,
    currentContentId: String,
    viewBoxSetFocus: FocusRequester,
    onViewBoxSet: () -> Unit,
    onReturnToHero: () -> Boolean,
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
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TvDetailSectionHeader(title = boxSet.title)
                Text(
                    text = boxSet.rowCaption(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 18.sp),
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                )
            }
            TvSecondaryPillButton(
                icon = Icons.Outlined.VideoLibrary,
                title = "View box set",
                onClick = onViewBoxSet,
                focusRequester = viewBoxSetFocus,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) onReturnToHero() else false
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
