package com.example.everytalk.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.everytalk.data.DataClass.WebSearchResult

@Composable
fun WebSourcesDialog(
    sources: List<WebSearchResult>,
    onDismissRequest: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceDim
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
            ) {
                Text(
                    text = "参考来源",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sources, key = { it.href }) { source ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${source.index}. ${source.title}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        style = SpanStyle(
                                            color = Color.Black,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    ) {
                                        append(source.href)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    try {
                                        uriHandler.openUri(source.href)
                                    } catch (e: Exception) {
                                    }
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = source.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (sources.last() != source) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = Color.Black.copy(alpha = 0.12f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White
                    )
                ) {
                    Text("关闭")
                }
            }
        }
    }
}