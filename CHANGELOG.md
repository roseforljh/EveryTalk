# Changelog

## [1.7.14](https://github.com/roseforljh/EveryTalk/compare/v1.7.13...v1.7.14) (2025-12-03)


### Bug Fixes

* 图像模式默认模型新增z-image ([af6504e](https://github.com/roseforljh/EveryTalk/commit/af6504eed674bead393c639b9869bc1af48d1134))

## [1.7.13](https://github.com/roseforljh/EveryTalk/compare/v1.7.12...v1.7.13) (2025-12-03)


### Bug Fixes

* md格式优化 ([e83c783](https://github.com/roseforljh/EveryTalk/commit/e83c78372b8e15fceba51eca7d015a4ed6c4e3e8))
* 修复 StreamingBuffer.kt：恢复了节流逻辑。现在它会严格遵守 120ms 或 30 个字符的阈值进行刷新，不再对每个字符都立即刷新。这确保了数据以稳定的节奏流向 UI。 ([81852fa](https://github.com/roseforljh/EveryTalk/commit/81852fa51dacd675253448d79ea15d956ec49d9d))

## [1.7.12](https://github.com/roseforljh/EveryTalk/compare/v1.7.11...v1.7.12) (2025-12-01)


### Bug Fixes

* katex优化 ([3c5537c](https://github.com/roseforljh/EveryTalk/commit/3c5537c8a897328acef3e0620528bdae4dfb696f))
* 优化数学公式布局空间 ([958a1b2](https://github.com/roseforljh/EveryTalk/commit/958a1b2c94e8d74fd818e93810d95ab92344266f))
* 修复两个转换问题，把"和**换个位置 **"文本"** 变成" **文本**"；另一个则是$$ ([d6679fd](https://github.com/roseforljh/EveryTalk/commit/d6679fd65e73ade1180c18a33f3155e8a78efff0))

## [1.7.11](https://github.com/roseforljh/EveryTalk/compare/v1.7.10...v1.7.11) (2025-11-30)


### Bug Fixes

* voice mode bug 麦克风按钮逻辑修复与完善 ([7e16006](https://github.com/roseforljh/EveryTalk/commit/7e160065503674f0b2e233eecfc2d36cebe385a3))
* 优化滚动效果 ([919528e](https://github.com/roseforljh/EveryTalk/commit/919528ee0978b45dff31c25fcfa46c5a89cb76da))
* 优化滚动逻辑 ([474f47c](https://github.com/roseforljh/EveryTalk/commit/474f47c2c2a636d3d509db808b7967e254969462))
* 图片水平 ([02711ec](https://github.com/roseforljh/EveryTalk/commit/02711ecb9aef3010991218c6e5dcc7424554ea91))
* 实现行内数学公式转换 ([aeb2f83](https://github.com/roseforljh/EveryTalk/commit/aeb2f833306203761bf6e57ddcd6631931b8afbe))
* 当 AI 输出结束时，界面应该会保持稳定，不会再发生跳动 ([95e0b4a](https://github.com/roseforljh/EveryTalk/commit/95e0b4aa08bcb514e296a5d96cc52ed5d555532d))
* 语音模式流式输出 ([29ceff0](https://github.com/roseforljh/EveryTalk/commit/29ceff0de1c15141ab7dbb9b431d45e870f1f8a6))
* 限制用户气泡最大高度、优化行内代码样式 ([674717a](https://github.com/roseforljh/EveryTalk/commit/674717abea6e1bae994afc1bf133a01a299da1aa))

## [1.7.10](https://github.com/roseforljh/EveryTalk/compare/v1.7.9...v1.7.10) (2025-11-26)


### Bug Fixes

* 按钮UI美化 ([5374b0c](https://github.com/roseforljh/EveryTalk/commit/5374b0c937e2c36f57c339044f4c97b26f228a9d))

## [1.7.9](https://github.com/roseforljh/EveryTalk/compare/v1.7.8...v1.7.9) (2025-11-26)


### Bug Fixes

* 优化输出框UI ([9f807e4](https://github.com/roseforljh/EveryTalk/commit/9f807e48b7f2922a6f5df50c2daf2e0c553befbb))

## [1.7.8](https://github.com/roseforljh/EveryTalk/compare/v1.7.7...v1.7.8) (2025-11-26)


### Bug Fixes

* bug修复 ([1c6acf9](https://github.com/roseforljh/EveryTalk/commit/1c6acf932dc8806401364323412947c26107939e))

## [1.7.7](https://github.com/roseforljh/EveryTalk/compare/v1.7.6...v1.7.7) (2025-11-26)


### Bug Fixes

* 修复用户气泡过长造成的对话体验变差的bug ([599e6a0](https://github.com/roseforljh/EveryTalk/commit/599e6a08b8bdc52562efdc317014a9e9bf0a6d92))

## [1.7.6](https://github.com/roseforljh/EveryTalk/compare/v1.7.5...v1.7.6) (2025-11-26)


### Bug Fixes

* 动态版本号 ([ae40f9f](https://github.com/roseforljh/EveryTalk/commit/ae40f9f6566619e828f952b518e7ce59f789d663))
* 安装包版本号有问题 ([c4f09b8](https://github.com/roseforljh/EveryTalk/commit/c4f09b8997f0beb0f3956651b0324e9b9155965f))

## [1.7.5](https://github.com/roseforljh/EveryTalk/compare/v1.7.4...v1.7.5) (2025-11-26)


### Bug Fixes

* **ui:** update debug logging ([1d1304e](https://github.com/roseforljh/EveryTalk/commit/1d1304ef0a3080ecd47a66544bdcbf4f5908bff8))
* **ui:** update debug logging ([585ffd8](https://github.com/roseforljh/EveryTalk/commit/585ffd8da73c9b849f23d37557950981949ed9ed))
* **ui:** update debug logging in EnhancedMarkdownText ([da9b93b](https://github.com/roseforljh/EveryTalk/commit/da9b93b8a86aba64497209f7a789d2b21cd03ac8))

## [1.7.4](https://github.com/roseforljh/EveryTalk/compare/v1.7.3...v1.7.4) (2025-11-26)


### Bug Fixes

* **ui:** optimize imports in EnhancedMarkdownText ([a371936](https://github.com/roseforljh/EveryTalk/commit/a371936128ec9e930ece0dc2d35c81111e91dd24))

## [1.10.0](https://github.com/roseforljh/EveryTalk/compare/v1.9.0...v1.10.0) (2025-11-26)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* 优化VoiceInputScreen.kt文件，从670行大幅精简到209行（减少69%代码量） ([8c078a4](https://github.com/roseforljh/EveryTalk/commit/8c078a4ee298d9e95d02ec081395f07e4dfd1305))
* 前端ui输出优美的效果 ([12f8445](https://github.com/roseforljh/EveryTalk/commit/12f8445a9077c21644821905cbca87bcc878322a))
* 压缩语音文件 ([bd005ba](https://github.com/roseforljh/EveryTalk/commit/bd005ba40088c726a4c77f7acc0be7f8a0c43d68))
* 增强语音模式中断按钮功能 ([1c2f484](https://github.com/roseforljh/EveryTalk/commit/1c2f48455e817016c458280ae9dcb5ce5be2cd92))
* 完善语音模式 ([071f35c](https://github.com/roseforljh/EveryTalk/commit/071f35c88061419bc0ed1ddf1d4891a0628b029a))
* 移除了监听 isApiCalling 变化的 LaunchedEffect 代码块。 ([1811625](https://github.com/roseforljh/EveryTalk/commit/18116252604c205a58f49f2327d9ccccba8ebae4))
* 网络握手性能优化 ([1b3995b](https://github.com/roseforljh/EveryTalk/commit/1b3995baaebafa0af5d7781e45326a67ea4e56b7))
* 语音模式添加过渡动画 ([202e7f0](https://github.com/roseforljh/EveryTalk/commit/202e7f0a06d88414a61f7df6aa9d3cb38ea1638e))
* 语音流式输出 ([1940752](https://github.com/roseforljh/EveryTalk/commit/1940752953ca194ef67820f1707f0d1964a635cd))
* 过渡动画 ([5c9430c](https://github.com/roseforljh/EveryTalk/commit/5c9430cac026b1271e7d01a70ecd9d67e37dd76c))
* 适配了几个免费的tts ([3aea999](https://github.com/roseforljh/EveryTalk/commit/3aea999167989cbb9d86850bc53ac817b2ed75be))
* 重构对话输出ui显示逻辑 ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 预缓冲阈值改为从 VOICE_STREAM_PREBUFFER_MS 读取，在 StreamAudioPlayer 初始化和 Underrun 重新缓冲处都统一使用： ([4dc3057](https://github.com/roseforljh/EveryTalk/commit/4dc30573d926adebe1ce0d83db331fc15c2a6618))


### Bug Fixes

* ai气泡刷新 ([b655918](https://github.com/roseforljh/EveryTalk/commit/b655918db2a9b2d3f71389c1c1d1af691b6bd6f8))
* enable artifact build on PRs and sync version ([0eacfae](https://github.com/roseforljh/EveryTalk/commit/0eacfae9deff27b73f9e2c9d5356bbf0ef7111c1))
* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 合并了 CancellationException 的处理逻辑，将原来的单独 catch (java.util.concurrent.CancellationException) 与通用 catch (Exception) 合并，改为统一在 catch (Exception) 内判断 ([7b05e0e](https://github.com/roseforljh/EveryTalk/commit/7b05e0e4b251a3d3ce6e19cda19ea52445a15380))
* 图像模式bug ([c98110c](https://github.com/roseforljh/EveryTalk/commit/c98110c71554f7f710dd1bd44eac070be016d68c))
* 当用户气泡和文本一起发送时布局不正确 ([6486066](https://github.com/roseforljh/EveryTalk/commit/6486066217dd1f8b2d21ab5500adea47fc58e757))
* 思考框小白条bug修复 ([ad39874](https://github.com/roseforljh/EveryTalk/commit/ad398747d0640087849823406747a19a964c7929))
* 标记程序化滚动：当代码自动执行置顶滚动时，设置一个“正在自动滚动”的标志位。 ([e7bd663](https://github.com/roseforljh/EveryTalk/commit/e7bd6634890323552dd1edbc9834ad3caa548596))
* 流式音频播放器成功完成了播放（日志显示"Playback wait timed out"），但等待超时时间过长（15秒） ([4b0f496](https://github.com/roseforljh/EveryTalk/commit/4b0f496ef42b5ef94b06d68631bc1b802e06065e))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 硅基流动tts爆音 ([6909525](https://github.com/roseforljh/EveryTalk/commit/69095253ac36c47531649a70f41f8582c2ac07fa))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))
* 语音模式的左滑返回没有像设置左滑返回那样的实时预览返回过渡动画效果 ([f95abcc](https://github.com/roseforljh/EveryTalk/commit/f95abcc0d3455cce2a5e591d70863de694091c1e))

## [1.9.0](https://github.com/roseforljh/EveryTalk/compare/v1.8.0...v1.9.0) (2025-11-26)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* 优化VoiceInputScreen.kt文件，从670行大幅精简到209行（减少69%代码量） ([8c078a4](https://github.com/roseforljh/EveryTalk/commit/8c078a4ee298d9e95d02ec081395f07e4dfd1305))
* 前端ui输出优美的效果 ([12f8445](https://github.com/roseforljh/EveryTalk/commit/12f8445a9077c21644821905cbca87bcc878322a))
* 压缩语音文件 ([bd005ba](https://github.com/roseforljh/EveryTalk/commit/bd005ba40088c726a4c77f7acc0be7f8a0c43d68))
* 增强语音模式中断按钮功能 ([1c2f484](https://github.com/roseforljh/EveryTalk/commit/1c2f48455e817016c458280ae9dcb5ce5be2cd92))
* 完善语音模式 ([071f35c](https://github.com/roseforljh/EveryTalk/commit/071f35c88061419bc0ed1ddf1d4891a0628b029a))
* 移除了监听 isApiCalling 变化的 LaunchedEffect 代码块。 ([1811625](https://github.com/roseforljh/EveryTalk/commit/18116252604c205a58f49f2327d9ccccba8ebae4))
* 网络握手性能优化 ([1b3995b](https://github.com/roseforljh/EveryTalk/commit/1b3995baaebafa0af5d7781e45326a67ea4e56b7))
* 语音模式添加过渡动画 ([202e7f0](https://github.com/roseforljh/EveryTalk/commit/202e7f0a06d88414a61f7df6aa9d3cb38ea1638e))
* 语音流式输出 ([1940752](https://github.com/roseforljh/EveryTalk/commit/1940752953ca194ef67820f1707f0d1964a635cd))
* 过渡动画 ([5c9430c](https://github.com/roseforljh/EveryTalk/commit/5c9430cac026b1271e7d01a70ecd9d67e37dd76c))
* 适配了几个免费的tts ([3aea999](https://github.com/roseforljh/EveryTalk/commit/3aea999167989cbb9d86850bc53ac817b2ed75be))
* 重构对话输出ui显示逻辑 ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 预缓冲阈值改为从 VOICE_STREAM_PREBUFFER_MS 读取，在 StreamAudioPlayer 初始化和 Underrun 重新缓冲处都统一使用： ([4dc3057](https://github.com/roseforljh/EveryTalk/commit/4dc30573d926adebe1ce0d83db331fc15c2a6618))


### Bug Fixes

* ai气泡刷新 ([b655918](https://github.com/roseforljh/EveryTalk/commit/b655918db2a9b2d3f71389c1c1d1af691b6bd6f8))
* enable artifact build on PRs and sync version ([0eacfae](https://github.com/roseforljh/EveryTalk/commit/0eacfae9deff27b73f9e2c9d5356bbf0ef7111c1))
* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 合并了 CancellationException 的处理逻辑，将原来的单独 catch (java.util.concurrent.CancellationException) 与通用 catch (Exception) 合并，改为统一在 catch (Exception) 内判断 ([7b05e0e](https://github.com/roseforljh/EveryTalk/commit/7b05e0e4b251a3d3ce6e19cda19ea52445a15380))
* 图像模式bug ([c98110c](https://github.com/roseforljh/EveryTalk/commit/c98110c71554f7f710dd1bd44eac070be016d68c))
* 当用户气泡和文本一起发送时布局不正确 ([6486066](https://github.com/roseforljh/EveryTalk/commit/6486066217dd1f8b2d21ab5500adea47fc58e757))
* 思考框小白条bug修复 ([ad39874](https://github.com/roseforljh/EveryTalk/commit/ad398747d0640087849823406747a19a964c7929))
* 标记程序化滚动：当代码自动执行置顶滚动时，设置一个“正在自动滚动”的标志位。 ([e7bd663](https://github.com/roseforljh/EveryTalk/commit/e7bd6634890323552dd1edbc9834ad3caa548596))
* 流式音频播放器成功完成了播放（日志显示"Playback wait timed out"），但等待超时时间过长（15秒） ([4b0f496](https://github.com/roseforljh/EveryTalk/commit/4b0f496ef42b5ef94b06d68631bc1b802e06065e))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 硅基流动tts爆音 ([6909525](https://github.com/roseforljh/EveryTalk/commit/69095253ac36c47531649a70f41f8582c2ac07fa))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))
* 语音模式的左滑返回没有像设置左滑返回那样的实时预览返回过渡动画效果 ([f95abcc](https://github.com/roseforljh/EveryTalk/commit/f95abcc0d3455cce2a5e591d70863de694091c1e))

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
