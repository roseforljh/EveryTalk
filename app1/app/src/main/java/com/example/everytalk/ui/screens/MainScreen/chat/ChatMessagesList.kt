package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

@Composable
fun ChatMessagesList(
    messageItems: List<Pair<String, @Composable () -> Unit>>,
    listState: LazyListState,
    nestedScrollConnection: NestedScrollConnection
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 8.dp),
        state = listState,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = 16.dp
        )
    ) {
        items(
            items = messageItems,
            key = { (id, _) -> id }
        ) { (_, content) ->
            content()
        }
        item(key = "chat_screen_footer_spacer_in_list") {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}