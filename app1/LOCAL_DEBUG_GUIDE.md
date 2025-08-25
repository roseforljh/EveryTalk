# 本地调试指南

## 概述

为了方便本地调试，我们创建了独立的调试配置文件和相应的配置管理方法。

## 配置文件

### 生产环境配置
- 文件：`app/src/main/assets/backend_config.json`
- 用途：生产环境和远程服务器配置

### 本地调试配置
- 文件：`app/src/main/assets/backend_config_debug.json`
- 用途：本地开发调试配置
- 默认配置：`http://192.168.0.100:7860/chat`

## 使用方法

### 1. 启动后端服务

```bash
# 进入后端目录
cd backdAiTalk

# 启动后端服务
python run.py
```

服务将在 `http://0.0.0.0:7860` 启动，可通过 `http://192.168.0.100:7860` 访问。

### 2. Android应用配置

**自动配置切换**：应用已配置为根据构建类型自动选择配置文件，无需手动修改代码。

- **Debug构建**：自动使用 `backend_config_debug.json`（本地调试配置）
- **Release构建**：自动使用 `backend_config.json`（生产环境配置）

实现原理：
```kotlin
// ApiClient.initialize() 中的自动切换逻辑
if (BuildConfig.DEBUG) {
    BackendConfig.initialize(context, isDebugMode = true)  // 使用调试配置
} else {
    BackendConfig.initialize(context, isDebugMode = false) // 使用生产配置
}
```

### 3. 构建和运行

#### Debug构建（开发调试）
```bash
# Android Studio中直接运行，或使用Gradle命令
./gradlew assembleDebug
```

#### Release构建（生产发布）
```bash
# 构建Release版本
./gradlew assembleRelease
```

## 调试配置说明

### backend_config_debug.json 配置项

```json
{
  "backend_urls": [
    "http://192.168.0.100:7860/chat"
  ],
  "primary_url": "http://192.168.0.100:7860/chat",
  "fallback_enabled": false,
  "timeout_ms": 30000,
  "concurrent_request_enabled": false,
  "race_timeout_ms": 10000
}
```

- `fallback_enabled: false`: 调试时不需要故障转移
- `concurrent_request_enabled: false`: 调试时使用顺序请求，便于调试
- 只配置一个本地URL，简化调试流程

## 网络配置

确保Android应用的网络安全配置允许HTTP连接：

`app/src/main/res/xml/network_security_config.xml` 已配置允许本地IP访问。

## 故障排除

### 1. 连接失败
- 检查后端服务是否启动：访问 `http://192.168.0.100:7860`
- 检查防火墙设置
- 确认IP地址是否正确

### 2. 配置未生效
- 检查LogCat中的 "BackendConfig" 标签日志
- 确认调用了正确的初始化方法
- 重新安装应用确保配置文件更新

### 3. 网络权限
- 确认 `AndroidManifest.xml` 中有网络权限
- 检查网络安全配置是否正确

## 注意事项

1. **自动切换**：应用已实现自动配置切换，Debug构建自动使用本地配置，Release构建自动使用生产配置
2. **IP地址更新**：如果本地IP地址变化，需要更新 `backend_config_debug.json`
3. **构建类型**：
   - 在Android Studio中直接运行 = Debug构建（使用本地配置）
   - 生成APK安装包 = 根据构建变体决定（debug/release）
4. **版本控制**：调试配置文件可以提交到版本控制，但不要包含敏感信息
5. **安全性**：本地调试配置仅用于开发环境，Release构建会自动使用生产配置