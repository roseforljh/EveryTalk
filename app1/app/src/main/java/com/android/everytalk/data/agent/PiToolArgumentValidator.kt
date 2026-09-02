package com.android.everytalk.data.agent

import com.android.everytalk.data.network.anyToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pi `validateToolArguments` 的 Android 等价边界。
 *
 * Provider 只负责解析 ToolCall。真正执行前在这里按当前请求携带的 JSON Schema
 * 做一次统一校验和有限的 AJV 风格基础类型转换。失败只返回给模型，不进入 Executor。
 */
internal object PiToolArgumentValidator {
    fun prepareAndValidate(
        call: AgentContentBlock.ToolCall,
        definitions: List<Map<String, Any>>?,
    ): AgentContentBlock.ToolCall {
        val prepared = preparePiToolCallArguments(call)
        // 旧 AgentRun 快照可能没有保存 tools。迁移期继续交给原 Executor 校验；
        // 新请求只要带了工具表，就严格执行 Pi 的存在性和 schema 校验。
        if (definitions == null) return prepared
        val definition = definitions.firstOrNull { toolName(it) == prepared.name }
            ?: throw IllegalArgumentException("Tool \"${prepared.name}\" not found")
        val schema = toolParameters(definition) ?: JsonObject(emptyMap())
        val normalized = normalizeOptionalNulls(prepared.arguments, schema, schema)
        val converted = coerce(normalized, schema, schema)
        val errors = mutableListOf<String>()
        validate(converted, schema, schema, "root", errors)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Validation failed for tool \"${prepared.name}\":\n" +
                    errors.joinToString("\n") { "  - $it" },
            )
        }
        val arguments = converted as? JsonObject
            ?: throw IllegalArgumentException("Validation failed for tool \"${prepared.name}\": root must be object")
        return if (arguments == prepared.arguments) prepared else prepared.copy(arguments = arguments)
    }

    private fun toolName(definition: Map<String, Any>): String? {
        val function = definition["function"] as? Map<*, *>
        return (function?.get("name") ?: definition["name"]) as? String
    }

    private fun toolParameters(definition: Map<String, Any>): JsonObject? {
        val function = definition["function"] as? Map<*, *>
        val raw = function?.get("parameters") ?: definition["parameters"] ?: return null
        return anyToJsonElement(raw) as? JsonObject
    }

    /** 可选且不允许 null 的字段收到 null 时按 Pi 规则视为省略。 */
    private fun normalizeOptionalNulls(value: JsonElement, rawSchema: JsonObject, root: JsonObject): JsonElement {
        val schema = resolve(rawSchema, root)
        if (value is JsonArray) {
            val itemSchemas = schema["items"]
            return when (itemSchemas) {
                is JsonArray -> JsonArray(value.mapIndexed { index, child ->
                    val itemSchema = itemSchemas.getOrNull(index) as? JsonObject
                    if (itemSchema == null) child else normalizeOptionalNulls(child, itemSchema, root)
                })
                is JsonObject -> JsonArray(value.map { child -> normalizeOptionalNulls(child, itemSchemas, root) })
                else -> value
            }
        }
        if (value !is JsonObject) return value
        val properties = schema["properties"] as? JsonObject ?: return value
        val required = (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet()
        return JsonObject(value.mapValues { (name, child) ->
            val childSchema = properties[name] as? JsonObject ?: return@mapValues child
            normalizeOptionalNulls(child, childSchema, root)
        }.filterNot { (name, child) ->
            child is JsonNull && name !in required && (properties[name] as? JsonObject)?.get("\$ref") == null &&
                (properties[name] as? JsonObject)?.let { !acceptsNull(it, root) } == true
        })
    }

    private fun coerce(value: JsonElement, rawSchema: JsonObject, root: JsonObject): JsonElement {
        val schema = resolve(rawSchema, root)
        var nextValue = value
        (schema["allOf"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { arm ->
            nextValue = coerce(nextValue, arm, root)
        }
        coercionUnionSchemas(schema).takeIf(List<JsonObject>::isNotEmpty)?.let { arms ->
            arms.firstOrNull { matches(nextValue, it, root) }?.let { return nextValue }
            arms.forEach { arm ->
                val candidate = coerce(nextValue, arm, root)
                if (matches(candidate, arm, root)) return candidate
            }
            return nextValue
        }

        val types = schemaTypes(schema)
        if (types.size > 1) {
            if (types.any { matchesType(nextValue, it) }) return coerceChildren(nextValue, schema, root)
            types.forEach { type ->
                val candidate = coercePrimitive(nextValue, type)
                if (matchesType(candidate, type)) return coerceChildren(candidate, schema, root)
            }
            return nextValue
        }
        val converted = types.singleOrNull()?.let { coercePrimitive(nextValue, it) } ?: nextValue
        return coerceChildren(converted, schema, root)
    }

    private fun coerceChildren(value: JsonElement, schema: JsonObject, root: JsonObject): JsonElement = when (value) {
        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject
            val additional = schema["additionalProperties"] as? JsonObject
            JsonObject(value.mapValues { (name, child) ->
                val childSchema = properties?.get(name) as? JsonObject ?: additional
                if (childSchema == null) child else coerce(child, childSchema, root)
            })
        }
        is JsonArray -> {
            when (val items = schema["items"]) {
                is JsonObject -> JsonArray(value.map { coerce(it, items, root) })
                is JsonArray -> JsonArray(value.mapIndexed { index, child ->
                    val itemSchema = items.getOrNull(index) as? JsonObject
                    if (itemSchema == null) child else coerce(child, itemSchema, root)
                })
                else -> value
            }
        }
        else -> value
    }

    private fun coercePrimitive(value: JsonElement, type: String): JsonElement = when (type) {
        "number" -> when (value) {
            JsonNull -> JsonPrimitive(0)
            is JsonPrimitive -> when {
                value.doubleOrNull != null && !value.isString -> value
                !value.isString && value.booleanOrNull != null -> JsonPrimitive(if (value.booleanOrNull == true) 1 else 0)
                value.isString -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    ?.toDoubleOrNull()?.let(::JsonPrimitive) ?: value
                else -> value
            }
            else -> value
        }
        "integer" -> when (value) {
            JsonNull -> JsonPrimitive(0)
            is JsonPrimitive -> when {
                value.longOrNull != null && !value.isString -> value
                !value.isString && value.booleanOrNull != null -> JsonPrimitive(if (value.booleanOrNull == true) 1 else 0)
                value.isString -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    ?.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
                    ?.toLong()?.let(::JsonPrimitive) ?: value
                else -> value
            }
            else -> value
        }
        "boolean" -> when (value) {
            JsonNull -> JsonPrimitive(false)
            is JsonPrimitive -> when {
                value.booleanOrNull != null && !value.isString -> value
                value.isString && value.contentOrNull == "true" -> JsonPrimitive(true)
                value.isString && value.contentOrNull == "false" -> JsonPrimitive(false)
                !value.isString && value.doubleOrNull == 1.0 -> JsonPrimitive(true)
                !value.isString && value.doubleOrNull == 0.0 -> JsonPrimitive(false)
                else -> value
            }
            else -> value
        }
        "string" -> when (value) {
            JsonNull -> JsonPrimitive("")
            is JsonPrimitive -> if (value.isString) value else JsonPrimitive(value.content)
            else -> value
        }
        "null" -> when {
            value is JsonNull -> value
            value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
            value is JsonPrimitive && !value.isString && value.booleanOrNull == false -> JsonNull
            value is JsonPrimitive && !value.isString && value.doubleOrNull == 0.0 -> JsonNull
            else -> value
        }
        else -> value
    }

    private fun validate(
        value: JsonElement,
        rawSchema: JsonObject,
        root: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        val schema = resolve(rawSchema, root)
        (schema["allOf"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { arm ->
            validate(value, arm, root, path, errors)
        }
        (schema["anyOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { arms ->
            if (arms.none { matches(value, it, root) }) errors += "$path does not match any allowed schema"
            return
        }
        (schema["oneOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { arms ->
            val matchCount = arms.count { matches(value, it, root) }
            if (matchCount != 1) errors += "$path must match exactly one allowed schema"
            return
        }
        schema["const"]?.let { expected ->
            if (value != expected) errors += "$path must equal the declared constant"
        }
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) errors += "$path must be one of the declared values"
        }

        val types = schemaTypes(schema)
        if (types.isNotEmpty() && types.none { matchesType(value, it) }) {
            errors += "$path must be ${types.joinToString(" or ")}"
            return
        }

        when (value) {
            is JsonObject -> validateObject(value, schema, root, path, errors)
            is JsonArray -> validateArray(value, schema, root, path, errors)
            is JsonPrimitive -> validatePrimitive(value, schema, path, errors)
            JsonNull -> Unit
        }
    }

    private fun validateObject(
        value: JsonObject,
        schema: JsonObject,
        root: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        val properties = schema["properties"] as? JsonObject
        (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { name ->
            if (value[name] == null) errors += "$path.$name is required"
        }
        value.forEach { (name, child) ->
            val declared = properties?.get(name) as? JsonObject
            val additional = schema["additionalProperties"]
            when {
                declared != null -> validate(child, declared, root, "$path.$name", errors)
                additional is JsonObject -> validate(child, additional, root, "$path.$name", errors)
                additional is JsonPrimitive && additional.booleanOrNull == false -> errors += "$path.$name is not allowed"
            }
        }
        val count = value.size
        schema.intKeyword("minProperties")?.let { if (count < it) errors += "$path must contain at least $it properties" }
        schema.intKeyword("maxProperties")?.let { if (count > it) errors += "$path must contain at most $it properties" }
    }

    private fun validateArray(
        value: JsonArray,
        schema: JsonObject,
        root: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        schema.intKeyword("minItems")?.let { if (value.size < it) errors += "$path must contain at least $it items" }
        schema.intKeyword("maxItems")?.let { if (value.size > it) errors += "$path must contain at most $it items" }
        when (val items = schema["items"]) {
            is JsonObject -> value.forEachIndexed { index, child ->
                validate(child, items, root, "$path[$index]", errors)
            }
            is JsonArray -> value.forEachIndexed { index, child ->
                (items.getOrNull(index) as? JsonObject)?.let { validate(child, it, root, "$path[$index]", errors) }
            }
            else -> Unit
        }
    }

    private fun validatePrimitive(
        value: JsonPrimitive,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        if (value.isString) {
            schema.intKeyword("minLength")?.let { if (value.content.length < it) errors += "$path is too short" }
            schema.intKeyword("maxLength")?.let { if (value.content.length > it) errors += "$path is too long" }
            (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                val valid = runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(false)
                if (!valid) errors += "$path does not match the required pattern"
            }
        }
        value.doubleOrNull?.let { number ->
            schema.numberKeyword("minimum")?.let { if (number < it) errors += "$path must be at least $it" }
            schema.numberKeyword("maximum")?.let { if (number > it) errors += "$path must be at most $it" }
            schema.numberKeyword("exclusiveMinimum")?.let { if (number <= it) errors += "$path must be greater than $it" }
            schema.numberKeyword("exclusiveMaximum")?.let { if (number >= it) errors += "$path must be less than $it" }
        }
    }

    private fun matches(value: JsonElement, schema: JsonObject, root: JsonObject): Boolean {
        val errors = mutableListOf<String>()
        validate(value, schema, root, "root", errors)
        return errors.isEmpty()
    }

    private fun acceptsNull(schema: JsonObject, root: JsonObject): Boolean {
        val resolved = resolve(schema, root)
        return "null" in schemaTypes(resolved) ||
            (resolved["anyOf"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .any { acceptsNull(it, root) } ||
            (resolved["oneOf"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .any { acceptsNull(it, root) }
    }

    private fun resolve(schema: JsonObject, root: JsonObject): JsonObject {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return schema
        if (!ref.startsWith("#/")) return schema
        var current: JsonElement = root
        ref.removePrefix("#/").split('/').forEach { raw ->
            val key = raw.replace("~1", "/").replace("~0", "~")
            current = (current as? JsonObject)?.get(key) ?: return schema
        }
        return current as? JsonObject ?: schema
    }

    /** Pi 的转换阶段会依次尝试 anyOf 或 oneOf；最终校验仍保留两者不同的语义。 */
    private fun coercionUnionSchemas(schema: JsonObject): List<JsonObject> =
        listOf("anyOf", "oneOf").firstNotNullOfOrNull { key ->
            (schema[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
        }.orEmpty()

    private fun schemaTypes(schema: JsonObject): List<String> = when (val type = schema["type"]) {
        is JsonPrimitive -> listOfNotNull(type.contentOrNull)
        is JsonArray -> type.mapNotNull { it.jsonPrimitive.contentOrNull }
        else -> emptyList()
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "null" -> value is JsonNull
        else -> true
    }

    private fun JsonObject.intKeyword(name: String): Int? =
        (this[name] as? JsonPrimitive)?.longOrNull?.toInt()

    private fun JsonObject.numberKeyword(name: String): Double? =
        (this[name] as? JsonPrimitive)?.doubleOrNull
}
