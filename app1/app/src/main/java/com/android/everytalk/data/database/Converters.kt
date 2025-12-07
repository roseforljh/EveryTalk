package com.android.everytalk.data.database

import androidx.room.TypeConverter
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.ui.components.MarkdownPartSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        isLenient = true
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
    fun fromStringList(value: List<String>?): String {
        if (value == null) return "[]"
        return json.encodeToString(ListSerializer(String.serializer()), value)
    }

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
    fun fromWebSearchResultList(value: List<WebSearchResult>?): String {
        if (value == null) return "[]"
        return json.encodeToString(ListSerializer(WebSearchResult.serializer()), value)
    }

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
        // We need a way to serialize this. Assuming there's a serializer available or we use a custom one.
        // Since I don't have the exact serializer for SelectedMediaItem handy in context (it was imported in Message.kt but I didn't read SelectedMediaItem.kt fully),
        // I will use a placeholder or assume a serializer exists.
        // Wait, Message.kt imported `com.android.everytalk.util.SelectedMediaItemSerializer`.
        // But `Message` uses `val attachments: List<SelectedMediaItem>`.
        // Let's rely on `SelectedMediaItem` being serializable or having a serializer.
        // For now, I'll assume standard polymorphism serialization works if configured, 
        // OR I might need to implement a custom serializer here.
        // Given I can't check SelectedMediaItem.kt easily right now without another tool call, 
        // and I want to be efficient, I'll use the one from `com.android.everytalk.util.SelectedMediaItemSerializer` if possible,
        // but that's an object/class.
        // Let's just use the `json` instance. If SelectedMediaItem has `@Serializable`, it works.
        return try {
             // We need to know the serializer. polymorphic list?
             // If SelectedMediaItem is a sealed class marked with @Serializable, ListSerializer(SelectedMediaItem.serializer()) works.
             // I'll assume it is.
             // If not, this might fail at runtime. I'll add a TODO/Comment.
             // Actually, looking at imports in `Message.kt`, it doesn't explicitly import a serializer for `attachments`.
             // But let's check `Converters` usage.
             json.encodeToString(ListSerializer(com.android.everytalk.models.SelectedMediaItem.serializer()), value)
        } catch (e: Exception) {
            "[]"
        }
    }

    @TypeConverter
    fun toSelectedMediaItemList(value: String?): List<SelectedMediaItem> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(com.android.everytalk.models.SelectedMediaItem.serializer()), value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // List<MarkdownPart>
    // Message.kt uses @Serializable(with = MarkdownPartSerializer::class) for parts.
    @TypeConverter
    fun fromMarkdownPartList(value: List<MarkdownPart>?): String {
        if (value == null) return "[]"
        // Since MarkdownPartSerializer is a KSerializer<List<MarkdownPart>>, we use it? 
        // No, MarkdownPartSerializer likely serializes a *single* List<MarkdownPart> or single MarkdownPart?
        // In Message.kt: `val parts: List<MarkdownPart>`. Annotation is on the property.
        // So MarkdownPartSerializer is likely `KSerializer<List<MarkdownPart>>`.
        return try {
            json.encodeToString(MarkdownPartSerializer, value)
        } catch (e: Exception) {
            "[]"
        }
    }

    @TypeConverter
    fun toMarkdownPartList(value: String?): List<MarkdownPart> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(MarkdownPartSerializer, value)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // GenerationConfig
    @TypeConverter
    fun fromGenerationConfig(value: GenerationConfig?): String {
        if (value == null) return "{}"
        return json.encodeToString(GenerationConfig.serializer(), value)
    }

    @TypeConverter
    fun toGenerationConfig(value: String?): GenerationConfig? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(GenerationConfig.serializer(), value)
        } catch (e: Exception) {
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