# 配置安全管理指南

## 概述

为了保护敏感信息（如API密钥、后端URL等），我们将配置信息从代码中分离出来，使用配置文件进行管理。

## 后端配置（backdAiTalk）

### 环境变量管理

敏感信息存储在 `.env` 文件中：

```bash
# 后端项目根目录
cd backdAiTalk/

# 编辑环境变量
nano .env
```

**重要配置项：**
- `GOOGLE_API_KEY`: Google AI Studio API 密钥
- `GOOGLE_CSE_ID`: Google 自定义搜索引擎 ID
- `PORT`: 服务端口（默认7860）

### 部署安全

1. **文件权限**：
   ```bash
   chmod 600 .env  # 仅所有者可读写
   ```

2. **版本控制**：
   - `.env` 文件已添加到 `.gitignore`
   - 使用 `.env.example` 作为模板

3. **Docker 部署**：
   ```bash
   # 使用 Docker Compose
   docker-compose up -d
   ```

## Android 应用配置（KunTalkwithAi）

### 后端URL配置

后端URL配置存储在 `assets/backend_config.json` 中：

```json
{
  "backend_urls": [
    "https://your-primary-backend.com/chat",
    "https://your-backup-backend.com/chat"
  ],
  "primary_url": "https://your-primary-backend.com/chat",
  "fallback_enabled": true,
  "timeout_ms": 30000,
  "concurrent_request_enabled": true,
  "race_timeout_ms": 10000
}
```

**重要安全更新：**
- 代码中已移除所有硬编码的后端URL
- 如果配置文件缺失或解析失败，应用将使用空配置并提示用户
- 所有后端URL必须通过配置文件提供，确保敏感信息不暴露在源代码中
```

### 配置管理类

使用 `BackendConfig` 类来管理配置：

```kotlin
// 初始化配置
BackendConfig.initialize(context)

// 获取后端URL列表
val urls = BackendConfig.getBackendUrls()
val primaryUrl = BackendConfig.getPrimaryUrl()

// 检查配置是否有效
if (urls.isEmpty()) {
    Log.e("Config", "未找到后端配置，请检查 assets/backend_config.json")
    // 应用应提示用户检查配置或无法正常工作
}
```

### 配置文件说明

- `backend_urls`: 后端服务器URL列表，支持多个备用服务器
- `primary_url`: 主要使用的后端URL
- `fallback_enabled`: 是否启用故障转移
- `timeout_ms`: 请求超时时间（毫秒）
- `concurrent_request_enabled`: 是否启用并发请求模式
- `race_timeout_ms`: 并发请求竞速超时时间（毫秒）

### 请求模式

- **顺序模式** (`concurrent_request_enabled: false`): 按顺序尝试每个URL，直到成功
- **并发模式** (`concurrent_request_enabled: true`): 同时请求所有URL，使用最先响应的结果

### 安全最佳实践

1. **配置文件保护**：
   - 配置文件存储在 `assets` 目录中
   - 不包含真正的敏感信息（如API密钥）
   - 仅包含后端服务地址

2. **代码分离**：
   - **已完全移除硬编码的URL**
   - 使用配置类统一管理
   - 代码中不再包含任何默认的生产URL
   - 支持运行时配置更新

3. **错误处理**：
   - 配置加载失败时使用空配置（不再提供默认URL）
   - 记录详细的错误日志
   - 应用会提示用户检查配置文件
   - 优雅降级处理

4. **源代码安全**：
   - 源代码中不包含任何后端URL
   - 即使反编译也无法获取接口地址
   - 所有配置都依赖外部配置文件

## 生产环境部署

### 后端部署检查清单

- [ ] 设置正确的 `GOOGLE_API_KEY`
- [ ] 配置 `GOOGLE_CSE_ID`（如需搜索功能）
- [ ] 设置合适的 `LOG_LEVEL`
- [ ] 配置防火墙规则
- [ ] 设置 SSL 证书（生产环境）
- [ ] 配置反向代理（可选）

### Android 应用发布检查清单

- [ ] 更新 `backend_config.json` 中的生产URL
- [ ] 移除调试日志
- [ ] 验证网络权限配置
- [ ] 测试所有后端URL的连通性
- [ ] 确保没有硬编码的敏感信息

## 故障排除

### 后端连接问题

1. **检查环境变量**：
   ```bash
   docker-compose logs eztalk-proxy
   ```

2. **验证API密钥**：
   ```bash
   curl -H "Authorization: Bearer $GOOGLE_API_KEY" \
        "https://generativelanguage.googleapis.com/v1beta/models"
   ```

3. **网络连通性**：
   ```bash
   curl http://your-server:7860/health
   ```

### Android 应用问题

1. **检查配置加载**：
   - 查看 LogCat 中的 "BackendConfig" 标签
   - 确认配置文件格式正确

2. **网络权限**：
   - 确认 `AndroidManifest.xml` 中有网络权限
   - 检查网络安全配置

3. **URL 可达性**：
   - 在浏览器中测试后端URL
   - 检查防火墙和代理设置

## 更新配置

### 后端配置更新

```bash
# 更新环境变量
nano .env

# 重启服务
docker-compose restart
```

### Android 配置更新

1. 修改 `assets/backend_config.json`
2. 重新编译应用
3. 或实现动态配置更新机制

通过这种配置管理方式，我们可以有效保护敏感信息，同时保持配置的灵活性和可维护性。