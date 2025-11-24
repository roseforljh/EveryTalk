# Changelog

## [1.9.0](https://github.com/roseforljh/EveryTalk/compare/v1.8.0...v1.9.0) (2025-11-24)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* 优化VoiceInputScreen.kt文件，从670行大幅精简到209行（减少69%代码量） ([8c078a4](https://github.com/roseforljh/EveryTalk/commit/8c078a4ee298d9e95d02ec081395f07e4dfd1305))
* 压缩语音文件 ([bd005ba](https://github.com/roseforljh/EveryTalk/commit/bd005ba40088c726a4c77f7acc0be7f8a0c43d68))
* 增强语音模式中断按钮功能 ([1c2f484](https://github.com/roseforljh/EveryTalk/commit/1c2f48455e817016c458280ae9dcb5ce5be2cd92))
* 完善语音模式 ([071f35c](https://github.com/roseforljh/EveryTalk/commit/071f35c88061419bc0ed1ddf1d4891a0628b029a))
* 移除了监听 isApiCalling 变化的 LaunchedEffect 代码块。 ([1811625](https://github.com/roseforljh/EveryTalk/commit/18116252604c205a58f49f2327d9ccccba8ebae4))
* 网络握手性能优化 ([1b3995b](https://github.com/roseforljh/EveryTalk/commit/1b3995baaebafa0af5d7781e45326a67ea4e56b7))
* 语音模式添加过渡动画 ([202e7f0](https://github.com/roseforljh/EveryTalk/commit/202e7f0a06d88414a61f7df6aa9d3cb38ea1638e))
* 语音流式输出 ([1940752](https://github.com/roseforljh/EveryTalk/commit/1940752953ca194ef67820f1707f0d1964a635cd))
* 适配了几个免费的tts ([3aea999](https://github.com/roseforljh/EveryTalk/commit/3aea999167989cbb9d86850bc53ac817b2ed75be))
* 重构对话输出ui显示逻辑 ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 预缓冲阈值改为从 VOICE_STREAM_PREBUFFER_MS 读取，在 StreamAudioPlayer 初始化和 Underrun 重新缓冲处都统一使用： ([4dc3057](https://github.com/roseforljh/EveryTalk/commit/4dc30573d926adebe1ce0d83db331fc15c2a6618))


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 合并了 CancellationException 的处理逻辑，将原来的单独 catch (java.util.concurrent.CancellationException) 与通用 catch (Exception) 合并，改为统一在 catch (Exception) 内判断 ([7b05e0e](https://github.com/roseforljh/EveryTalk/commit/7b05e0e4b251a3d3ce6e19cda19ea52445a15380))
* 流式音频播放器成功完成了播放（日志显示"Playback wait timed out"），但等待超时时间过长（15秒） ([4b0f496](https://github.com/roseforljh/EveryTalk/commit/4b0f496ef42b5ef94b06d68631bc1b802e06065e))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 硅基流动tts爆音 ([6909525](https://github.com/roseforljh/EveryTalk/commit/69095253ac36c47531649a70f41f8582c2ac07fa))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))

## [1.8.0](https://github.com/roseforljh/EveryTalk/compare/v1.7.1...v1.8.0) (2025-11-24)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* 优化VoiceInputScreen.kt文件，从670行大幅精简到209行（减少69%代码量） ([8c078a4](https://github.com/roseforljh/EveryTalk/commit/8c078a4ee298d9e95d02ec081395f07e4dfd1305))
* 压缩语音文件 ([bd005ba](https://github.com/roseforljh/EveryTalk/commit/bd005ba40088c726a4c77f7acc0be7f8a0c43d68))
* 增强语音模式中断按钮功能 ([1c2f484](https://github.com/roseforljh/EveryTalk/commit/1c2f48455e817016c458280ae9dcb5ce5be2cd92))
* 完善语音模式 ([071f35c](https://github.com/roseforljh/EveryTalk/commit/071f35c88061419bc0ed1ddf1d4891a0628b029a))
* 移除了监听 isApiCalling 变化的 LaunchedEffect 代码块。 ([1811625](https://github.com/roseforljh/EveryTalk/commit/18116252604c205a58f49f2327d9ccccba8ebae4))
* 网络握手性能优化 ([1b3995b](https://github.com/roseforljh/EveryTalk/commit/1b3995baaebafa0af5d7781e45326a67ea4e56b7))
* 语音模式添加过渡动画 ([202e7f0](https://github.com/roseforljh/EveryTalk/commit/202e7f0a06d88414a61f7df6aa9d3cb38ea1638e))
* 语音流式输出 ([1940752](https://github.com/roseforljh/EveryTalk/commit/1940752953ca194ef67820f1707f0d1964a635cd))
* 适配了几个免费的tts ([3aea999](https://github.com/roseforljh/EveryTalk/commit/3aea999167989cbb9d86850bc53ac817b2ed75be))
* 重构对话输出ui显示逻辑 ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 预缓冲阈值改为从 VOICE_STREAM_PREBUFFER_MS 读取，在 StreamAudioPlayer 初始化和 Underrun 重新缓冲处都统一使用： ([4dc3057](https://github.com/roseforljh/EveryTalk/commit/4dc30573d926adebe1ce0d83db331fc15c2a6618))


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 合并了 CancellationException 的处理逻辑，将原来的单独 catch (java.util.concurrent.CancellationException) 与通用 catch (Exception) 合并，改为统一在 catch (Exception) 内判断 ([7b05e0e](https://github.com/roseforljh/EveryTalk/commit/7b05e0e4b251a3d3ce6e19cda19ea52445a15380))
* 流式音频播放器成功完成了播放（日志显示"Playback wait timed out"），但等待超时时间过长（15秒） ([4b0f496](https://github.com/roseforljh/EveryTalk/commit/4b0f496ef42b5ef94b06d68631bc1b802e06065e))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 硅基流动tts爆音 ([6909525](https://github.com/roseforljh/EveryTalk/commit/69095253ac36c47531649a70f41f8582c2ac07fa))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))

## [1.7.0](https://github.com/roseforljh/EveryTalk/compare/v1.6.11...v1.7.0) (2025-11-24)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* 完善语音模式 ([071f35c](https://github.com/roseforljh/EveryTalk/commit/071f35c88061419bc0ed1ddf1d4891a0628b029a))
* 移除了监听 isApiCalling 变化的 LaunchedEffect 代码块。 ([1811625](https://github.com/roseforljh/EveryTalk/commit/18116252604c205a58f49f2327d9ccccba8ebae4))
* 网络握手性能优化 ([1b3995b](https://github.com/roseforljh/EveryTalk/commit/1b3995baaebafa0af5d7781e45326a67ea4e56b7))
* 适配了几个免费的tts ([3aea999](https://github.com/roseforljh/EveryTalk/commit/3aea999167989cbb9d86850bc53ac817b2ed75be))
* 重构对话输出ui显示逻辑 ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))

## [1.6.11](https://github.com/roseforljh/EveryTalk/compare/v1.6.10...v1.6.11) (2025-11-22)


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))

## [1.6.10](https://github.com/roseforljh/EveryTalk/compare/v1.6.9...v1.6.10) (2025-11-22)


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))

## [1.6.8](https://github.com/roseforljh/EveryTalk/compare/v1.6.7...v1.6.8) (2025-11-21)


### Bug Fixes

* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))

## [1.6.7](https://github.com/roseforljh/EveryTalk/compare/v1.6.6...v1.6.7) (2025-11-21)


### Bug Fixes

* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))

## [0.1.1](https://github.com/roseforljh/EveryTalk/compare/v0.1.0...v0.1.1) (2025-11-21)


### Bug Fixes

* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
