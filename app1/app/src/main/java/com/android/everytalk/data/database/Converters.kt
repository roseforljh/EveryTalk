package com.android.everytalk.data.database

import android.net.Uri
import androidx.room.TypeConverter
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.ui.components.MarkdownPartSerializer
import com.android.everytalk.util.serialization.UriSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        serializersModule = SerializersModule {
            contextual(Uri::class, UriSerializer)
            polymorphic(SelectedMediaItem::class) {
                subclass(SelectedMediaItem.ImageFromUri::class, SelectedMediaItem.ImageFromUri.serializer())
                subclass(SelectedMediaItem.ImageFromBitmap::class, SelectedMediaItem.ImageFromBitmap.serializer())
                subclass(SelectedMediaItem.GenericFile::class, SelectedMediaItem.GenericFile.serializer())
                subclass(SelectedMediaItem.Audio::class, SelectedMediaItem.Audio.serializer())
            }
        }
    }

    // Sender Enum
    @TypeConverter
    fun fromSender(value: Sender): String = value.name

    @TypeConverter
    fun toSender(value: String): Sender = try {
        Sender.valueOf(value)
    } catch (e: Exception) {
        Sender.User // Fallback
    }

    // ModalityType Enum
    @TypeConverter
    fun fromModalityType(value: ModalityType): String = value.name

    @TypeConverter
    fun toModalityType(value: String): ModalityType = try {
        ModalityType.valueOf(value)
    } catch (e: Exception) {
        ModalityType.TEXT // Fallback
    }

    // List<String> (for imageUrls, etc.)
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // List<WebSearchResult>
    @TypeConverter
    fun fromWebSearchResultList(value: List<WebSearchResult>): String =
        json.encodeToString(ListSerializer(WebSearchResult.serializer()), value)

    @TypeConverter
    fun toWebSearchResultList(value: String?): List<WebSearchResult> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(WebSearchResult.serializer()), value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // List<SelectedMediaItem>
    // Note: SelectedMediaItem must be Serializable for this to work directly.
    // Assuming SelectedMediaItem is marked @Serializable. If not, we might need a custom serializer wrapper.
    // Based on previous reads, it seems it might not be directly serializable or might be polymorphic.
    // Let's assume we can serialize it or it's handled. If SelectedMediaItem is sealed/polymorphic, Json handles it if configured.
    // Ideally SelectedMediaItem should have a custom serializer if it contains complex types like Bitmap (which it does).
    // However, for Room storage, we probably only stored paths/URIs in SharedPreferences too.
    // Let's check SelectedMediaItem definition later if needed. For now assuming it works or we filter out Bitmap.
    
    // Actually, saving Bitmap to DB is bad. The app already saves images to disk and stores paths.
    // SelectedMediaItemSerializer should handle this.
    @TypeConverter
    fun fromSelectedMediaItemList(value: List<SelectedMediaItem>?): String {
        if (value == null) return "[]"
        return json.encodeToString(ListSerializer(SelectedMediaItem.serializer()), value)
    }

    @TypeConverter
    fun toSelectedMediaItemList(value: String?): List<SelectedMediaItem> {
        if (value.isNullOrEmpty()) return emptyList()
        return json.decodeFromString(ListSerializer(SelectedMediaItem.serializer()), value)
    }

    // List<MarkdownPart>
    // Message.kt uses @Serializable(with = MarkdownPartSerializer::class) for parts.
    @TypeConverter
    fun fromMarkdownPartList(value: List<MarkdownPart>?): String {
        if (value == null) return "[]"
        return json.encodeToString(MarkdownPartSerializer, value)
    }

    @TypeConverter
    fun toMarkdownPartList(value: String?): List<MarkdownPart> {
        if (value.isNullOrEmpty()) return emptyList()
        return json.decodeFromString(MarkdownPartSerializer, value)
    }
    
    // 模型参数
    @TypeConverter
    fun fromModelParameters(value: ModelParameters): String {
        return json.encodeToString(ModelParameters.serializer(), value)
    }

    @TypeConverter
    fun fromExecutionStepList(value: List<ExecutionStep>): String =
        json.encodeToString(ListSerializer(ExecutionStep.serializer()), value)

    @TypeConverter
    fun toExecutionStepList(value: String?): List<ExecutionStep> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(ExecutionStep.serializer()), value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromExecutionTrace(value: List<ExecutionTraceEvent>): String =
        json.encodeToString(ListSerializer(ExecutionTraceEvent.serializer()), value)

    @TypeConverter
    fun toExecutionTrace(value: String?): List<ExecutionTraceEvent> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(ExecutionTraceEvent.serializer()), value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun toModelParameters(value: String?): ModelParameters {
        if (value.isNullOrEmpty()) return ModelParameters()
        return try {
            json.decodeFromString(ModelParameters.serializer(), value)
        } catch (e: Exception) {
            ModelParameters()
        }
    }

    @TypeConverter
    fun fromTokenUsage(value: TokenUsage?): String? = value?.let {
        json.encodeToString(TokenUsage.serializer(), it)
    }

    @TypeConverter
    fun toTokenUsage(value: String?): TokenUsage? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(TokenUsage.serializer(), value)
        } catch (_: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromContextUsageSnapshot(value: ContextUsageSnapshot?): String? = value?.let {
        json.encodeToString(ContextUsageSnapshot.serializer(), it)
    }

    @TypeConverter
    fun toContextUsageSnapshot(value: String?): ContextUsageSnapshot? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(ContextUsageSnapshot.serializer(), value)
        } catch (_: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromContextCompressionState(value: ContextCompressionState?): String? = value?.let {
        json.encodeToString(ContextCompressionState.serializer(), it)
    }

    @TypeConverter
    fun toContextCompressionState(value: String?): ContextCompressionState? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(ContextCompressionState.serializer(), value)
        } catch (_: Exception) {
            null
        }
    }
    
    // Map<String, List<String>> for Groups
    @TypeConverter
    fun fromStringListMap(value: Map<String, List<String>>?): String {
         if (value == null) return "{}"
         return json.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value)
    }
    
    @TypeConverter
    fun toStringListMap(value: String?): Map<String, List<String>> {
        if (value.isNullOrEmpty()) return emptyMap()
        return try {
            json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), value)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
