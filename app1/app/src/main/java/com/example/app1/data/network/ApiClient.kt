package com.example.app1.data.network

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.* // For ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.IOException // Use java.io.IOException

// Import project-specific models
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.OpenAiChoice
import com.example.app1.data.models.OpenAiDelta

// Use kotlinx.coroutines.CancellationException explicitly to avoid ambiguity
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

object ApiClient {

    // JSON parser configuration for Ktor client and backend stream parsing
    private val jsonParser = Json {
        ignoreUnknownKeys = true // Tolerate extra fields from backend/API
        isLenient = true       // Allow slightly malformed JSON if possible (use with caution)
        encodeDefaults = true  // Ensure default values are sent in requests
    }

    // Ktor HTTP Client setup
    private val client = HttpClient(Android) { // Using Android engine
        // Content Negotiation for automatic JSON serialization/deserialization
        install(ContentNegotiation) {
            json(jsonParser)
        }
        // Timeouts configuration
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000 // 5 minutes for the entire request
            connectTimeoutMillis = 60_000  // 1 minute to establish connection
            socketTimeoutMillis = 300_000  // 5 minutes for data transfer inactivity
        }
        // Optional: Logging (useful for debugging)
        // install(Logging) {
        //     logger = Logger.DEFAULT
        //     level = LogLevel.ALL // Log headers and bodies (be careful with sensitive data)
        // }
        // Optional: Default request configuration (e.g., common headers)
        // defaultRequest {
        //     header("X-App-Version", "1.0.0")
        // }
    }

    // Data class to represent the structure of JSON chunks expected from *your* backend proxy
    @Serializable
    data class BackendStreamChunk(
        val type: String? = null,        // e.g., "content", "reasoning", "status", "error"
        val text: String? = null,        // The actual text content for "content" or "reasoning" types
        @SerialName("finish_reason")     // Matches the field name from backend JSON
        val finishReason: String? = null // e.g., "stop", "length", "error", "cancelled"
        // Add other fields your backend might send per chunk, e.g.:
        // val message_id: String? = null
        // val sequence_id: Int? = null
        // val error_code: String? = null
    )

    /**
     * Streams chat responses from the backend proxy.
     * Handles the HTTP request and parses the Server-Sent Events (SSE) stream line by line.
     * Maps the backend's chunk format to the `OpenAiStreamChunk` format expected by the ViewModel.
     *
     * @param request The ChatRequest object containing messages, config, etc.
     * @return A Flow emitting `OpenAiStreamChunk` objects as they are received and parsed.
     *         The flow completes normally when the stream ends, or emits an error (IOException, CoroutineCancellationException).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // For channelFlow
    fun streamChatResponse(request: ChatRequest): Flow<OpenAiStreamChunk> = channelFlow {
        // TODO: Replace with your actual backend proxy URL
        val backendProxyUrl = "https://backdaitalk-production.up.railway.app/chat" // Example Ngrok URL
        var response: HttpResponse? = null // Hold response for potential error reporting

        try {
            println("ApiClient: Preparing POST request to $backendProxyUrl")
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json)
                setBody(request) // Send the ChatRequest object as JSON body
                // Include API key in a header (adjust header name if needed)
                header("X-API-Key", request.apiKey ?: "")
                // Add other headers if your proxy requires them
                // header("Authorization", "Bearer ${request.apiKey}")
                accept(ContentType.Text.EventStream) // Explicitly accept SSE stream
                timeout { // Can override default timeouts per request
                    requestTimeoutMillis = 310_000 // Slightly longer for this specific call
                }

            }.execute { receivedResponse -> // Execute the request and process the response
                response = receivedResponse // Store response for potential error access
                println("ApiClient: Received response status: ${response?.status}")

                // --- Check Response Status ---
                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(No error body)"
                    } catch (e: Exception) {
                        "(Failed to read error body: ${e.message})"
                    }
                    println("ApiClient: Proxy error ${response?.status}. Body: $errorBody")
                    // Close the flow with an error
                    close(IOException("Proxy error: ${response?.status?.value} - $errorBody"))
                    return@execute // Stop processing
                }

                // --- Process Successful Stream ---
                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    println("ApiClient: Error - Response body channel is null.")
                    close(IOException("Failed to get response body channel."))
                    return@execute
                }
                println("ApiClient: Starting to read stream channel...")

                try {
                    // Loop while the flow collector is active and the channel has data
                    while (isActive && !channel.isClosedForRead) {
                        // Read lines until EOF or channel closed
                        val line = channel.readUTF8Line()

                        if (line == null) {
                            println("ApiClient: Stream channel EOF reached.")
                            break // End of stream
                        }

                        val messageString = line.trim()
                        // println("ApiClient: Raw line: '$messageString'") // Debug raw lines

                        // Process non-empty lines (ignore potential keep-alive blank lines)
                        if (messageString.isNotEmpty()) {
                            // SSE format usually prefixes data lines with "data: "
                            val jsonData = if (messageString.startsWith("data:")) {
                                messageString.substring(5).trim()
                            } else {
                                // If not starting with "data:", treat the whole line as JSON?
                                // Or log a warning, depending on your backend's SSE format.
                                println("ApiClient: WARN - Received line without 'data:' prefix: '$messageString'. Attempting to parse directly.")
                                messageString
                            }

                            if (jsonData.isNotEmpty()) {
                                try {
                                    // Parse the JSON data using the BackendStreamChunk structure
                                    val backendChunk = jsonParser.decodeFromString(
                                        BackendStreamChunk.serializer(),
                                        jsonData
                                    )

                                    // --- Map Backend Chunk to OpenAiStreamChunk ---
                                    val openAiChunk = OpenAiStreamChunk(
                                        // Assuming one choice per chunk from backend for simplicity
                                        choices = listOf(
                                            OpenAiChoice(
                                                index = 0,
                                                delta = OpenAiDelta(
                                                    // Map based on backend 'type' field
                                                    content = if (backendChunk.type == "content") backendChunk.text else null,
                                                    reasoningContent = if (backendChunk.type == "reasoning") backendChunk.text else null
                                                    // Add other delta fields if needed (role, tool_calls)
                                                ),
                                                finishReason = backendChunk.finishReason // Pass through finish reason
                                            )
                                        )
                                        // Add other top-level OpenAiStreamChunk fields if needed (id, created, model)
                                    )
                                    // --- End Mapping ---

                                    // Send the mapped chunk to the flow collector
                                    // Use trySend to handle backpressure and check if downstream is still listening
                                    val sendResult = trySend(openAiChunk)
                                    if (!sendResult.isSuccess) {
                                        println("ApiClient: Downstream collector closed or failed. Stopping stream reading. Reason: ${sendResult.toString()}")
                                        // Optionally, explicitly close the channel/response here? Ktor might handle it.
                                        channel.cancel(CoroutineCancellationException("Downstream closed"))
                                        return@execute // Stop processing
                                    }
                                    // else {
                                    //    println("ApiClient: Sent chunk: Type=${backendChunk.type}, FinishReason=${backendChunk.finishReason}")
                                    // }

                                } catch (e: SerializationException) {
                                    // Handle JSON parsing errors for a specific chunk
                                    println("ApiClient: ERROR - Failed to parse stream JSON chunk. Raw JSON: '$jsonData'. Error: ${e.message}")
                                    // Close the flow with an error, including the problematic data
                                    close(
                                        IOException(
                                            "Failed to parse stream JSON chunk: '${
                                                jsonData.take(
                                                    100
                                                )
                                            }...'. Error: ${e.message}", e
                                        )
                                    )
                                    return@execute // Stop processing
                                } catch (e: Exception) {
                                    // Catch unexpected errors during chunk processing
                                    println("ApiClient: ERROR - Unexpected error processing chunk: '$jsonData'. Error: ${e.message}")
                                    e.printStackTrace()
                                    close(
                                        IOException(
                                            "Unexpected error processing chunk: ${e.message}",
                                            e
                                        )
                                    )
                                    return@execute
                                }
                            }
                        }
                    } // End while loop (reading lines)
                    println("ApiClient: Finished reading stream channel (isActive=$isActive, isClosedForRead=${channel.isClosedForRead}).")

                } catch (e: IOException) {
                    // Handle network errors during stream reading
                    println("ApiClient: ERROR - Network error during stream reading: ${e.message}")
                    if (!isClosedForSend) { // Check if flow already closed
                        close(
                            IOException(
                                "Network Error: Stream reading interrupted. ${e.message}",
                                e
                            )
                        )
                    }
                } catch (e: CoroutineCancellationException) {
                    // Catch cancellation signal (likely from downstream collector or timeout)
                    println("ApiClient: Stream reading cancelled. Reason: ${e.message}")
                    // Re-throw cancellation so channelFlow handles it correctly
                    throw e
                } catch (e: Exception) {
                    // Catch any other unexpected errors during reading loop
                    println("ApiClient: ERROR - Unexpected error during stream reading: ${e.message}")
                    e.printStackTrace()
                    if (!isClosedForSend) {
                        close(IOException("Unexpected stream reading error: ${e.message}", e))
                    }
                } finally {
                    println("ApiClient: Exiting response execution block.")
                    // Ensure resources are released? Ktor's execute block should handle response closure.
                }
            } // End execute block
        } catch (e: CoroutineCancellationException) {
            // Catch cancellation during request setup/connection phase
            println("ApiClient: Request setup cancelled. Reason: ${e.message}")
            // Re-throw cancellation for proper handling by caller
            throw e
        } catch (e: IOException) {
            // Catch network errors during request setup/connection (e.g., DNS resolution, connection refused)
            println("ApiClient: ERROR - Network error during request setup/execution: ${e.message}")
            if (!isClosedForSend) {
                close(IOException("Network Setup/Execution Error: ${e.message}", e))
            }
        } catch (e: Exception) {
            // Catch other unexpected errors during request setup/execution
            println("ApiClient: ERROR - Unexpected error during request setup/execution: ${e.message}")
            e.printStackTrace()
            if (!isClosedForSend) {
                // Include response status if available
                val statusInfo = response?.status?.let { " (Status: ${it.value})" } ?: ""
                close(IOException("Unexpected API Client Error$statusInfo: ${e.message}", e))
            }
        } finally {
            println("ApiClient: Exiting channelFlow block.")
            // channelFlow ensures the channel is closed when the block exits or an error occurs.
        }
    }.flowOn(Dispatchers.IO) // Ensure network operations run on the IO dispatcher
}