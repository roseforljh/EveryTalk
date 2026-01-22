# AGENTS.md - AI Coding Agent Guidelines for EveryTalk

This document provides guidelines for AI coding agents working on the EveryTalk Android project.

## Project Overview

EveryTalk is an Android AI chat client built with Kotlin, Jetpack Compose, and Material Design 3.
The main Android project resides in `app1/`.

## Build Commands

All Gradle commands should be run from the `app1/` directory.

```powershell
# Navigate to project
cd app1

# Build debug APK
./gradlew :app:assembleDebug

# Build release APK
./gradlew :app:assembleRelease --parallel --build-cache

# Clean build
./gradlew clean

# Sync dependencies
./gradlew dependencies
```

## Test Commands

```powershell
# Run ALL unit tests
./gradlew :app:testDebugUnitTest

# Run a SINGLE test class
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.StreamingBufferTest"

# Run a SINGLE test method
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.StreamingBufferTest.first append triggers flush due to initial time delta"

# Run tests with pattern matching
./gradlew :app:testDebugUnitTest --tests "*BufferTest*"

# Run tests with verbose output
./gradlew :app:testDebugUnitTest --info
```

## Lint & Static Analysis

```powershell
# Run Android Lint (note: checkReleaseBuilds is disabled due to Kotlin 2.1.0 compatibility)
./gradlew :app:lintDebug

# Run Kotlin compiler checks
./gradlew :app:compileDebugKotlin
```

## Code Style Guidelines

### Language & Versions

| Component | Version |
|-----------|---------|
| Kotlin | 2.1.0 |
| JVM Target | 17 |
| Compose BOM | 2024.12.01 |
| Android Gradle Plugin | 8.7.3 |
| KSP | 2.1.0-1.0.29 |

### Import Conventions

Organize imports in this order:
1. Android SDK (`android.*`)
2. AndroidX (`androidx.*`)
3. Project imports (`com.android.everytalk.*`)
4. Third-party libraries (`io.ktor.*`, `kotlinx.*`, etc.)
5. Java standard library (`java.*`)

```kotlin
package com.android.everytalk.statecontroller

import android.util.Log
import androidx.lifecycle.ViewModel
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.util.debug.PerformanceMonitor
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
```

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Classes | PascalCase | `StreamingBuffer`, `ApiClient` |
| Functions | camelCase | `parseBackendStreamEvent()` |
| Properties | camelCase | `lastUpdateTime`, `pendingFlushJob` |
| Constants | SCREAMING_SNAKE_CASE | `HIGH_SPEED_CHARS_PER_SECOND` |
| Package | lowercase | `com.android.everytalk.data.network` |
| Test methods | Backtick descriptive | `` `first append triggers flush` `` |

### File Organization

```
app1/app/src/main/java/com/android/everytalk/
  config/           # App configuration classes
  data/
    database/       # Room database, DAOs, entities
    mcp/            # Model Context Protocol
    network/        # API clients, network utilities
      client/       # HTTP client factories
      direct/       # Direct API clients (Gemini, OpenAI)
      parser/       # Stream event parsers
    repository/     # Data repositories
  models/           # Data models
  provider/         # LLM provider implementations
  service/          # Service layer
  statecontroller/  # ViewModels and state management
    facade/         # UI state facades
    viewmodel/      # ViewModel managers
  ui/
    components/     # Reusable Compose components
    screens/        # Screen composables
    state/          # UI state machines
    theme/          # Colors, typography, dimensions
  util/             # Utility classes
    audio/          # Audio helpers
    cache/          # Caching utilities
    debug/          # Debug/performance tools
    messageprocessor/
    serialization/  # Custom serializers
    share/          # Export utilities
    storage/        # File management
    streaming/      # Streaming content utilities
```

### Kotlin Idioms

Prefer:
- `val` over `var` where possible
- Data classes for DTOs: `@Serializable data class ModelInfo(val id: String)`
- Sealed classes for state: `sealed class AppStreamEvent`
- Extension functions for utility methods
- `?.let {}` and `?:` for null handling
- `when` expressions over `if-else` chains
- Coroutines Flow for async streams

### Compose Patterns

- Use `remember` and `rememberSaveable` for state
- Prefer stateless composables with hoisted state
- Use `LaunchedEffect` for side effects
- Use Material Design 3 components from `androidx.compose.material3`

### Error Handling

```kotlin
// Prefer structured error handling
try {
    onUpdate(fullContent)
} catch (e: Exception) {
    Log.e(TAG, "[$messageId] onUpdate callback ERROR", e)
}

// Use sealed classes for result types
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable) : Result<Nothing>()
}

// Cancel coroutines gracefully
pendingFlushJob?.cancel()
pendingFlushJob = null
```

### Serialization

Use `kotlinx.serialization` with explicit annotations:

```kotlin
@Serializable
data class ModelsResponse(val data: List<ModelInfo>)

// For nullable JSON fields
val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
```

### Testing Patterns

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingBufferTest {
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `descriptive test name with backticks`() = runTest {
        // Arrange
        buffer = createBuffer(batchThreshold = 100)
        
        // Act
        buffer.append("Hello")
        
        // Assert
        assertEquals(1, collectedChunks.size)
    }
}
```

### Documentation

Use KDoc for public APIs:

```kotlin
/**
 * StreamingBuffer - Adaptive throttling content accumulator for smooth streaming display
 * 
 * Implements adaptive throttling strategy:
 * - Initial 60fps (16ms) high-frequency refresh for first-screen response
 * - Dynamically adjusts refresh rate based on flow speed when threshold exceeded
 */
class StreamingBuffer(...)
```

### Dependency Injection

The project uses Koin for DI (Hilt disabled due to Kotlin 2.1.0 compatibility):

```kotlin
implementation("io.insert-koin:koin-android:3.5.6")
implementation("io.insert-koin:koin-androidx-compose:3.5.6")
```

### Configuration

API keys and secrets are injected via `local.properties` or environment variables.
Never hardcode secrets. Use `BuildConfig` fields:

```kotlin
BuildConfig.GOOGLE_API_KEY
BuildConfig.DEFAULT_TEXT_API_URL
```

## Git Workflow

- Branch naming: `feature/`, `fix/`, `refactor/`
- Commit messages: `<type>(<scope>): <description>`
- Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`

## CI/CD

GitHub Actions workflow (`.github/workflows/build-artifacts.yml`):
- Triggers on release publish, PR, or manual dispatch
- Builds release APK with signing
- Uploads artifacts to GitHub Releases
- Sends Telegram notification on release

## Important Notes

1. **Kotlin 2.1.0 Compatibility**: Some tools (Hilt, Android Lint) have compatibility issues. Check `checkReleaseBuilds = false` in build config.

2. **Android SDK**: compileSdk=36, targetSdk=36, minSdk=27

3. **ProGuard**: Enabled for release builds with `proguard-rules.pro`

4. **Network**: Uses Ktor client with OkHttp engine, supports SSE streaming

5. **Database**: Room with KSP compiler for entities/DAOs
