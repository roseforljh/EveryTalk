# Changelog

## [1.11.0](https://github.com/roseforljh/EveryTalk/compare/v1.10.2...v1.11.0) (2026-01-12)


### Features

* **mcp-dialog:** 优化MCP对话框UI样式和颜色区分 ([10252a1](https://github.com/roseforljh/EveryTalk/commit/10252a11f65c6b2a7458fd7779901ac0c3c0f926))
* **mcp:** 为OpenAI兼容渠道适配MCP工具调用功能 ([533c679](https://github.com/roseforljh/EveryTalk/commit/533c679e1481107329deb45d87034c9b4fdbf7be))
* **mcp:** 重构MCP传输层并添加Gemini工具调用支持 ([5809344](https://github.com/roseforljh/EveryTalk/commit/5809344a0b2950b5abbf7b79bf6867b9dd338e03))


### Bug Fixes

* **code-block:** restore EOL tokens in fenced code parsing to preserve line breaks ([7f88431](https://github.com/roseforljh/EveryTalk/commit/7f884310bad9876e4c70107923d0e48e15197641))
* **core:** 流式渲染架构优化与Provider抽象重构 ([e0949d8](https://github.com/roseforljh/EveryTalk/commit/e0949d8c5011f8982efa741ffce7c69bcade6e92))
* **image-dialog:** 优化图像比例选择对话框样式 ([20b5a0a](https://github.com/roseforljh/EveryTalk/commit/20b5a0a6f9ed170e51797255d3b76242e22db628))
* **image-gen:** 完善图像模式置顶逻辑并新增图片滑动切换功能 ([73f0f7d](https://github.com/roseforljh/EveryTalk/commit/73f0f7d4029b39878adf28b0c83003c5fc909961))
* **mcp:** 添加MCP协议支持并修复Gemini PDF上传问题 ([d09bb97](https://github.com/roseforljh/EveryTalk/commit/d09bb97f60e5fde658bccd6d1328b4be34aa615f))
* **regenerate:** 重新回答置顶改为事件驱动，消除竞态条件 ([2b10838](https://github.com/roseforljh/EveryTalk/commit/2b1083847742508ceec5310300b8b23de0a7ec68))
* **scroll:** 统一滚动动画曲线，解决从中间位置发送消息时无加速度的问题 ([e044b1f](https://github.com/roseforljh/EveryTalk/commit/e044b1f99dbab724b0f399d12a31774d82de430b))
* **share:** 新增历史会话分享功能，支持导出为Markdown格式 ([7931104](https://github.com/roseforljh/EveryTalk/commit/79311042f818ba701a0839765cef73964e3db152))
* **sticky-header:** 修复代码块吸顶失效问题 ([f191d3b](https://github.com/roseforljh/EveryTalk/commit/f191d3b039a2512de49c8281e7b9e2e543c739d1))
* **streaming:** 修复流式渲染代码块/表格闪烁问题 ([44a9336](https://github.com/roseforljh/EveryTalk/commit/44a9336aea3771f66154c9830e11533b73c70228))
* **streaming:** 修复流式渲染闪烁和表格Markdown格式丢失问题 ([b9be5cd](https://github.com/roseforljh/EveryTalk/commit/b9be5cd19336c00f00b3625520a6f659c54db096))
* 更新默认模型 ([26eec51](https://github.com/roseforljh/EveryTalk/commit/26eec51a04a3809a7a98758ecf6af8fcc1a30283))
* 表格转换 ([e338705](https://github.com/roseforljh/EveryTalk/commit/e3387054c76cba0b2c236c36c419afdea67cc1d4))

## [1.10.2](https://github.com/roseforljh/EveryTalk/compare/v1.10.1...v1.10.2) (2025-12-25)


### Bug Fixes

* 代码块重组 ([0d4a730](https://github.com/roseforljh/EveryTalk/commit/0d4a730ce54cbbfc07d35deb233f2e6406abb8c2))
* 代码长按 ([9c3addc](https://github.com/roseforljh/EveryTalk/commit/9c3addcbc5315503bb7555325608cea6c45ff9af))

## [1.10.1](https://github.com/roseforljh/EveryTalk/compare/v1.10.0...v1.10.1) (2025-12-24)


### Bug Fixes

* 优化编辑功能 ([859e4b9](https://github.com/roseforljh/EveryTalk/commit/859e4b91f20584671f6e3f386affb35fec62251c))
* 修复css样式不生效的bug ([b75f340](https://github.com/roseforljh/EveryTalk/commit/b75f340049881acf2e45db75e447ad5c49188f02))
* 修复了流式结束瞬间因重新解析导致的 UI 重组跳动 ([c68e8d1](https://github.com/roseforljh/EveryTalk/commit/c68e8d12c9fa1ac4bf6f4cebb35ad7a87d9a3c51))
* 显示bug修复 ([bd8baea](https://github.com/roseforljh/EveryTalk/commit/bd8baea82dbc8fa2b338154576efbe61e48a1cb9))
* 滚动优化 ([8608a89](https://github.com/roseforljh/EveryTalk/commit/8608a8922c9eefa115244a33ef5abf02d49aa715))
* 适配语言高亮 ([0b6538e](https://github.com/roseforljh/EveryTalk/commit/0b6538e099fa8d1cce8a71a15db76f6f12e18003))

## [1.10.0](https://github.com/roseforljh/EveryTalk/compare/v1.9.4...v1.10.0) (2025-12-24)


### Features

* 2api适配 ([0baeaa4](https://github.com/roseforljh/EveryTalk/commit/0baeaa46356a8281d304d2fb098fe8f1c39362c3))
* modified:   app1/app/proguard-rules.pro ([0c3f378](https://github.com/roseforljh/EveryTalk/commit/0c3f37830a453cc6843e3e0890bbe295f9b774f2))
* stt-&gt;llm-&gt;tts ([a58d423](https://github.com/roseforljh/EveryTalk/commit/a58d423add201c0aa497f5d95555a46c43626bd0))
* update telegram notification style to send apk with details ([55bbf38](https://github.com/roseforljh/EveryTalk/commit/55bbf3868fcb37cbf8d1c10c750cbabd31b6bd21))
* upgrade telegram notification to send apk file with details ([246f164](https://github.com/roseforljh/EveryTalk/commit/246f1648703d77df48f370f43228b984755a6159))
* 优化VoiceInputScreen.kt文件，从670行大幅精简到209行（减少69%代码量） ([8c078a4](https://github.com/roseforljh/EveryTalk/commit/8c078a4ee298d9e95d02ec081395f07e4dfd1305))
* 全局直连！ ([5b24313](https://github.com/roseforljh/EveryTalk/commit/5b24313845a9dc2721900aabb048338fafd7b7a5))
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
* ai气泡动画 ([d1cb2a8](https://github.com/roseforljh/EveryTalk/commit/d1cb2a88218cd6bfcce2ba90cba856167e8bbe90))
* bug ([e5626ab](https://github.com/roseforljh/EveryTalk/commit/e5626ab8882170bdd6c063b4981468fefd7b1046))
* bug fix ([ec078af](https://github.com/roseforljh/EveryTalk/commit/ec078af4bf03eade225feeaf26720ef42cb6b2dd))
* bug fix ([ff9e802](https://github.com/roseforljh/EveryTalk/commit/ff9e8024532c2dc737e74f3995115ee9214d35e4))
* bug修复 ([528c1fd](https://github.com/roseforljh/EveryTalk/commit/528c1fdd7a870ee281a34b81af01da6831946cf1))
* bug修复 ([4c0abfb](https://github.com/roseforljh/EveryTalk/commit/4c0abfbe9e58592cf6b9209500053a9b1fc205dd))
* bug修复 ([1c6acf9](https://github.com/roseforljh/EveryTalk/commit/1c6acf932dc8806401364323412947c26107939e))
* correct python script path in build workflow ([5acdfa5](https://github.com/roseforljh/EveryTalk/commit/5acdfa5144c38bfa486e2d59114d07b383a81dd7))
* enable artifact build on PRs and sync version ([0eacfae](https://github.com/roseforljh/EveryTalk/commit/0eacfae9deff27b73f9e2c9d5356bbf0ef7111c1))
* fix something not goog code ([f8d5428](https://github.com/roseforljh/EveryTalk/commit/f8d5428bb19a85ce59ebf4f3cb5ec96e35ad45ed))
* force add github workflows and replace telegram action with curl ([a7af25f](https://github.com/roseforljh/EveryTalk/commit/a7af25fb6796108de2d04ddade7691837c96c7de))
* good ([eea96b2](https://github.com/roseforljh/EveryTalk/commit/eea96b2ae54b952174b86c9d83a646a0d2340ea5))
* Google工具调用 ([de7d0ea](https://github.com/roseforljh/EveryTalk/commit/de7d0ea317db18bd4b5196b1de197826fc969290))
* html ([b1eeef5](https://github.com/roseforljh/EveryTalk/commit/b1eeef5255c36a8ebec0aa466302d04ae6bff888))
* imagemode index mode bug ([db4a940](https://github.com/roseforljh/EveryTalk/commit/db4a940cb9ddd48d03b59357731e08f07ac062c8))
* katex优化 ([3c5537c](https://github.com/roseforljh/EveryTalk/commit/3c5537c8a897328acef3e0620528bdae4dfb696f))
* md格式优化 ([e83c783](https://github.com/roseforljh/EveryTalk/commit/e83c78372b8e15fceba51eca7d015a4ed6c4e3e8))
* md格式自修复增强、针对一些破垃圾狗屎模型 ([3bf8e37](https://github.com/roseforljh/EveryTalk/commit/3bf8e37ea325f03b4ab795d70992450eff661465))
* qwen-long原生识别文档 ([1e87d02](https://github.com/roseforljh/EveryTalk/commit/1e87d029899b1a61fcf55b48f8eff3a46cf3dc03))
* release编译修复、删除废弃代码 ([5f02f1f](https://github.com/roseforljh/EveryTalk/commit/5f02f1ff6fe74a2e8f27f95f71a491b913210a46))
* room ([8764c07](https://github.com/roseforljh/EveryTalk/commit/8764c079da9ef6bf896011f83301acad2f2ba5a2))
* StreamingControls.kt：恢复时同步 StreamingMessageStateManager 累积内容到 messages 列表 ([1c3ac81](https://github.com/roseforljh/EveryTalk/commit/1c3ac8160741581141a0af550872b021ab4a3016))
* STT/TTS 直连客户端，以优化延迟 ([46d458f](https://github.com/roseforljh/EveryTalk/commit/46d458f6118e653cba0fbfc268dacf92903c52f2))
* **ui:** optimize imports in EnhancedMarkdownText ([a371936](https://github.com/roseforljh/EveryTalk/commit/a371936128ec9e930ece0dc2d35c81111e91dd24))
* **ui:** update debug logging ([1d1304e](https://github.com/roseforljh/EveryTalk/commit/1d1304ef0a3080ecd47a66544bdcbf4f5908bff8))
* **ui:** update debug logging ([585ffd8](https://github.com/roseforljh/EveryTalk/commit/585ffd8da73c9b849f23d37557950981949ed9ed))
* **ui:** update debug logging in EnhancedMarkdownText ([da9b93b](https://github.com/roseforljh/EveryTalk/commit/da9b93b8a86aba64497209f7a789d2b21cd03ac8))
* voice mode bug 麦克风按钮逻辑修复与完善 ([7e16006](https://github.com/roseforljh/EveryTalk/commit/7e160065503674f0b2e233eecfc2d36cebe385a3))
* 优化prompt ([dcd709e](https://github.com/roseforljh/EveryTalk/commit/dcd709e85679b59eb1470ca92607ef84238abd55))
* 优化ui ([fd21316](https://github.com/roseforljh/EveryTalk/commit/fd2131687a2c275e52b25b44e94dfda64935ca01))
* 优化代码结构 ([eb747e5](https://github.com/roseforljh/EveryTalk/commit/eb747e55097c5f0d4447ce22b5382013dfecfefe))
* 优化多项潜在问题 ([4152872](https://github.com/roseforljh/EveryTalk/commit/41528722f38b501accb98b86062bf02dd602b93b))
* 优化数学公式布局空间 ([958a1b2](https://github.com/roseforljh/EveryTalk/commit/958a1b2c94e8d74fd818e93810d95ab92344266f))
* 优化滚动效果 ([919528e](https://github.com/roseforljh/EveryTalk/commit/919528ee0978b45dff31c25fcfa46c5a89cb76da))
* 优化滚动逻辑 ([474f47c](https://github.com/roseforljh/EveryTalk/commit/474f47c2c2a636d3d509db808b7967e254969462))
* 优化目录 ([07a93a4](https://github.com/roseforljh/EveryTalk/commit/07a93a43c0e67f679b8905c0740e04f1ec98a7f5))
* 优化目录 ([3e227d2](https://github.com/roseforljh/EveryTalk/commit/3e227d22279e46c302b9519b64c5f6438bd86d0a))
* 优化细节处理 ([dc90c25](https://github.com/roseforljh/EveryTalk/commit/dc90c250ec4570faea817147dad1629805ffdd5c))
* 优化网络 ([b3d20f8](https://github.com/roseforljh/EveryTalk/commit/b3d20f80ff48b64138161cf9ad33e075add61a53))
* 优化输出框UI ([9f807e4](https://github.com/roseforljh/EveryTalk/commit/9f807e48b7f2922a6f5df50c2daf2e0c553befbb))
* 修复 StreamingBuffer.kt：恢复了节流逻辑。现在它会严格遵守 120ms 或 30 个字符的阈值进行刷新，不再对每个字符都立即刷新。这确保了数据以稳定的节奏流向 UI。 ([81852fa](https://github.com/roseforljh/EveryTalk/commit/81852fa51dacd675253448d79ea15d956ec49d9d))
* 修复ai输出结束，总是会跳动的bug ([1200551](https://github.com/roseforljh/EveryTalk/commit/12005513afb736dc4d48e68a339e2ef7b3de7f6f))
* 修复xxx ([d34787f](https://github.com/roseforljh/EveryTalk/commit/d34787ffdc0b27fd64befa6b070f9271f0f9f364))
* 修复两个转换问题，把"和**换个位置 **"文本"** 变成" **文本**"；另一个则是$$ ([d6679fd](https://github.com/roseforljh/EveryTalk/commit/d6679fd65e73ade1180c18a33f3155e8a78efff0))
* 修复了“文件大小检查”误判/漏判的问题、修复了图片缩放判断的逻辑优先级 bug、让“发送附件”真正走统一的文件链路 ([bde3d25](https://github.com/roseforljh/EveryTalk/commit/bde3d251c8f0d295b5f479363790888264e92801))
* 修复了聊天列表滚动的 bug ([ebc68ae](https://github.com/roseforljh/EveryTalk/commit/ebc68aeb0259baf0803253979935c29d6f4db6db))
* 修复代码执行bug ([224fd3b](https://github.com/roseforljh/EveryTalk/commit/224fd3bca3eb2c678e0e2976db9cc3f254b3f616))
* 修复即梦等模型的图像编辑能力 ([fc3eaee](https://github.com/roseforljh/EveryTalk/commit/fc3eaee3eb73b2e22e032a338fb54f3f079e0e23))
* 修复已知bug ([c2e2814](https://github.com/roseforljh/EveryTalk/commit/c2e2814b8be4a3daf1d8e4d47ee2730fc0189151))
* 修复模式切换bug ([b01b18d](https://github.com/roseforljh/EveryTalk/commit/b01b18d7daccb81acdd2c3eccea1021880e515e2))
* 修复潜在bug ([3ad9f92](https://github.com/roseforljh/EveryTalk/commit/3ad9f9274e817258e61c1a11c8d49ed705adb54d))
* 修复潜在问题 ([51cd368](https://github.com/roseforljh/EveryTalk/commit/51cd36808948c9e412c4099b3e614d80e19382a1))
* 修复用户气泡过长造成的对话体验变差的bug ([599e6a0](https://github.com/roseforljh/EveryTalk/commit/599e6a08b8bdc52562efdc317014a9e9bf0a6d92))
* 修复缺失的环境变量 ([9a88dcc](https://github.com/roseforljh/EveryTalk/commit/9a88dccb5146edd4cbe4d6af6ed127bea6c30c02))
* 修复设置按钮按钮的导航逻辑 ([0359433](https://github.com/roseforljh/EveryTalk/commit/03594335bbc21945af01d4d774041551cc1d1354))
* 修复阿里云stt实时流式的bug ([c167e33](https://github.com/roseforljh/EveryTalk/commit/c167e33d9b1b5605f1acf46a8a4277b8beed60b8))
* 修复页面抖动 ([cfef9ea](https://github.com/roseforljh/EveryTalk/commit/cfef9eadbf96c076234e01742824b9cb360f7a43))
* 修改了 DataPersistenceManager.kt，移除了跳过清理的逻辑 ([3fe10ed](https://github.com/roseforljh/EveryTalk/commit/3fe10ed73ee2a13909c4a6473af3951ba8df90a0))
* 减小体积 ([6166c02](https://github.com/roseforljh/EveryTalk/commit/6166c025f1da8ca6c3d11e3159ab12cea9090f17))
* 删除废弃代码 ([beade2e](https://github.com/roseforljh/EveryTalk/commit/beade2e53364f095bc7af9e37cc368b2e2afeacf))
* 加大步数 ([7d91417](https://github.com/roseforljh/EveryTalk/commit/7d914170f32601fe90b5af69390b71ff6544e599))
* 加快tts输出 ([37b6ed4](https://github.com/roseforljh/EveryTalk/commit/37b6ed4f8afaf62f94e3736cc135f0166af98e6c))
* 加快阿里云tts输出首字的速度 ([9ed2f47](https://github.com/roseforljh/EveryTalk/commit/9ed2f470eaa74d0cee5464dd06e190a2a2519b48))
* 动态版本号 ([ae40f9f](https://github.com/roseforljh/EveryTalk/commit/ae40f9f6566619e828f952b518e7ce59f789d663))
* 升级数据库结构 ([acb0b8d](https://github.com/roseforljh/EveryTalk/commit/acb0b8dc64891aa4dce23747bd87092d610d71a7))
* 可能修复了持久化问题 ([fb4fbfe](https://github.com/roseforljh/EveryTalk/commit/fb4fbfe77cd567d3d213a0db1bfbd1a6897770be))
* 合并了 CancellationException 的处理逻辑，将原来的单独 catch (java.util.concurrent.CancellationException) 与通用 catch (Exception) 合并，改为统一在 catch (Exception) 内判断 ([7b05e0e](https://github.com/roseforljh/EveryTalk/commit/7b05e0e4b251a3d3ce6e19cda19ea52445a15380))
* 图像模式bug ([c98110c](https://github.com/roseforljh/EveryTalk/commit/c98110c71554f7f710dd1bd44eac070be016d68c))
* 图像模式两个默认模型的持久化问题修复 ([d5a1ab1](https://github.com/roseforljh/EveryTalk/commit/d5a1ab1828f3cd380428bbea889ef9bbe8306f97))
* 图像模式滚动优化 ([a7a649e](https://github.com/roseforljh/EveryTalk/commit/a7a649e534cece8b942f997bfb89295feb9d0288))
* 图像模式默认模型新增z-image ([af6504e](https://github.com/roseforljh/EveryTalk/commit/af6504eed674bead393c639b9869bc1af48d1134))
* 图像模式默认配置bug ([0336248](https://github.com/roseforljh/EveryTalk/commit/03362487ef9e9103312be5c9294bae66304a5ebb))
* 图像模式默认配置bug修复 ([913a817](https://github.com/roseforljh/EveryTalk/commit/913a817a2269649b63b96ac6390c7b1fa476b6fe))
* 图片持久化的问题 ([c7f6111](https://github.com/roseforljh/EveryTalk/commit/c7f6111659c6574857a67fc1fa8c123de00bb7a3))
* 图片水平 ([02711ec](https://github.com/roseforljh/EveryTalk/commit/02711ecb9aef3010991218c6e5dcc7424554ea91))
* 增加图像模式超时时间限制 ([5d00aae](https://github.com/roseforljh/EveryTalk/commit/5d00aae8b62eebdae3ebe381955a6124798f3add))
* 处理 URL 中的反斜杠转义 ([9fd734f](https://github.com/roseforljh/EveryTalk/commit/9fd734f84104758faef2b83d3af476b86cbd3eec))
* 安装包版本号有问题 ([c4f09b8](https://github.com/roseforljh/EveryTalk/commit/c4f09b8997f0beb0f3956651b0324e9b9155965f))
* 实现系统分享功能 ([ee1987d](https://github.com/roseforljh/EveryTalk/commit/ee1987d9c2ead7b484853f26439afe398ca3bc51))
* 实现行内数学公式转换 ([aeb2f83](https://github.com/roseforljh/EveryTalk/commit/aeb2f833306203761bf6e57ddcd6631931b8afbe))
* 对“表格渲染链路”补上流式结束必然触发最终解析的修复 ([4bf577b](https://github.com/roseforljh/EveryTalk/commit/4bf577b80bdc83bdaa480a2ecb8fa2ea826afb6d))
* 导入导出bug修复、语音模式模型名称bug修复 ([00f1827](https://github.com/roseforljh/EveryTalk/commit/00f18274312dd973af03fac6e8a4f3ec4a81b1d3))
* 当 AI 输出结束时，界面应该会保持稳定，不会再发生跳动 ([95e0b4a](https://github.com/roseforljh/EveryTalk/commit/95e0b4aa08bcb514e296a5d96cc52ed5d555532d))
* 当用户气泡和文本一起发送时布局不正确 ([6486066](https://github.com/roseforljh/EveryTalk/commit/6486066217dd1f8b2d21ab5500adea47fc58e757))
* 思考框小白条bug修复 ([ad39874](https://github.com/roseforljh/EveryTalk/commit/ad398747d0640087849823406747a19a964c7929))
* 我他妈真服了 ([2b70899](https://github.com/roseforljh/EveryTalk/commit/2b70899333cc49fbf2a63face896b6f7d94caaad))
* 按钮UI美化 ([5374b0c](https://github.com/roseforljh/EveryTalk/commit/5374b0c937e2c36f57c339044f4c97b26f228a9d))
* 提示优化 ([ac6214d](https://github.com/roseforljh/EveryTalk/commit/ac6214d00285215811539bd1db29de8f45758ef3))
* 改善卡片交互 ([4e8f415](https://github.com/roseforljh/EveryTalk/commit/4e8f415110bdb5dd82ec95b04e1ba71d3405704d))
* 改善语音模式llm的prompt ([cd0b2c1](https://github.com/roseforljh/EveryTalk/commit/cd0b2c1813ae2e02bb53a6b5d0b5c8883a690cf3))
* 改进交互 ([d6f0453](https://github.com/roseforljh/EveryTalk/commit/d6f0453d3967dc6463bd8b7561f42b85c0e4678b))
* 数学公式转换prompt修复 ([0495123](https://github.com/roseforljh/EveryTalk/commit/0495123869833bdaedab62718ae0b550ae7f79b8))
* 数据库优化 ([d4c49af](https://github.com/roseforljh/EveryTalk/commit/d4c49af99f767da899b948d6baf74ed367b5fb62))
* 文本样式优化 ([ed9b0d5](https://github.com/roseforljh/EveryTalk/commit/ed9b0d571c71a426f321c686d1acd847766821e5))
* 新代码块样式 ([f553712](https://github.com/roseforljh/EveryTalk/commit/f55371242c961ea1a43dbe2d394e20a542d9af8c))
* 更新reamde ([fde034b](https://github.com/roseforljh/EveryTalk/commit/fde034b12f0b059a0998853ef121360f61067929))
* 查看文本bug修复 ([4699daf](https://github.com/roseforljh/EveryTalk/commit/4699dafb2f373d898c652a50f1ff24cc61526391))
* 标记程序化滚动：当代码自动执行置顶滚动时，设置一个“正在自动滚动”的标志位。 ([e7bd663](https://github.com/roseforljh/EveryTalk/commit/e7bd6634890323552dd1edbc9834ad3caa548596))
* 没座！！！ ([e0cf574](https://github.com/roseforljh/EveryTalk/commit/e0cf574d435a273114535cc3d65c0541c2461af4))
* 流式音频播放器成功完成了播放（日志显示"Playback wait timed out"），但等待超时时间过长（15秒） ([4b0f496](https://github.com/roseforljh/EveryTalk/commit/4b0f496ef42b5ef94b06d68631bc1b802e06065e))
* 测试 ([39226a8](https://github.com/roseforljh/EveryTalk/commit/39226a82ccf65b8910a873e935ff0b15676a7cf6))
* 测试 ([ae1dc4e](https://github.com/roseforljh/EveryTalk/commit/ae1dc4e933119155c201ef8d8d9716e5f271bb35))
* 添加了 Telegram 通知功能 ([c1c9b86](https://github.com/roseforljh/EveryTalk/commit/c1c9b864cf0c96acc85aa82a1a4f1bb7b10ca030))
* 渲染结构始终一致 ([01e99b2](https://github.com/roseforljh/EveryTalk/commit/01e99b242df9e12544b758802c99ea269ff7f587))
* 滚动效果优化 ([ef42bf3](https://github.com/roseforljh/EveryTalk/commit/ef42bf359188449a2a8f8d7dce09bceb427cd972))
* 用户气泡不转换md格式、优化用户气泡逻辑 ([0f705fa](https://github.com/roseforljh/EveryTalk/commit/0f705fa24267abf8991daaf1be568ef1d1191f23))
* 直连编译默认配置常量 ([511080a](https://github.com/roseforljh/EveryTalk/commit/511080a8c8ac64b7bdaf642a2747d22c9b6e1536))
* 硅基流动tts爆音 ([6909525](https://github.com/roseforljh/EveryTalk/commit/69095253ac36c47531649a70f41f8582c2ac07fa))
* 移除孤儿文件清理机制 ([609b736](https://github.com/roseforljh/EveryTalk/commit/609b7366054432edc8f67d63d0b2bb855ccc8f9a))
* 移除废弃代码 ([484ca3e](https://github.com/roseforljh/EveryTalk/commit/484ca3e2fb204334613bb8b4783aaccedeabfdb8))
* 统一导入导出逻辑 ([eb24503](https://github.com/roseforljh/EveryTalk/commit/eb24503ba515ee8281f5dd4095562305142a13fe))
* 编译 ([29b06c8](https://github.com/roseforljh/EveryTalk/commit/29b06c8188b48ed425037bcf5b2287bb61ab6308))
* 美化UI ([0dc4953](https://github.com/roseforljh/EveryTalk/commit/0dc495333220f063094570adecaca24a021e55e5))
* 自定义z-image步数 ([93e60ba](https://github.com/roseforljh/EveryTalk/commit/93e60baf24fa427ca86e723a817ac0a38e7754ca))
* 自适应节流 (StreamingBuffer) - 初期快速响应（16ms），后期根据流速自动调整（8-100ms），避免高速流时过度重组导致 UI 卡顿，同时保证首屏体验 ([3ae8f0b](https://github.com/roseforljh/EveryTalk/commit/3ae8f0b025b1499f9f8408363ec681489eef31b0))
* 触发提交 ([54aca5f](https://github.com/roseforljh/EveryTalk/commit/54aca5f5f97310f06afd58ad4db0c6b2d7b4d89d))
* 识别逻辑严格限制为仅对 qwen-long 模型生效 ([0dd4da2](https://github.com/roseforljh/EveryTalk/commit/0dd4da2ef4e63c5785212d6bf1975460ee0d9c86))
* 语音模式bug修复 ([ffc80e5](https://github.com/roseforljh/EveryTalk/commit/ffc80e5ef6749e1c06dc307fd990ac584c08f475))
* 语音模式minimmax终极优化 ([d89f99e](https://github.com/roseforljh/EveryTalk/commit/d89f99e97e152772e479224792316650218e6f2e))
* 语音模式tts设置页面bug修复 ([69f4c49](https://github.com/roseforljh/EveryTalk/commit/69f4c49b3695154a477b9f2a2e538d23b92a3c4f))
* 语音模式修复回调 ([129450b](https://github.com/roseforljh/EveryTalk/commit/129450bcd7e846dae17c7ec9718a2e9a8d3083ad))
* 语音模式流式输出 ([29ceff0](https://github.com/roseforljh/EveryTalk/commit/29ceff0de1c15141ab7dbb9b431d45e870f1f8a6))
* 语音模式的prompt改为英文 ([6023522](https://github.com/roseforljh/EveryTalk/commit/602352227668c0b01b5d41d65e233ea648d06247))
* 语音模式的左滑返回没有像设置左滑返回那样的实时预览返回过渡动画效果 ([f95abcc](https://github.com/roseforljh/EveryTalk/commit/f95abcc0d3455cce2a5e591d70863de694091c1e))
* 适配fun-asr ([f2ce72f](https://github.com/roseforljh/EveryTalk/commit/f2ce72f212be2b1b2cfcdcd60a8611db08d234d3))
* 适配Gemini3 ([811fa92](https://github.com/roseforljh/EveryTalk/commit/811fa92ab2b89050f31d01d6b850beda7d40f73d))
* 适配统一原生联网搜索 ([ae2afe3](https://github.com/roseforljh/EveryTalk/commit/ae2afe3d16060380e344612cec688943a2ebf21a))
* 适配阿里云tts ([d982757](https://github.com/roseforljh/EveryTalk/commit/d982757493d6e37bc4671fd3dcd230f0dcab9a48))
* 重新回答bug ([e39d16a](https://github.com/roseforljh/EveryTalk/commit/e39d16a4213765e4e43d75e368c7effcd6a6c015))
* 重新回答动画优化 ([c6d46e2](https://github.com/roseforljh/EveryTalk/commit/c6d46e2bec6714312ed35850ce6c5fa9e3049c0c))
* 长按bug修复 ([53c0fff](https://github.com/roseforljh/EveryTalk/commit/53c0fffdebe359ab72e4bae523cf2d781d0a6831))
* 阿里云stt实时输出 ([8171fff](https://github.com/roseforljh/EveryTalk/commit/8171fffb75e6003d746723b4bcde8d5aa2b0ed28))
* 阿里系超低延迟 ([3fbed3c](https://github.com/roseforljh/EveryTalk/commit/3fbed3cbf9bc0530a55b1df413dca1120dcc3e2d))
* 限制用户气泡最大高度、优化行内代码样式 ([674717a](https://github.com/roseforljh/EveryTalk/commit/674717abea6e1bae994afc1bf133a01a299da1aa))

## [1.9.4](https://github.com/roseforljh/EveryTalk/compare/v1.9.3...v1.9.4) (2025-12-14)


### Bug Fixes

* 对“表格渲染链路”补上流式结束必然触发最终解析的修复 ([79c3098](https://github.com/roseforljh/EveryTalk/commit/79c30985cfad0a6c4f7287cbdbc24780302168bb))
## [1.9.3](https://github.com/roseforljh/EveryTalk/compare/v1.9.2...v1.9.3) (2025-12-12)


### Bug Fixes

* good ([bb63390](https://github.com/roseforljh/EveryTalk/commit/bb633903136f0d7e22bc742bd0bece57a1791dd1))
* 优化prompt ([dcd709e](https://github.com/roseforljh/EveryTalk/commit/dcd709e85679b59eb1470ca92607ef84238abd55))
* 修复了“文件大小检查”误判/漏判的问题、修复了图片缩放判断的逻辑优先级 bug、让“发送附件”真正走统一的文件链路 ([3d3243e](https://github.com/roseforljh/EveryTalk/commit/3d3243e881ec94516dd39a3590e5f7936b7cf5af))
* 修复模式切换bug ([5ed3ca3](https://github.com/roseforljh/EveryTalk/commit/5ed3ca36fdc80d053b1decad5462ba786ada3c8e))
* 数学公式转换prompt修复 ([bd99077](https://github.com/roseforljh/EveryTalk/commit/bd9907798075a99c5980cc08ffbe7b2abad8465c))
* 渲染结构始终一致 ([dd6dcde](https://github.com/roseforljh/EveryTalk/commit/dd6dcde6d8874305e314ef84278de9585f65f5d4))

## [1.9.2](https://github.com/roseforljh/EveryTalk/compare/v1.9.1...v1.9.2) (2025-12-11)


### Bug Fixes

* ai气泡动画 ([d1cb2a8](https://github.com/roseforljh/EveryTalk/commit/d1cb2a88218cd6bfcce2ba90cba856167e8bbe90))
* StreamingControls.kt：恢复时同步 StreamingMessageStateManager 累积内容到 messages 列表 ([1c3ac81](https://github.com/roseforljh/EveryTalk/commit/1c3ac8160741581141a0af550872b021ab4a3016))
* 优化多项潜在问题 ([4152872](https://github.com/roseforljh/EveryTalk/commit/41528722f38b501accb98b86062bf02dd602b93b))
* 修复潜在bug ([3ad9f92](https://github.com/roseforljh/EveryTalk/commit/3ad9f9274e817258e61c1a11c8d49ed705adb54d))
* 修复潜在问题 ([51cd368](https://github.com/roseforljh/EveryTalk/commit/51cd36808948c9e412c4099b3e614d80e19382a1))
* 图像模式滚动优化 ([a7a649e](https://github.com/roseforljh/EveryTalk/commit/a7a649e534cece8b942f997bfb89295feb9d0288))
* 图像模式默认配置bug修复 ([913a817](https://github.com/roseforljh/EveryTalk/commit/913a817a2269649b63b96ac6390c7b1fa476b6fe))
* 导入导出bug修复、语音模式模型名称bug修复 ([00f1827](https://github.com/roseforljh/EveryTalk/commit/00f18274312dd973af03fac6e8a4f3ec4a81b1d3))
* 改善卡片交互 ([4e8f415](https://github.com/roseforljh/EveryTalk/commit/4e8f415110bdb5dd82ec95b04e1ba71d3405704d))
* 数据库优化 ([d4c49af](https://github.com/roseforljh/EveryTalk/commit/d4c49af99f767da899b948d6baf74ed367b5fb62))
* 滚动效果优化 ([ef42bf3](https://github.com/roseforljh/EveryTalk/commit/ef42bf359188449a2a8f8d7dce09bceb427cd972))
* 自适应节流 (StreamingBuffer) - 初期快速响应（16ms），后期根据流速自动调整（8-100ms），避免高速流时过度重组导致 UI 卡顿，同时保证首屏体验 ([3ae8f0b](https://github.com/roseforljh/EveryTalk/commit/3ae8f0b025b1499f9f8408363ec681489eef31b0))
* 语音模式bug修复 ([ffc80e5](https://github.com/roseforljh/EveryTalk/commit/ffc80e5ef6749e1c06dc307fd990ac584c08f475))
* 重新回答动画优化 ([c6d46e2](https://github.com/roseforljh/EveryTalk/commit/c6d46e2bec6714312ed35850ce6c5fa9e3049c0c))

## [1.9.1](https://github.com/roseforljh/EveryTalk/compare/v1.9.0...v1.9.1) (2025-12-10)


### Bug Fixes

* correct python script path in build workflow ([5acdfa5](https://github.com/roseforljh/EveryTalk/commit/5acdfa5144c38bfa486e2d59114d07b383a81dd7))

## [1.9.0](https://github.com/roseforljh/EveryTalk/compare/v1.8.4...v1.9.0) (2025-12-10)


### Features

* update telegram notification style to send apk with details ([55bbf38](https://github.com/roseforljh/EveryTalk/commit/55bbf3868fcb37cbf8d1c10c750cbabd31b6bd21))
* upgrade telegram notification to send apk file with details ([246f164](https://github.com/roseforljh/EveryTalk/commit/246f1648703d77df48f370f43228b984755a6159))


### Bug Fixes

* force add github workflows and replace telegram action with curl ([a7af25f](https://github.com/roseforljh/EveryTalk/commit/a7af25fb6796108de2d04ddade7691837c96c7de))
* 修复页面抖动 ([cfef9ea](https://github.com/roseforljh/EveryTalk/commit/cfef9eadbf96c076234e01742824b9cb360f7a43))
* 减小体积 ([6166c02](https://github.com/roseforljh/EveryTalk/commit/6166c025f1da8ca6c3d11e3159ab12cea9090f17))

## [1.8.4](https://github.com/roseforljh/EveryTalk/compare/v1.8.3...v1.8.4) (2025-12-10)


### Bug Fixes

* fix something not goog code ([f8d5428](https://github.com/roseforljh/EveryTalk/commit/f8d5428bb19a85ce59ebf4f3cb5ec96e35ad45ed))
* Google工具调用 ([de7d0ea](https://github.com/roseforljh/EveryTalk/commit/de7d0ea317db18bd4b5196b1de197826fc969290))
* qwen-long原生识别文档 ([1e87d02](https://github.com/roseforljh/EveryTalk/commit/1e87d029899b1a61fcf55b48f8eff3a46cf3dc03))
* 修复代码执行bug ([224fd3b](https://github.com/roseforljh/EveryTalk/commit/224fd3bca3eb2c678e0e2976db9cc3f254b3f616))
* 更新reamde ([fde034b](https://github.com/roseforljh/EveryTalk/commit/fde034b12f0b059a0998853ef121360f61067929))
* 添加了 Telegram 通知功能 ([c1c9b86](https://github.com/roseforljh/EveryTalk/commit/c1c9b864cf0c96acc85aa82a1a4f1bb7b10ca030))
* 用户气泡不转换md格式、优化用户气泡逻辑 ([0f705fa](https://github.com/roseforljh/EveryTalk/commit/0f705fa24267abf8991daaf1be568ef1d1191f23))
* 识别逻辑严格限制为仅对 qwen-long 模型生效 ([0dd4da2](https://github.com/roseforljh/EveryTalk/commit/0dd4da2ef4e63c5785212d6bf1975460ee0d9c86))
* 适配统一原生联网搜索 ([ae2afe3](https://github.com/roseforljh/EveryTalk/commit/ae2afe3d16060380e344612cec688943a2ebf21a))
* 重新回答bug ([e39d16a](https://github.com/roseforljh/EveryTalk/commit/e39d16a4213765e4e43d75e368c7effcd6a6c015))
* 长按bug修复 ([53c0fff](https://github.com/roseforljh/EveryTalk/commit/53c0fffdebe359ab72e4bae523cf2d781d0a6831))

## [1.8.3](https://github.com/roseforljh/EveryTalk/compare/v1.8.2...v1.8.3) (2025-12-09)


### Bug Fixes

* md格式自修复增强、针对一些破垃圾狗屎模型 ([3bf8e37](https://github.com/roseforljh/EveryTalk/commit/3bf8e37ea325f03b4ab795d70992450eff661465))
* 优化代码结构 ([eb747e5](https://github.com/roseforljh/EveryTalk/commit/eb747e55097c5f0d4447ce22b5382013dfecfefe))
* 优化目录 ([07a93a4](https://github.com/roseforljh/EveryTalk/commit/07a93a43c0e67f679b8905c0740e04f1ec98a7f5))
* 优化目录 ([3e227d2](https://github.com/roseforljh/EveryTalk/commit/3e227d22279e46c302b9519b64c5f6438bd86d0a))
* 修复阿里云stt实时流式的bug ([c167e33](https://github.com/roseforljh/EveryTalk/commit/c167e33d9b1b5605f1acf46a8a4277b8beed60b8))
* 删除废弃代码 ([beade2e](https://github.com/roseforljh/EveryTalk/commit/beade2e53364f095bc7af9e37cc368b2e2afeacf))
* 加快tts输出 ([37b6ed4](https://github.com/roseforljh/EveryTalk/commit/37b6ed4f8afaf62f94e3736cc135f0166af98e6c))
* 处理 URL 中的反斜杠转义 ([9fd734f](https://github.com/roseforljh/EveryTalk/commit/9fd734f84104758faef2b83d3af476b86cbd3eec))
* 改进交互 ([d6f0453](https://github.com/roseforljh/EveryTalk/commit/d6f0453d3967dc6463bd8b7561f42b85c0e4678b))
* 文本样式优化 ([ed9b0d5](https://github.com/roseforljh/EveryTalk/commit/ed9b0d571c71a426f321c686d1acd847766821e5))
* 新代码块样式 ([f553712](https://github.com/roseforljh/EveryTalk/commit/f55371242c961ea1a43dbe2d394e20a542d9af8c))
* 没座！！！ ([e0cf574](https://github.com/roseforljh/EveryTalk/commit/e0cf574d435a273114535cc3d65c0541c2461af4))
* 直连编译默认配置常量 ([511080a](https://github.com/roseforljh/EveryTalk/commit/511080a8c8ac64b7bdaf642a2747d22c9b6e1536))
* 移除废弃代码 ([484ca3e](https://github.com/roseforljh/EveryTalk/commit/484ca3e2fb204334613bb8b4783aaccedeabfdb8))
* 语音模式修复回调 ([129450b](https://github.com/roseforljh/EveryTalk/commit/129450bcd7e846dae17c7ec9718a2e9a8d3083ad))
* 语音模式的prompt改为英文 ([6023522](https://github.com/roseforljh/EveryTalk/commit/602352227668c0b01b5d41d65e233ea648d06247))

## [1.8.2](https://github.com/roseforljh/EveryTalk/compare/v1.8.1...v1.8.2) (2025-12-09)


### Bug Fixes

* 修复缺失的环境变量 ([9a88dcc](https://github.com/roseforljh/EveryTalk/commit/9a88dccb5146edd4cbe4d6af6ed127bea6c30c02))

## [1.8.1](https://github.com/roseforljh/EveryTalk/compare/v1.8.0...v1.8.1) (2025-12-08)


### Bug Fixes

* bug fix ([ec078af](https://github.com/roseforljh/EveryTalk/commit/ec078af4bf03eade225feeaf26720ef42cb6b2dd))
* 修复即梦等模型的图像编辑能力 ([fc3eaee](https://github.com/roseforljh/EveryTalk/commit/fc3eaee3eb73b2e22e032a338fb54f3f079e0e23))
* 适配Gemini3 ([811fa92](https://github.com/roseforljh/EveryTalk/commit/811fa92ab2b89050f31d01d6b850beda7d40f73d))

## [1.8.0](https://github.com/roseforljh/EveryTalk/compare/v1.7.20...v1.8.0) (2025-12-08)


### Features

* 全局直连！ ([5b24313](https://github.com/roseforljh/EveryTalk/commit/5b24313845a9dc2721900aabb048338fafd7b7a5))


### Bug Fixes

* release编译修复、删除废弃代码 ([5f02f1f](https://github.com/roseforljh/EveryTalk/commit/5f02f1ff6fe74a2e8f27f95f71a491b913210a46))
* 编译 ([29b06c8](https://github.com/roseforljh/EveryTalk/commit/29b06c8188b48ed425037bcf5b2287bb61ab6308))
* 语音模式minimmax终极优化 ([d89f99e](https://github.com/roseforljh/EveryTalk/commit/d89f99e97e152772e479224792316650218e6f2e))

## [1.7.20](https://github.com/roseforljh/EveryTalk/compare/v1.7.19...v1.7.20) (2025-12-07)


### Bug Fixes

* room ([8764c07](https://github.com/roseforljh/EveryTalk/commit/8764c079da9ef6bf896011f83301acad2f2ba5a2))
* STT/TTS 直连客户端，以优化延迟 ([46d458f](https://github.com/roseforljh/EveryTalk/commit/46d458f6118e653cba0fbfc268dacf92903c52f2))
* 修复设置按钮按钮的导航逻辑 ([0359433](https://github.com/roseforljh/EveryTalk/commit/03594335bbc21945af01d4d774041551cc1d1354))
* 升级数据库结构 ([acb0b8d](https://github.com/roseforljh/EveryTalk/commit/acb0b8dc64891aa4dce23747bd87092d610d71a7))
* 改善语音模式llm的prompt ([cd0b2c1](https://github.com/roseforljh/EveryTalk/commit/cd0b2c1813ae2e02bb53a6b5d0b5c8883a690cf3))
* 移除孤儿文件清理机制 ([609b736](https://github.com/roseforljh/EveryTalk/commit/609b7366054432edc8f67d63d0b2bb855ccc8f9a))
* 阿里系超低延迟 ([3fbed3c](https://github.com/roseforljh/EveryTalk/commit/3fbed3cbf9bc0530a55b1df413dca1120dcc3e2d))

## [1.7.19](https://github.com/roseforljh/EveryTalk/compare/v1.7.18...v1.7.19) (2025-12-07)


### Bug Fixes

* bug fix ([ff9e802](https://github.com/roseforljh/EveryTalk/commit/ff9e8024532c2dc737e74f3995115ee9214d35e4))
* 优化网络 ([b3d20f8](https://github.com/roseforljh/EveryTalk/commit/b3d20f80ff48b64138161cf9ad33e075add61a53))

## [1.7.18](https://github.com/roseforljh/EveryTalk/compare/v1.7.17...v1.7.18) (2025-12-07)


### Bug Fixes

* 加快阿里云tts输出首字的速度 ([9ed2f47](https://github.com/roseforljh/EveryTalk/commit/9ed2f470eaa74d0cee5464dd06e190a2a2519b48))
* 可能修复了持久化问题 ([fb4fbfe](https://github.com/roseforljh/EveryTalk/commit/fb4fbfe77cd567d3d213a0db1bfbd1a6897770be))
* 图像模式两个默认模型的持久化问题修复 ([d5a1ab1](https://github.com/roseforljh/EveryTalk/commit/d5a1ab1828f3cd380428bbea889ef9bbe8306f97))
* 图片持久化的问题 ([c7f6111](https://github.com/roseforljh/EveryTalk/commit/c7f6111659c6574857a67fc1fa8c123de00bb7a3))
* 适配fun-asr ([f2ce72f](https://github.com/roseforljh/EveryTalk/commit/f2ce72f212be2b1b2cfcdcd60a8611db08d234d3))
* 适配阿里云tts ([d982757](https://github.com/roseforljh/EveryTalk/commit/d982757493d6e37bc4671fd3dcd230f0dcab9a48))
* 阿里云stt实时输出 ([8171fff](https://github.com/roseforljh/EveryTalk/commit/8171fffb75e6003d746723b4bcde8d5aa2b0ed28))

## [1.7.17](https://github.com/roseforljh/EveryTalk/compare/v1.7.16...v1.7.17) (2025-12-04)


### Bug Fixes

* 优化ui ([fd21316](https://github.com/roseforljh/EveryTalk/commit/fd2131687a2c275e52b25b44e94dfda64935ca01))
* 加大步数 ([7d91417](https://github.com/roseforljh/EveryTalk/commit/7d914170f32601fe90b5af69390b71ff6544e599))
* 增加图像模式超时时间限制 ([5d00aae](https://github.com/roseforljh/EveryTalk/commit/5d00aae8b62eebdae3ebe381955a6124798f3add))

## [1.7.16](https://github.com/roseforljh/EveryTalk/compare/v1.7.15...v1.7.16) (2025-12-04)


### Bug Fixes

* 查看文本bug修复 ([4699daf](https://github.com/roseforljh/EveryTalk/commit/4699dafb2f373d898c652a50f1ff24cc61526391))
* 统一导入导出逻辑 ([eb24503](https://github.com/roseforljh/EveryTalk/commit/eb24503ba515ee8281f5dd4095562305142a13fe))
* 美化UI ([0dc4953](https://github.com/roseforljh/EveryTalk/commit/0dc495333220f063094570adecaca24a021e55e5))
* 自定义z-image步数 ([93e60ba](https://github.com/roseforljh/EveryTalk/commit/93e60baf24fa427ca86e723a817ac0a38e7754ca))

## [1.7.15](https://github.com/roseforljh/EveryTalk/compare/v1.7.14...v1.7.15) (2025-12-03)


### Bug Fixes

* bug ([e5626ab](https://github.com/roseforljh/EveryTalk/commit/e5626ab8882170bdd6c063b4981468fefd7b1046))

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
