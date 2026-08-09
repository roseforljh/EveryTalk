package com.android.everytalk.ui.screens.MainScreen.search

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MILLIS = 120L
private const val MAX_SNIPPET_CHARS = 168
private const val MAX_VISIBLE_SNIPPETS_PER_CONVERSATION = 24
private val SearchCardShape = RoundedCornerShape(22.dp)
private val SearchInputShape = RoundedCornerShape(28.dp)
private val SearchSnippetShape = RoundedCornerShape(16.dp)

internal data class ConversationSearchSource(
    val originalIndex: Int,
    val stableId: String,
    val title: String,
    val messages: List<Message>,
)

internal data class ConversationSearchSnippet(
    val source: ConversationSearchSourceType,
    val text: String,
    val occurrenceCount: Int,
)

internal enum class ConversationSearchSourceType(@StringRes val labelRes: Int) {
    TITLE(R.string.conversation_search_source_title),
    USER(R.string.conversation_search_source_user),
    AI(R.string.conversation_search_source_ai),
    SYSTEM(R.string.conversation_search_source_system),
    TOOL(R.string.conversation_search_source_tool),
}

internal data class ConversationSearchResult(
    val originalIndex: Int,
    val stableId: String,
    val title: String,
    val snippets: List<ConversationSearchSnippet>,
    val totalOccurrences: Int,
    val isTruncated: Boolean,
)

private data class ConversationSearchUiState(
    val query: String = "",
    val results: List<ConversationSearchResult> = emptyList(),
    val isSearching: Boolean = false,
)

internal data class TextSearchSnippet(
    val text: String,
    val occurrenceCount: Int,
)

@Composable
internal fun ConversationSearchContent(
    query: String,
    conversations: List<List<Message>>,
    getConversationTitle: (Int) -> String,
    onQueryChange: (String) -> Unit,
    onConversationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources = remember(conversations) {
        conversations.mapIndexed { index, conversation ->
            ConversationSearchSource(
                originalIndex = index,
                stableId = ConversationNameHelper.resolveStableId(conversation) ?: "conversation_$index",
                title = getConversationTitle(index),
                messages = conversation,
            )
        }
    }
    var searchState by remember { mutableStateOf(ConversationSearchUiState()) }
    val expandedResults = remember(query) { mutableStateMapOf<String, Boolean>() }
    val bottomInset = WindowInsets.navigationBars
        .union(WindowInsets.ime)
        .asPaddingValues()
        .calculateBottomPadding()

    LaunchedEffect(query, sources) {
        val normalizedQuery = normalizeSearchText(query).trim()
        if (normalizedQuery.isEmpty()) {
            searchState = ConversationSearchUiState()
            return@LaunchedEffect
        }
        searchState = ConversationSearchUiState(query = normalizedQuery, isSearching = true)
        delay(SEARCH_DEBOUNCE_MILLIS)
        val results = withContext(Dispatchers.Default) {
            buildConversationSearchResults(sources, normalizedQuery)
        }
        searchState = ConversationSearchUiState(
            query = normalizedQuery,
            results = results,
            isSearching = false,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            searchState.isSearching -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 116.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            }

            searchState.query.isNotEmpty() && searchState.results.isEmpty() -> {
                Text(
                    text = stringResource(R.string.conversation_search_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 116.dp),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 96.dp,
                        end = 16.dp,
                        bottom = 96.dp + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = searchState.results,
                        key = ConversationSearchResult::stableId,
                    ) { result ->
                        val expanded = expandedResults[result.stableId] == true
                        ConversationSearchResultCard(
                            result = result,
                            query = searchState.query,
                            expanded = expanded,
                            onToggle = { expandedResults[result.stableId] = !expanded },
                            onConversationClick = { onConversationClick(result.originalIndex) },
                        )
                    }
                }
            }
        }

        ConversationSearchInput(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.ime)
                        .only(WindowInsetsSides.Bottom),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ConversationSearchResultCard(
    result: ConversationSearchResult,
    query: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onConversationClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "conversationSearchChevron",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180)),
        shape = SearchCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column {
            Surface(
                onClick = onToggle,
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 15.dp, end = 14.dp, bottom = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rememberSelectedSearchText(result.title, query),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.conversation_search_match_count,
                                result.totalOccurrences,
                                result.totalOccurrences,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_chevron_right),
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.conversation_search_collapse
                            } else {
                                R.string.conversation_search_expand
                            },
                        ),
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(animationSpec = tween(120)),
            ) {
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    result.snippets.forEach { snippet ->
                        ConversationSearchSnippetCard(
                            snippet = snippet,
                            query = query,
                            onClick = onConversationClick,
                        )
                    }
                    if (result.isTruncated) {
                        Text(
                            text = stringResource(
                                R.string.conversation_search_truncated,
                                MAX_VISIBLE_SNIPPETS_PER_CONVERSATION,
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationSearchSnippetCard(
    snippet: ConversationSearchSnippet,
    query: String,
    onClick: () -> Unit,
) {
    val sourceLabel = stringResource(snippet.source.labelRes)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = SearchSnippetShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = if (snippet.occurrenceCount > 1) {
                    "$sourceLabel · ${pluralStringResource(
                        R.plurals.conversation_search_match_count,
                        snippet.occurrenceCount,
                        snippet.occurrenceCount,
                    )}"
                } else {
                    sourceLabel
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = rememberSelectedSearchText(snippet.text, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(Unit) {
        delay(140)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = SearchInputShape,
                spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.20f),
                ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.14f),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                shape = SearchInputShape,
            ),
        shape = SearchInputShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.conversation_search_input_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun rememberSelectedSearchText(text: String, query: String): AnnotatedString {
    val highlightBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val highlightText = MaterialTheme.colorScheme.primary
    return remember(text, query, highlightBackground, highlightText) {
        buildHighlightedSearchText(
            text = text,
            query = query,
            highlightStyle = SpanStyle(
                color = highlightText,
                background = highlightBackground,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

internal fun buildHighlightedSearchText(
    text: String,
    query: String,
    highlightStyle: SpanStyle,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val matchIndex = text.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (matchIndex < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, matchIndex))
            val end = matchIndex + query.length
            pushStyle(highlightStyle)
            append(text.substring(matchIndex, end))
            pop()
            cursor = end
        }
    }
}

internal fun buildConversationSearchResults(
    sources: List<ConversationSearchSource>,
    query: String,
): List<ConversationSearchResult> {
    val normalizedQuery = normalizeSearchText(query).trim()
    if (normalizedQuery.isEmpty()) return emptyList()

    return sources.mapNotNull { source ->
        val snippets = mutableListOf<ConversationSearchSnippet>()
        var totalOccurrences = 0
        var visibleOccurrences = 0

        val normalizedTitle = normalizeSearchText(source.title)
        val titleOccurrences = findSearchOccurrences(normalizedTitle, normalizedQuery).size
        if (titleOccurrences > 0) {
            totalOccurrences += titleOccurrences
            snippets += ConversationSearchSnippet(
                source = ConversationSearchSourceType.TITLE,
                text = normalizedTitle,
                occurrenceCount = titleOccurrences,
            )
            visibleOccurrences += titleOccurrences
        }

        source.messages.forEach { message ->
            if (message.sender == Sender.System && message.isPlaceholderName) return@forEach
            val textSnippets = buildSearchSnippets(message.text, normalizedQuery)
            totalOccurrences += textSnippets.sumOf(TextSearchSnippet::occurrenceCount)
            textSnippets.forEach { snippet ->
                if (snippets.size < MAX_VISIBLE_SNIPPETS_PER_CONVERSATION) {
                    snippets += ConversationSearchSnippet(
                        source = message.sender.searchSourceType(),
                        text = snippet.text,
                        occurrenceCount = snippet.occurrenceCount,
                    )
                    visibleOccurrences += snippet.occurrenceCount
                }
            }
        }

        if (totalOccurrences == 0) {
            null
        } else {
            ConversationSearchResult(
                originalIndex = source.originalIndex,
                stableId = source.stableId,
                title = source.title,
                snippets = snippets.take(MAX_VISIBLE_SNIPPETS_PER_CONVERSATION),
                totalOccurrences = totalOccurrences,
                isTruncated = visibleOccurrences < totalOccurrences,
            )
        }
    }
}

internal fun buildSearchSnippets(
    text: String,
    query: String,
    maxSnippetChars: Int = MAX_SNIPPET_CHARS,
): List<TextSearchSnippet> {
    val normalizedText = normalizeSearchText(text)
    val normalizedQuery = normalizeSearchText(query).trim()
    if (normalizedText.isEmpty() || normalizedQuery.isEmpty()) return emptyList()

    val positions = findSearchOccurrences(normalizedText, normalizedQuery)
    if (positions.isEmpty()) return emptyList()

    val bodyLimit = maxOf(maxSnippetChars, normalizedQuery.length)
    val contextBefore = (bodyLimit - normalizedQuery.length) / 2
    val snippets = mutableListOf<TextSearchSnippet>()
    var positionIndex = 0

    while (positionIndex < positions.size) {
        val firstMatch = positions[positionIndex]
        var start = (firstMatch - contextBefore).coerceAtLeast(0)
        var end = (start + bodyLimit).coerceAtMost(normalizedText.length)
        if (end - start < bodyLimit) start = (end - bodyLimit).coerceAtLeast(0)

        var nextPositionIndex = positionIndex
        while (
            nextPositionIndex < positions.size &&
            positions[nextPositionIndex] + normalizedQuery.length <= end
        ) {
            nextPositionIndex++
        }
        if (nextPositionIndex == positionIndex) nextPositionIndex++

        val leadingEllipsis = start > 0
        val trailingEllipsis = end < normalizedText.length
        snippets += TextSearchSnippet(
            text = buildString {
                if (leadingEllipsis) append('…')
                append(normalizedText.substring(start, end))
                if (trailingEllipsis) append('…')
            },
            occurrenceCount = nextPositionIndex - positionIndex,
        )
        positionIndex = nextPositionIndex
    }
    return snippets
}

private fun findSearchOccurrences(text: String, query: String): List<Int> {
    if (text.isEmpty() || query.isEmpty()) return emptyList()
    val positions = mutableListOf<Int>()
    var searchStart = 0
    while (searchStart <= text.length - query.length) {
        val matchIndex = text.indexOf(query, startIndex = searchStart, ignoreCase = true)
        if (matchIndex < 0) break
        positions += matchIndex
        searchStart = matchIndex + query.length
    }
    return positions
}

private fun normalizeSearchText(text: String): String = buildString(text.length) {
    var previousWasWhitespace = false
    text.forEach { character ->
        if (character.isWhitespace()) {
            if (!previousWasWhitespace && isNotEmpty()) append(' ')
            previousWasWhitespace = true
        } else {
            append(character)
            previousWasWhitespace = false
        }
    }
    if (lastOrNull() == ' ') deleteCharAt(lastIndex)
}

private fun Sender.searchSourceType(): ConversationSearchSourceType = when (this) {
    Sender.User -> ConversationSearchSourceType.USER
    Sender.AI -> ConversationSearchSourceType.AI
    Sender.System -> ConversationSearchSourceType.SYSTEM
    Sender.Tool -> ConversationSearchSourceType.TOOL
}
