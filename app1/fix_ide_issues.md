# 解决IDE中"Unresolved reference"错误的方法

你的项目可以正常构建，但IDE显示很多红色错误。这是常见的Android Studio索引问题。以下是解决方案：

## 方法1：重新同步项目（推荐）

1. 在Android Studio中点击 **File** → **Sync Project with Gradle Files**
2. 等待同步完成
3. 如果还有问题，继续下一步

## 方法2：清理并重建项目

1. 在Android Studio中点击 **Build** → **Clean Project**
2. 等待清理完成后，点击 **Build** → **Rebuild Project**

## 方法3：重启IDE并清理缓存

1. 关闭Android Studio
2. 重新打开Android Studio
3. 如果问题仍然存在，点击 **File** → **Invalidate Caches and Restart**
4. 选择 **Invalidate and Restart**

## 方法4：手动刷新依赖（命令行）

在项目根目录执行：
```bash
.\gradlew clean
.\gradlew --refresh-dependencies assembleDebug
```

## 方法5：检查Kotlin插件版本

确保Android Studio的Kotlin插件版本与项目中的Kotlin版本(2.0.0)兼容。

## 已修复的问题

✅ 更新了Compose BOM版本从 `2025.05.00` 到更稳定的 `2024.12.01`
✅ 项目可以正常构建
✅ 所有依赖都正确配置

## 注意事项

- 这些错误只是IDE显示问题，不影响实际编译
- 项目构建成功说明代码本身没有问题
- 通常重新同步项目就能解决大部分问题

## 如果问题仍然存在

如果上述方法都不能解决问题，可能需要：
1. 更新Android Studio到最新版本
2. 检查是否有其他插件冲突
3. 创建新的项目并迁移代码