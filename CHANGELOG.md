# Changelog

## [1.22.0](https://github.com/roseforljh/EveryTalk/compare/v1.21.0...v1.22.0) (2026-07-24)


### Features

* LLM客户端新增 PromptCachePolicy 能力指纹日志 ([86cb576](https://github.com/roseforljh/EveryTalk/commit/86cb576a575ce1a2410462d9f61a43102d116ba6))
* loading/连接状态统一添加实时耗时显示 ([bdea675](https://github.com/roseforljh/EveryTalk/commit/bdea67588d2d9ffc74d128d4b9f8a546b458d473))
* Markdown 链接 Logo — 链接旁显示 favicon，自定义链接颜色去下划线 ([f1a55da](https://github.com/roseforljh/EveryTalk/commit/f1a55da43d2f1e3cf2243930e61ce540d4548574))
* 完成态AI消息惰性展开为Markdown节点项 ([9dfd420](https://github.com/roseforljh/EveryTalk/commit/9dfd420b0fbc59e48f54894de091dada24eeb2e4))


### Bug Fixes

* Markdown表格单元格设置maxLines=Int.MAX_VALUE防止文本被截断 ([de08aa5](https://github.com/roseforljh/EveryTalk/commit/de08aa5b1b558cf351d8334c9384b34cdab548db))
* TopAnchor 哈希纳入 item 顺序/锚点丢失后按索引重新捕获 ([de8b5ae](https://github.com/roseforljh/EveryTalk/commit/de8b5ae2cf88c4f936c3d866fa922eba4fce6148))
* TopAnchor引擎重答阶段与用户操控阶段鲁棒性增强 ([2705e4d](https://github.com/roseforljh/EveryTalk/commit/2705e4df28009b127f0cfe082b8f8cddb8dccff4))
* 代码块初始异步高亮期间抑制resize动画 ([b6db15e](https://github.com/roseforljh/EveryTalk/commit/b6db15e4d5d57c6fc915571c352ab17ba9829600))
* 初始加载滚动拆分为两阶段初始化 ([02f6eb7](https://github.com/roseforljh/EveryTalk/commit/02f6eb78906fb4275e5997ee5e5b3a0f00ad567b))
* 功能面板弹入动画优化/PageSourcesButton解耦 ([2e0f16c](https://github.com/roseforljh/EveryTalk/commit/2e0f16cfa24a55513c3f764da1880232e4842680))
* 底部守护根据 canScrollForward 修正零剩余距离误判 ([a37d02d](https://github.com/roseforljh/EveryTalk/commit/a37d02d9e347a1f02f5929211e9e9fe507684bbd))
* 流式Markdown渲染优化 - rebase触发改进与非流式结束原子校准 ([7285e95](https://github.com/roseforljh/EveryTalk/commit/7285e95b4e1daa4ef021149e9fb9026ad2454d56))
* 清理模型误输出到正文的能力选择卡 ([70e5a16](https://github.com/roseforljh/EveryTalk/commit/70e5a161075ea8af52c3a8433a06aa0c037176b9))
* 重新生成时原地替换用户消息防止LazyColumn key中断 ([bcf67cb](https://github.com/roseforljh/EveryTalk/commit/bcf67cbc5c6264eb1658f152f0173cf1d77b5767))


### Performance Improvements

* 数学公式首帧同步恢复cache/水平滚动惰性绑定 ([5158075](https://github.com/roseforljh/EveryTalk/commit/5158075dfd8677a1bf07242491b470d3c3c7cd9d))

## [1.21.0](https://github.com/roseforljh/EveryTalk/compare/v1.20.3...v1.21.0) (2026-07-20)


### Features

* ApiHandler 图片流处理与 ClipboardController 优化 ([f435f8c](https://github.com/roseforljh/EveryTalk/commit/f435f8ced3c77dcbd84f582b93c5a1b0390be41a))
* ChatMessagesList UI 与测试补充 ([1c25a4b](https://github.com/roseforljh/EveryTalk/commit/1c25a4b6c3172c71a7dedc6c923836ff2f4f6b7a))
* MikePenzMarkdownRenderer 与 MessageProcessor 优化 ([52c3da3](https://github.com/roseforljh/EveryTalk/commit/52c3da3af93da775490ed5346fe959f841f8a488))
* MikePenzMarkdownRenderer 渲染增强 ([eeec7ff](https://github.com/roseforljh/EveryTalk/commit/eeec7ff31a71af30f6094c76b4baec6391c5846c))
* TopAnchorReserveEngine 增强与测试扩展 ([1baebda](https://github.com/roseforljh/EveryTalk/commit/1baebda8c8bdc8eb76af77c5d781cc8f3f48bdd5))
* 优化 BubbleContentTypes 气泡内容与 ChatScrollStateManager 滚动状态 ([2ab56c3](https://github.com/roseforljh/EveryTalk/commit/2ab56c3bbb56b7e26e2ecebe9f8d96281434c1c8))
* 优化 ChatScreen UI 与滚动交互 ([d3a3c14](https://github.com/roseforljh/EveryTalk/commit/d3a3c143c784d35c6c57cd49c43e55c64a5713dd))
* 优化 ChatScreen 与 BubbleContentTypes 渲染 ([d26fcc8](https://github.com/roseforljh/EveryTalk/commit/d26fcc8c35c1f11bee5bbdc8f8d9f83d5c182c71))
* 优化 ChatScreen 与 ChatMessagesList UI ([c632cf0](https://github.com/roseforljh/EveryTalk/commit/c632cf0427e4fabdf157fba52b7455543107202a))
* 升级全部技术栈并统一渲染链路 ([97c3bfd](https://github.com/roseforljh/EveryTalk/commit/97c3bfd989e1d9f0a228f0ec9d32eeeb0dc12f92))
* 增强 Markdown 渲染器图片与表格支持 ([13fa768](https://github.com/roseforljh/EveryTalk/commit/13fa768efa490d5c4e928a210ed44a243104233b))
* 增强 Markdown 渲染核心与流式解析能力 ([9124e2e](https://github.com/roseforljh/EveryTalk/commit/9124e2ebeaf3290909ec694509b3c45761324ee1))
* 增强 Markdown/Math/Streaming 渲染层 ([608095f](https://github.com/roseforljh/EveryTalk/commit/608095fe5a31e3ebcf17ccc6f11e7cabe731ac42))
* 实现 Streaming 流式暂停机制与状态管理重构 ([9e7ea2e](https://github.com/roseforljh/EveryTalk/commit/9e7ea2e08ae39fa3a9c2343d75f6ae901bdfe970))
* 新增 EveryTalkLoadingIndicator 并优化渲染配置 ([4d08e31](https://github.com/roseforljh/EveryTalk/commit/4d08e31b82863eb40dc219c4fd564d5685a8a0e1))
* 新增图片消息模型与 ImagePreviewSelection 组件 ([be0bf25](https://github.com/roseforljh/EveryTalk/commit/be0bf251967dc48d8e133ffdf835f7c29293cb41))
* 统一 Markdown 与 MathJax 渲染链路 ([5a9b7de](https://github.com/roseforljh/EveryTalk/commit/5a9b7de00e1be14098a7c91c326361c769cedf6d))
* 统一 Markdown 渲染为 MikePenz 与 KaTeX ([c54057e](https://github.com/roseforljh/EveryTalk/commit/c54057ea47431ff99591e32a16596030dc0a8640))
* 迁移当前改动到 like-gpt 分支 ([59497fe](https://github.com/roseforljh/EveryTalk/commit/59497feb54f6aa3f325c6b9f0693720053570092))


### Bug Fixes

* ChatScrollStateManager 未暂存修改 ([ca275fe](https://github.com/roseforljh/EveryTalk/commit/ca275fe1b2571f0f82a83be98dea2457edab9eef))
* HistoryManager 避免无变更时的重复保存 ([6dca7a5](https://github.com/roseforljh/EveryTalk/commit/6dca7a5916dd316621f4c54d8aeb433127500ee3))
* MathJax 资产校验增加换行符归一化处理 ([269997a](https://github.com/roseforljh/EveryTalk/commit/269997abee106caf848379bef91fd931c39b4662))
* MikePenzMarkdownRenderer 与 MathRenderer 渲染优化 ([051cc9f](https://github.com/roseforljh/EveryTalk/commit/051cc9f5185ff52bbf85fbb38c4f8769934fae14))
* TopAnchorReserveEngineComposeTest 增加等待避免断言竞争 ([d4be8b4](https://github.com/roseforljh/EveryTalk/commit/d4be8b4dd551030821db259e6805d72602b501a0))

## [1.20.3](https://github.com/roseforljh/EveryTalk/compare/v1.20.2...v1.20.3) (2026-06-16)


### Bug Fixes

* improve web preview error handling ([6aac2df](https://github.com/roseforljh/EveryTalk/commit/6aac2dfe7d8775fc8c182331debdc97f0f2167cd))
* 修复代码预览长按复制崩溃 ([2673d57](https://github.com/roseforljh/EveryTalk/commit/2673d57c7bd5905c0991ee131545a76f80dbb9f8))
* 对齐图像模式加载指示器样式 ([1f48876](https://github.com/roseforljh/EveryTalk/commit/1f488763dd623f5d2f700d2c842fa19471750748))
* 对齐图像模式加载指示器样式，公开 LoadingStageIndicator 可见性 ([6e5c798](https://github.com/roseforljh/EveryTalk/commit/6e5c7980514c10da5fcd1578a79aa9d9187f7522))
* 对齐图像模式输入框亮色主题样式与动画 ([c8cc51a](https://github.com/roseforljh/EveryTalk/commit/c8cc51ab4491ac405cbf3bfdb19ceb38242b7780))

## [1.20.2](https://github.com/roseforljh/EveryTalk/compare/v1.20.1...v1.20.2) (2026-06-10)


### Bug Fixes

* harden app runtime, protocol, and build reliability ([9ec4200](https://github.com/roseforljh/EveryTalk/commit/9ec420013826e7e3f06948b0aa67aeb5eb567acf))
* keep light input plus button borderless ([fe3fc56](https://github.com/roseforljh/EveryTalk/commit/fe3fc56731fa7ca3b62c2a1d1ffe3846bb803ab7))
* merge remote release update before push ([44f581c](https://github.com/roseforljh/EveryTalk/commit/44f581cf3a850e380fe022bd0c69cab743989828))
* stabilize chat progress and sanitize stream text ([f44aaef](https://github.com/roseforljh/EveryTalk/commit/f44aaefc79347ed8769977338d0aaf8919a03a55))

## [1.20.1](https://github.com/roseforljh/EveryTalk/compare/v1.20.0...v1.20.1) (2026-06-07)


### Bug Fixes

* adjust web sources dialog spacing ([8d42b9d](https://github.com/roseforljh/EveryTalk/commit/8d42b9d10a194961521984a83b0b40accf1b74a7))
* refine scroll button behavior ([cdb3704](https://github.com/roseforljh/EveryTalk/commit/cdb37045ea020b3217dc62b219cfbfe0bb4e96ef))
* reset code block scroll after streaming ([80b1c75](https://github.com/roseforljh/EveryTalk/commit/80b1c75fd2543d402a6d155d197c1ac32a1ea47e))
* 优化MCP/搜索标签胶囊样式、会话参数及系统提示对话框UI与IME滚动 ([5c1530b](https://github.com/roseforljh/EveryTalk/commit/5c1530bbcec22f352a914da998096a8694ed7625))
* 优化代码块底部渐变逻辑 ([e35a989](https://github.com/roseforljh/EveryTalk/commit/e35a98993c11634c40e152fa8407bdc3e9c14e6d))
* 优化代码块流式滚动 ([f85f2ed](https://github.com/roseforljh/EveryTalk/commit/f85f2ed663b8dd09b07e1f5b8f9ee1b347a2f4a5))
* 优化代码预览退出动画 ([c59a425](https://github.com/roseforljh/EveryTalk/commit/c59a4252ab1aca443f38dbe2d97595d979c277c2))
* 优化回到底部按钮显示 ([74e5430](https://github.com/roseforljh/EveryTalk/commit/74e54304b399e553ef58402afe9d9c55e3e248a9))
* 优化用户气泡和历史排序 ([e2ab31c](https://github.com/roseforljh/EveryTalk/commit/e2ab31c7f879835aed48b2c73981315c35997c31))
* 修复暗色预览表格点击态对比度 ([e74955a](https://github.com/roseforljh/EveryTalk/commit/e74955aacf55fa8d5fe12609ca2da63e552388bd))
* 修复用户气泡滚动锚点 ([5890e18](https://github.com/roseforljh/EveryTalk/commit/5890e1893c5b225e11232eef5e6f27769cd4e467))

## [1.20.0](https://github.com/roseforljh/EveryTalk/compare/v1.19.7...v1.20.0) (2026-06-07)


### Features

* add swipeable code preview transitions ([38dac4d](https://github.com/roseforljh/EveryTalk/commit/38dac4d0e4ad6f09d433f20ff0de565c7fa92885))
* refine ChatGPT style code block UI and fullscreen preview ([8c7d2e7](https://github.com/roseforljh/EveryTalk/commit/8c7d2e762ada6d9e6ea0076d9f755c03e371bbd6))


### Bug Fixes

* optimize image mode interactions ([10e42dc](https://github.com/roseforljh/EveryTalk/commit/10e42dca5724c103ff3203f95f8a445995334a6e))
* pass current preview mode state to FullScreenCodeViewerDialog ([39f478c](https://github.com/roseforljh/EveryTalk/commit/39f478c78ed0dd8a7a66064ae19fdbc7db040a73))
* polish code preview tab interactions ([c37f5e2](https://github.com/roseforljh/EveryTalk/commit/c37f5e29c97780027a7026b322748555fd2ac03c))
* refine code preview theming and transitions ([0187fdb](https://github.com/roseforljh/EveryTalk/commit/0187fdb618595e9ccc6458fc9d71689f9b94a8a7))
* resize splash logo ([5de1baf](https://github.com/roseforljh/EveryTalk/commit/5de1baf4bf29a6573a5429799e7e6c90421c5364))
* use system splash logo ([5e173ac](https://github.com/roseforljh/EveryTalk/commit/5e173ac1da6fb23c2aeaed7e4ec3fa1ea77ea286))

## [1.19.7](https://github.com/roseforljh/EveryTalk/compare/v1.19.6...v1.19.7) (2026-06-06)


### Bug Fixes

* adjust markdown spacing for nested lists and headings ([0c4d419](https://github.com/roseforljh/EveryTalk/commit/0c4d4197bffcc064ac2c2c68ec17f5bde484a2e9))
* align chat text edges with justified break strategy ([c3d4f2c](https://github.com/roseforljh/EveryTalk/commit/c3d4f2cfa1368274092cf3ea67f456289b76e2d5))
* apply text metrics via descent/ascent to restore proper body line height ([606815f](https://github.com/roseforljh/EveryTalk/commit/606815f210e59df2b078b0555c6deecf79fa5b65))
* customize diffuse shadows for ChatInputArea and plus button in light theme ([2288e37](https://github.com/roseforljh/EveryTalk/commit/2288e379e145a940850c5b3e3b3b6eebf36da6a9))
* optimize chat markdown rendering ([8a8da0c](https://github.com/roseforljh/EveryTalk/commit/8a8da0c2d2bfe77087f7fd0a1f1fb298ae39e0c7))
* polish chat rendering and launcher icon ([ef906f3](https://github.com/roseforljh/EveryTalk/commit/ef906f3eaa76444ad5adf0084954806cc7751b31))
* polish chat rendering and top controls ([4f30c10](https://github.com/roseforljh/EveryTalk/commit/4f30c10500fede0bfd30cdb89373ca2a7e85b035))
* polish chat sources and launcher resources ([05b9e80](https://github.com/roseforljh/EveryTalk/commit/05b9e80d48325aebc7f56954d9b5a2fb91d5aae3))
* refine vector launcher and splash logo ([6275dba](https://github.com/roseforljh/EveryTalk/commit/6275dbaa44484be489923890d1e54d576bed96b9))
* refine web sources dialog ([73ab3d7](https://github.com/roseforljh/EveryTalk/commit/73ab3d7aa217b2a938326d482f53eb1c16b85f88))
* restore input borders and splash resources ([5b04eb4](https://github.com/roseforljh/EveryTalk/commit/5b04eb49aebb3332c26972e47379431c2f36d0ce))
* tighten nested markdown list spacing ([53493f5](https://github.com/roseforljh/EveryTalk/commit/53493f5166cfede4c815a9a30b72bd1ac282a79f))
* update launcher and splash icon ([71703cc](https://github.com/roseforljh/EveryTalk/commit/71703cca628a2e7262bddfdf10d46981d3289862))
* 避免重复点击历史项卡住加载态 ([324fae7](https://github.com/roseforljh/EveryTalk/commit/324fae73306dccc5b699763d08913511c95e7ada))

## [1.19.6](https://github.com/roseforljh/EveryTalk/compare/v1.19.5...v1.19.6) (2026-06-04)


### Bug Fixes

* optimize chat markdown rendering ([cc8575d](https://github.com/roseforljh/EveryTalk/commit/cc8575df3a352e6adbccc48cb3f5be3f932253ac))
* refresh markdown layout on stream completion ([3b74248](https://github.com/roseforljh/EveryTalk/commit/3b742482156c187e7703fea24281d544278ee2cb))
* stabilize markdown link touch handling ([9eac34a](https://github.com/roseforljh/EveryTalk/commit/9eac34a9b647bc08151e5fda4cbd565c80cb3000))
* 修复重答定位与日志脱敏 ([81c7481](https://github.com/roseforljh/EveryTalk/commit/81c7481de079c038e384131e0531f1328d91b6da))

## [1.19.5](https://github.com/roseforljh/EveryTalk/compare/v1.19.4...v1.19.5) (2026-05-30)


### Bug Fixes

* append regenerated turns and constrain image preview ([c9d235e](https://github.com/roseforljh/EveryTalk/commit/c9d235e4c2ca9de8d40e322c9f64b648613597b7))
* optimize dialog and image preview controls ([ae88b9d](https://github.com/roseforljh/EveryTalk/commit/ae88b9d2f221cb98e8129a7d90814a83a3baee26))
* preserve follow-up turns on regenerate ([e2bb3ab](https://github.com/roseforljh/EveryTalk/commit/e2bb3aba453423b9979e393c872fd7de3ec82516))
* refine markdown tables and model styling ([25bcbf0](https://github.com/roseforljh/EveryTalk/commit/25bcbf09095fff08b92542da0adee0dfb65f7007))
* 修复价格表格流式渲染 ([93ae4e6](https://github.com/roseforljh/EveryTalk/commit/93ae4e6a3c51d92d179da8be1bc2f743f0f64cf6))

## [1.19.4](https://github.com/roseforljh/EveryTalk/compare/v1.19.3...v1.19.4) (2026-05-29)


### Bug Fixes

* add history loading skeleton bubbles ([f8f5e8c](https://github.com/roseforljh/EveryTalk/commit/f8f5e8c7c2eb98061d5ff7e3797d0424e433845e))
* align drawer history reset with gpt logic ([f2ec70c](https://github.com/roseforljh/EveryTalk/commit/f2ec70cf4b30d84d5be9b75ce15827234459830f))
* align enter-conversation scroll behavior with GPT ([0a30def](https://github.com/roseforljh/EveryTalk/commit/0a30def4434650bc44cd1c10c9099d55d36c1537))
* improve chat markdown rendering fidelity ([1718ca6](https://github.com/roseforljh/EveryTalk/commit/1718ca6a4c458499e1d94939faf1a73bbcbef435))
* optimize HTML code block preview layout and responsiveness ([44456b4](https://github.com/roseforljh/EveryTalk/commit/44456b498154c33595a6bd54364c369268212ed3))
* P2 streaming render optimizations - plain text Compose rendering and incremental parsing ([02bbb03](https://github.com/roseforljh/EveryTalk/commit/02bbb034c9de2e1203c87e52f3da257f6fcb1480))
* P3 streaming optimizations - delta callback and Compose-native inline markdown rendering ([c9874f1](https://github.com/roseforljh/EveryTalk/commit/c9874f18d5f288d9cb4762f9ff265c67e2c46903))
* refactor empty chat PillCard styles to have thinner borders and transparent background ([703da0d](https://github.com/roseforljh/EveryTalk/commit/703da0d7491cfc293b221dba39e8d3fd6a9ea102))
* replace history loading spinner with skeleton pulse animation ([29ec540](https://github.com/roseforljh/EveryTalk/commit/29ec540ce71336eb8cddda24f8a01ce599b9d523))
* restore ai bubble text selection ([bcc6a58](https://github.com/roseforljh/EveryTalk/commit/bcc6a58fd35e41ce48bda78c0e1db134bad88690))
* 修复流式表格渲染路由 ([7e79081](https://github.com/roseforljh/EveryTalk/commit/7e790816ff0ccd891ae870131bcf0e13ee796fa6))
* 统一对话框样式与复制图标 ([f88577d](https://github.com/roseforljh/EveryTalk/commit/f88577d3c4801b911a0adbc1d50a59818a89c7fb))

## [1.19.3](https://github.com/roseforljh/EveryTalk/compare/v1.19.2...v1.19.3) (2026-05-28)


### Bug Fixes

* align release-please manifest config ([997fddf](https://github.com/roseforljh/EveryTalk/commit/997fddff80e578b429457782125783bda7aa7cbd))
* reduce streaming completion re-rendering ([bd339b8](https://github.com/roseforljh/EveryTalk/commit/bd339b8af4c704e4574bcd58172303a700a347f4))
* restore AI message text selection ([4de3bb0](https://github.com/roseforljh/EveryTalk/commit/4de3bb0c8cc2fe197f08be477e6d7233bc8236b6))
* use release-please simple mode ([bc738b1](https://github.com/roseforljh/EveryTalk/commit/bc738b1f18bebc7083dcc4a95c7eb9ea28abdc4d))

## [1.19.2](https://github.com/roseforljh/EveryTalk/compare/v1.19.1...v1.19.2) (2026-05-21)


### Bug Fixes

* redesign empty chat view with compact pill cards and updated title ([49d7047](https://github.com/roseforljh/EveryTalk/commit/49d704796671975b2079af4b14ea1b988ed3d850))

## [1.19.1](https://github.com/roseforljh/EveryTalk/compare/v1.19.0...v1.19.1) (2026-05-21)


### Bug Fixes

* remove default config card from text mode settings page ([81f7528](https://github.com/roseforljh/EveryTalk/commit/81f75281e64284a85f288ed7d55f70aba0335d4a))

## [1.19.0](https://github.com/roseforljh/EveryTalk/compare/v1.18.2...v1.19.0) (2026-05-21)


### Features

* add glass-morphic quick action cards to empty chat home screen with keyboard animation ([5eb726e](https://github.com/roseforljh/EveryTalk/commit/5eb726eac45c96ce079460bb691a96944aee9604))


### Bug Fixes

* ConfigSwitchPopup model selection dismiss flash ([e286a06](https://github.com/roseforljh/EveryTalk/commit/e286a06134e135d72e5d95e1ff288d3c69c0c420))
* optimize voice selection scroll view area and overlays ([032c394](https://github.com/roseforljh/EveryTalk/commit/032c3944ea7dd1769e99bbb3017254120dee2e48))
* preserve regenerate conversation identity ([940e980](https://github.com/roseforljh/EveryTalk/commit/940e9807d6412b0a6c87d9c3683baab421fdb229))
* unify voice dialog styling ([aac436a](https://github.com/roseforljh/EveryTalk/commit/aac436aceae09e763c76c99ab8c7ea33a512abf0))

## [1.18.2](https://github.com/roseforljh/EveryTalk/compare/v1.18.1...v1.18.2) (2026-05-20)


### Bug Fixes

* 修复内联围栏代码块导致 TableAwareText 无限递归 OOM ([05752d1](https://github.com/roseforljh/EveryTalk/commit/05752d114de7070d8e1446868bd8afd6eccb7171))

## [1.18.1](https://github.com/roseforljh/EveryTalk/compare/v1.18.0...v1.18.1) (2026-05-18)


### Bug Fixes

* claude是世界上最垃圾、最司马的模型 ([615656b](https://github.com/roseforljh/EveryTalk/commit/615656b019cab3020530e7f134724f62720fd4e3))
* 优化编辑配置对话框遮罩效果 ([e3718cf](https://github.com/roseforljh/EveryTalk/commit/e3718cf09240a668b9d7c1511bd38ae9e123b08d))
* 修复历史切换后 AI 回复丢失 ([62184c7](https://github.com/roseforljh/EveryTalk/commit/62184c70934edfddb8f33ce686d0eb334321ee83))
* 切换回话消除底部空白 ([f17f875](https://github.com/roseforljh/EveryTalk/commit/f17f875294b56c8df308de67717ac7f22b587374))
* 完美 ([4bf2d31](https://github.com/roseforljh/EveryTalk/commit/4bf2d3155c0c98abeeefe191882b88c120cb772e))
* 完美置顶 ([9dbf85a](https://github.com/roseforljh/EveryTalk/commit/9dbf85a213d993a7b7494d0042b5ef4061245aad))
* 对齐图像模式置顶滚动 ([cf24b6a](https://github.com/roseforljh/EveryTalk/commit/cf24b6a1bc30e7ea6b54628100a535771fe52f9c))
* 新增codex渠道 ([ce50d2f](https://github.com/roseforljh/EveryTalk/commit/ce50d2f10378a891ecced55f53e59ba016350e72))
* 无敌的寂寞 ([2020c86](https://github.com/roseforljh/EveryTalk/commit/2020c863706d80157fd26f0a393c82fecc796f54))
* 移除气泡置顶上滑强制消费 ([8558c35](https://github.com/roseforljh/EveryTalk/commit/8558c3501e64c0f6f68716c2d8f16c3c8bbfbb5c))
* 编辑配置对话框键盘弹出时按钮不再溢出框外 ([15fb3d5](https://github.com/roseforljh/EveryTalk/commit/15fb3d5600b4bcda894fdc06bff8941a584ca1bd))
* 顶栏按钮圆形按压效果及模型选择卡片样式统一 ([94036b6](https://github.com/roseforljh/EveryTalk/commit/94036b63586760a1985ee680e493f8e79e85562b))
* 顶级前端设计 ([e2a9150](https://github.com/roseforljh/EveryTalk/commit/e2a9150f106a1769d65d2c8dc76b6d962873e49c))

## [1.18.0](https://github.com/roseforljh/EveryTalk/compare/v1.17.1...v1.18.0) (2026-05-18)


### Features

* UI improvements - custom icons, function panel positioning, system prompt border, input animation ([e78c063](https://github.com/roseforljh/EveryTalk/commit/e78c06331e08a242eda11a9228712e85f940ae10))
* 前端大升级 ([b4cc58c](https://github.com/roseforljh/EveryTalk/commit/b4cc58cf29db7d9ebf1b469384c60eb390c35a14))


### Bug Fixes

* 上学的时候抄学霸作业，工作了抄大厂代码01 ([fb01b10](https://github.com/roseforljh/EveryTalk/commit/fb01b10ec5added8d19757c1f0169ae60850cd45))
* 上学的时候抄学霸作业，工作了抄大厂代码02 ([ea86df8](https://github.com/roseforljh/EveryTalk/commit/ea86df87680f4c2c671eebf0a5a202ec355e5adb))
* 上学的时候抄学霸作业，工作了抄大厂代码03 ([8307270](https://github.com/roseforljh/EveryTalk/commit/83072702b483f36188823b541334885a54f70acf))
* 上学的时候抄学霸作业，工作了抄大厂代码04 ([c4ace9c](https://github.com/roseforljh/EveryTalk/commit/c4ace9c2751a68b73c6f25a3db5b5521ab2b5026))
* 修复 Gemini 思考内容回收 ([fdff339](https://github.com/roseforljh/EveryTalk/commit/fdff339bf4b5278463e7204c85b91535463646ff))

## [1.17.1](https://github.com/roseforljh/EveryTalk/compare/v1.17.0...v1.17.1) (2026-05-15)


### Bug Fixes

* gpt-image-2图像编辑 ([9461dd2](https://github.com/roseforljh/EveryTalk/commit/9461dd29659fb092fed338c79cd6366de6334352))
* 优化图像模式输入框区域 ([8b7ced5](https://github.com/roseforljh/EveryTalk/commit/8b7ced57a9d2b353cb70a3c1600ab3f3c4d0bb97))
* 优化图像配置模型获取流程 ([db48ff2](https://github.com/roseforljh/EveryTalk/commit/db48ff275fec3ee75404dc5a7ca76c8d401fa619))
* 修复 Gemini 兼容流结束误报 ([92b9c61](https://github.com/roseforljh/EveryTalk/commit/92b9c61faa4ffd7c36d8f032e2143d41a53656bb))
* 删除所有默认免费模型 ([b735983](https://github.com/roseforljh/EveryTalk/commit/b735983797771b187c71ae4b42c19b6980cb6929))
* 参数补充 ([cffebcd](https://github.com/roseforljh/EveryTalk/commit/cffebcd70de0a5a76603489a88234816ad702c38))
* 图片预览多图滑动+缩放+动画；文本模式图片预览对齐；图像模式加载动画文本滚动 ([a8bcc6f](https://github.com/roseforljh/EveryTalk/commit/a8bcc6f5c003e0ff9d44a48a4332fc3b82862faa))
* 图片预览支持全会话滑动、双指缩放、边界约束、丝滑动画；GPT-IMAGE编辑auto不传size ([25b4635](https://github.com/roseforljh/EveryTalk/commit/25b463547cb083ab23b0ad59d96c31a50fd11d59))
* 文本模式图片操作优化、图像模式置顶逻辑优化 ([91400e0](https://github.com/roseforljh/EveryTalk/commit/91400e06bad609f5255b163ce40d23dab662351b))
* 适配了gpt-iamge-2模型 ([9ed3664](https://github.com/roseforljh/EveryTalk/commit/9ed36644dc89d39165a11c9056b1a3af73cb306d))

## [1.17.0](https://github.com/roseforljh/EveryTalk/compare/v1.16.1...v1.17.0) (2026-05-12)


### Features

* webfetch自动提取网页图片并传递给LLM进行多模态分析 ([518b52f](https://github.com/roseforljh/EveryTalk/commit/518b52fd35eec5c8a8eb18aa1ca89ea2575b279a))
* WebFetch自部署支持 + Jina Search联网搜索兜底 + 错误信息脱敏 ([7d10f73](https://github.com/roseforljh/EveryTalk/commit/7d10f7393326710afaab1fc9cf4039cb17260f6b))


### Bug Fixes

* frame loop 统一控制 pinned 模式下的 drift 修正和 spacer 缩减 ([76f349c](https://github.com/roseforljh/EveryTalk/commit/76f349cacac575b1e13b6511e568b17c82400866))
* pinned 期间禁止用户手动滚动 ([dd0dccc](https://github.com/roseforljh/EveryTalk/commit/dd0dccce2d6182d9364462a51c13fa7879cb81a5))
* spacer 缩减改为 gap 计算，消除底部多余空白 ([c316ae2](https://github.com/roseforljh/EveryTalk/commit/c316ae239cb5e33fadbceb062160d0f0c5655657))
* 优化 pinned scroll 帧循环功耗和 API 结束动画 ([e8b08e0](https://github.com/roseforljh/EveryTalk/commit/e8b08e0b997a2a32fd4d865ffe9c619bc5a8d365))
* 修复AI气泡流式输出时滑动导致高度坍塌 & MCP候选选择器逻辑 ([35ace6b](https://github.com/roseforljh/EveryTalk/commit/35ace6b5cfd7a1f5d6cab0bcb1dc44ae362f42a7))
* 修复AI气泡流式输出时高度坍塌与内容跳转bug ([00ce7b6](https://github.com/roseforljh/EveryTalk/commit/00ce7b67318f787d547b3f505ce953579a7c04bc))
* 修复切换会话底部空白 + AI气泡内容消失 + 流式结束重组 ([f72e13e](https://github.com/roseforljh/EveryTalk/commit/f72e13ebd0f5264984198fbbd0370340fa2680a7))
* 修复思考框收起时用户置顶气泡下落问题 ([5648376](https://github.com/roseforljh/EveryTalk/commit/5648376de5a7c4d4e49ef461207d6f7895fdc6ea))
* 工具调用多项修复 - MCP schema序列化/链接点击/气泡高度/表格解析 ([5875e4f](https://github.com/roseforljh/EveryTalk/commit/5875e4fe526e74fa709d5391a5c49f46aadf5dae))
* 移除 LaunchedEffect(scrollSessionKey) 中的滚动恢复逻辑 ([10ad713](https://github.com/roseforljh/EveryTalk/commit/10ad7139c4adfc4f1722cea3c1d87cc9f193b97c))

## [1.16.1](https://github.com/roseforljh/EveryTalk/compare/v1.16.0...v1.16.1) (2026-05-11)


### Bug Fixes

* 根治流式结束时AI气泡高度坍塌 ([36d73b7](https://github.com/roseforljh/EveryTalk/commit/36d73b7aabe0d2184610d9b80120e90266b0fef2))

## [1.16.0](https://github.com/roseforljh/EveryTalk/compare/v1.15.6...v1.16.0) (2026-05-10)


### Features

* 用户图片附件靠右显示+边缘渐变+桌面图标跟随深色模式实时切换 ([25ecc94](https://github.com/roseforljh/EveryTalk/commit/25ecc9472bff66afd1b19d359eacb1c5941704b5))
* 重构webfetch工具链路，接入Jina Reader API + MCP fallback ([17dc57e](https://github.com/roseforljh/EveryTalk/commit/17dc57ee53da386f98a3bb317614096a829101a2))


### Bug Fixes

* MCP工具调用不积极，优化dispatch策略和系统提示词 ([44ac6c0](https://github.com/roseforljh/EveryTalk/commit/44ac6c0887318cd0c3106b2799b2a71e7152ec54))
* 优化置顶逻辑 ([75519cb](https://github.com/roseforljh/EveryTalk/commit/75519cb24842088a2e3b9cefd7782f2859cfa66f))
* 会话位置记忆 ([db0adde](https://github.com/roseforljh/EveryTalk/commit/db0adde5c956cf38fbc11680b2d95a114a1c6edd))
* 修复置顶滚动session竞态及底部空白问题 ([d07642c](https://github.com/roseforljh/EveryTalk/commit/d07642cb1ac64bd4aaac15ebe08da3737eb69d06))
* 修复重新回答置顶逻辑 ([da2a35e](https://github.com/roseforljh/EveryTalk/commit/da2a35e0ba04554edadf0f5306dcfa4f51c3439f))
* 用户气泡置顶 ([afb9868](https://github.com/roseforljh/EveryTalk/commit/afb9868e783b66f12802ec68d54c25dda83e29cf))

## [1.15.6](https://github.com/roseforljh/EveryTalk/compare/v1.15.5...v1.15.6) (2026-04-26)


### Bug Fixes

* 一些输出上的优化？ ([8bbbb42](https://github.com/roseforljh/EveryTalk/commit/8bbbb421171baf02d9e867bf45ed5095b7147d58))
* 优化数学公式和美金的逻辑冲突 ([9c960a7](https://github.com/roseforljh/EveryTalk/commit/9c960a7617638e5cb1a9d85634fee2a26925ebbe))

## [1.15.5](https://github.com/roseforljh/EveryTalk/compare/v1.15.4...v1.15.5) (2026-04-14)


### Bug Fixes

* bug修复和结构图 ([6a81f29](https://github.com/roseforljh/EveryTalk/commit/6a81f292047a99180623ceee96bab80529d0febc))

## [1.15.4](https://github.com/roseforljh/EveryTalk/compare/v1.15.3...v1.15.4) (2026-04-11)


### Bug Fixes

* 修复工作流 ([4bc991c](https://github.com/roseforljh/EveryTalk/commit/4bc991c1a06bea595625124723ccd7d71954b202))

## [1.15.3](https://github.com/roseforljh/EveryTalk/compare/v1.15.2...v1.15.3) (2026-04-11)


### Bug Fixes

* 优化mcp架构 ([c7fa421](https://github.com/roseforljh/EveryTalk/commit/c7fa4215d43cc84ad312d68987ccd9453a3185b6))
* 优化文本模式加载态动画表现 ([332a7ea](https://github.com/roseforljh/EveryTalk/commit/332a7eaf49ee223809a98b750d8428fd9d9e65c9))
* 优化输出结构 ([3155cf6](https://github.com/roseforljh/EveryTalk/commit/3155cf669deb10d906e43df1c044c967d222c88d))
* 修复潜在和显式bug ([ff24a25](https://github.com/roseforljh/EveryTalk/commit/ff24a25c495d07f655d7e41933c6c5bd9f418e7a))
* 页面跳动 ([33e59f2](https://github.com/roseforljh/EveryTalk/commit/33e59f21651c1ac8c1c7b7d2683e5b4ef06a65d2))

## [1.15.2](https://github.com/roseforljh/EveryTalk/compare/v1.15.1...v1.15.2) (2026-04-04)


### Bug Fixes

* 优化加载时机 ([df1215a](https://github.com/roseforljh/EveryTalk/commit/df1215a38a76c9d8a31b1d4a9f1617709b0586e4))
* 优化流式输出 ([fb183ef](https://github.com/roseforljh/EveryTalk/commit/fb183efa1ab588a482dd518488ab819120cc507b))
* 优化输出格式、修复了一些bug ([32af1ac](https://github.com/roseforljh/EveryTalk/commit/32af1acbf564e2f7e115c8645b2df97ffa5cc8da))
* 修复ai输出卡死 ([c7b6fc2](https://github.com/roseforljh/EveryTalk/commit/c7b6fc2c82a5d9840e6ddd32efa65559b27705e9))
* 修复一些问题 ([57bcedd](https://github.com/roseforljh/EveryTalk/commit/57bceddff2beeddacd0f36045d0f1d52b8104e7f))

## [1.15.1](https://github.com/roseforljh/EveryTalk/compare/v1.15.0...v1.15.1) (2026-04-04)


### Bug Fixes

* 紧急bug修复 ([9a5c3c5](https://github.com/roseforljh/EveryTalk/commit/9a5c3c5a0c3ce03fa582dcae390bb9872c0ae251))

## [1.15.0](https://github.com/roseforljh/EveryTalk/compare/v1.14.0...v1.15.0) (2026-04-04)


### Features

* animate settings tab indicator with swipe and change color to onSurface ([305f429](https://github.com/roseforljh/EveryTalk/commit/305f4298d38ecb48d562643a72d0b2de5a70432a))
* implement swipeable tabs in settings screen and update tab text colors ([fd75d9f](https://github.com/roseforljh/EveryTalk/commit/fd75d9fd77b686b001441adad65098f0d8f8c59a))
* refactor settings screen to three tabs and change chat MCP options to toggle ([fd1fcc3](https://github.com/roseforljh/EveryTalk/commit/fd1fcc33f6c8cee806cbd64b82500d4a57668824))


### Bug Fixes

* 修复双模式bug ([14deb86](https://github.com/roseforljh/EveryTalk/commit/14deb86364c035313038263b754104f3d615c7c8))
* 可以调用工具解析https链接 ([8688850](https://github.com/roseforljh/EveryTalk/commit/86888501c107897e2956afc388534129a20fd243))
* 实现mcp调用能力 ([cc486cf](https://github.com/roseforljh/EveryTalk/commit/cc486cf5b3c44c0592d0171529c03af0ac780e69))
* 适配三方联网搜索 ([ccc484b](https://github.com/roseforljh/EveryTalk/commit/ccc484b36b6efbf0a689567eabcc0b499b08b4f6))

## [1.14.0](https://github.com/roseforljh/EveryTalk/compare/v1.13.2...v1.14.0) (2026-03-29)


### Features

* webfetch按链接注入内建工具 ([b14af72](https://github.com/roseforljh/EveryTalk/commit/b14af7215c1e0ab3b59cdff063e2ecf725f45d85))
* webfetch新增原生抓取服务 ([04ae2b3](https://github.com/roseforljh/EveryTalk/commit/04ae2b3e04edb2bc9e84cc7049202930e3c58505))


### Bug Fixes

* webfetch修复工具状态与连接卡死问题 ([833953b](https://github.com/roseforljh/EveryTalk/commit/833953bb174243d32cc861608f38dadf3f846aae))
* 修复自定义提示词误显示到对话界面 ([1e19af5](https://github.com/roseforljh/EveryTalk/commit/1e19af556ac74547e2295f40ea2b138ba1c6f75e))

## [1.13.2](https://github.com/roseforljh/EveryTalk/compare/v1.13.1...v1.13.2) (2026-03-29)


### Bug Fixes

* 修复复杂数学公式渲染链路 ([8c6efb2](https://github.com/roseforljh/EveryTalk/commit/8c6efb2dd1e1f66ab8ce4e83230099048c7d32e5))
* 修复数据库导致app崩溃和表格渲染 ([8c4bd0d](https://github.com/roseforljh/EveryTalk/commit/8c4bd0d75318b62b5519a25ae38ab1ed20ea26cc))

## [1.13.1](https://github.com/roseforljh/EveryTalk/compare/v1.13.0...v1.13.1) (2026-03-24)


### Bug Fixes

* "/model、/models" ([31d9fe3](https://github.com/roseforljh/EveryTalk/commit/31d9fe3c424ef0acbfb1ee1bef515c3eb7cc59cb))
* 修复openai兼容渠道 ([db45344](https://github.com/roseforljh/EveryTalk/commit/db45344c540307f36e393af63e705c97ff95517e))
* 修复乱码 ([73042ab](https://github.com/roseforljh/EveryTalk/commit/73042abbc6939c3f52069bca0b40b3fbb4502a0e))
* 删除垃圾日志 ([56d1b84](https://github.com/roseforljh/EveryTalk/commit/56d1b841e0fb0aeda70ddcc54936d9744af38291))
* 输出优化 ([02221d9](https://github.com/roseforljh/EveryTalk/commit/02221d94e694e0e3ced38aa2c48c09e0b6143145))

## [1.13.0](https://github.com/roseforljh/EveryTalk/compare/v1.12.9...v1.13.0) (2026-03-15)


### Features

* add openclaw bridge integration and remote installer ([06e5ebb](https://github.com/roseforljh/EveryTalk/commit/06e5ebb87f4cb5f5bb4b7192159c2767d463de26))
* 接入openclaw ([77edbf9](https://github.com/roseforljh/EveryTalk/commit/77edbf946f6017a3503b1dc60f23fbde8ecbb01b))

## [1.12.9](https://github.com/roseforljh/EveryTalk/compare/v1.12.8...v1.12.9) (2026-03-06)


### Bug Fixes

* add BreakableLatexRenderer component for markdown UI. ([d843bc0](https://github.com/roseforljh/EveryTalk/commit/d843bc0748175fb2408b04ad387cc4b3c95da19a))
* add TableAwareText component for rich text rendering with Markdown, LaTeX, and table support, including streaming and caching. ([41f1b43](https://github.com/roseforljh/EveryTalk/commit/41f1b435aecddd6bdd7fb2a5d59e69481ef5f24f))
* Implement a comprehensive Markdown rendering and content parsing system with JLatexMath support, streaming policies, and Markwon caching. ([9ddb236](https://github.com/roseforljh/EveryTalk/commit/9ddb2366ff4e735f5bca6249757a5bc3ebce3b54))
* Implement Gemini-like streaming architecture for AI chat responses, including new state management, parsing, and UI rendering components. ([ac9da3e](https://github.com/roseforljh/EveryTalk/commit/ac9da3eee5def3a4f4f1b8c989e4e9f303a4de6d))
* Introduce `TableAwareText` and `BreakableLatexRenderer` for enhanced content rendering with incremental parsing and caching. ([a7ea493](https://github.com/roseforljh/EveryTalk/commit/a7ea493c9e9d1847ae92ceb4ffc94846cb71bb13))
* introduce Markdown content parsing into structured components for rendering and add system prompt injection. ([5dbd80f](https://github.com/roseforljh/EveryTalk/commit/5dbd80ff83c7e12108423a0eeec8392f3159b9d0))
* Introduce streaming chat message processing with enhanced UI components for markdown, math, and tables. ([5ca838f](https://github.com/roseforljh/EveryTalk/commit/5ca838f86b0f965878a1047ac1f7da6e35c43c29))

## [1.12.8](https://github.com/roseforljh/EveryTalk/compare/v1.12.7...v1.12.8) (2026-03-04)


### Bug Fixes

* Introduce Markdown rendering with preprocessing, system prompt injection, and prompt leak guard utilities. ([43e29f5](https://github.com/roseforljh/EveryTalk/commit/43e29f54089dfa0517fc921d6ab220998140cb7c))

## [1.12.7](https://github.com/roseforljh/EveryTalk/compare/v1.12.6...v1.12.7) (2026-03-04)


### Bug Fixes

* Add Markdown rendering with enhanced preprocessing for math, bolding, and text formatting. ([8901d56](https://github.com/roseforljh/EveryTalk/commit/8901d567d0dfcf0ef62e8545b13f5664343d88e2))
* Add Markwon caching and custom rendering with markdown preprocessing utilities. ([430740c](https://github.com/roseforljh/EveryTalk/commit/430740c374e4fe48ae66f7cd8e802c307d88ec8c))
* Implement a new chat screen featuring markdown rendering, typewriter effect, and robust data persistence for messages and media. ([3ebc5d4](https://github.com/roseforljh/EveryTalk/commit/3ebc5d4e0141b5fe7a93e59980e94c37c79d9c33))
* Implement new UI components for table and Markdown rendering, add infographic parsing, and introduce system prompt injection. ([a6e6eef](https://github.com/roseforljh/EveryTalk/commit/a6e6eeff3d770a1389b55ae8378db16990a6b22f))

## [1.12.6](https://github.com/roseforljh/EveryTalk/compare/v1.12.5...v1.12.6) (2026-02-02)


### Bug Fixes

* **chat:** improve fall animation performance by using Spacer item instead of contentPadding ([8b847db](https://github.com/roseforljh/EveryTalk/commit/8b847db68db4a82e0eed736e9457cafb217638eb))
* 历史名称重命名 ([11cda4d](https://github.com/roseforljh/EveryTalk/commit/11cda4dfb608bea5c9ecb6e9403d28f34c979723))

## [1.12.5](https://github.com/roseforljh/EveryTalk/compare/v1.12.4...v1.12.5) (2026-01-29)


### Bug Fixes

* 自动识别最新环境变量 ([ee03270](https://github.com/roseforljh/EveryTalk/commit/ee03270e3777d842ba1ed54b9fda57d67620abff))

## [1.12.4](https://github.com/roseforljh/EveryTalk/compare/v1.12.3...v1.12.4) (2026-01-29)


### Bug Fixes

* bug fix ([f2fcb04](https://github.com/roseforljh/EveryTalk/commit/f2fcb04439b8c131849e551a3fa2b74dca551f86))

## [1.12.3](https://github.com/roseforljh/EveryTalk/compare/v1.12.2...v1.12.3) (2026-01-21)


### Bug Fixes

* **ui:** 修复表格数学公式渲染和代码块吸顶问题 ([6b2532c](https://github.com/roseforljh/EveryTalk/commit/6b2532c4a7f391793187999c870b5c048fc8fbb8))
* **ui:** 输入框多行时动态切换为圆角矩形 ([87f61d6](https://github.com/roseforljh/EveryTalk/commit/87f61d63cdaa6bb1bf25569621331b35d7383a7e))

## [1.12.2](https://github.com/roseforljh/EveryTalk/compare/v1.12.1...v1.12.2) (2026-01-21)


### Bug Fixes

* **ui:** adapt input area colors for light theme ([8e8c344](https://github.com/roseforljh/EveryTalk/commit/8e8c344be8a432ea7dbef0042d458f49220b75d6))
* 优化输出 ([531c4c4](https://github.com/roseforljh/EveryTalk/commit/531c4c488969cca6f1fc5154fdf478e5316bd13e))

## [1.12.1](https://github.com/roseforljh/EveryTalk/compare/v1.12.0...v1.12.1) (2026-01-20)


### Bug Fixes

* optimize chat scroll position and prevent user bubble jump ([e30df7b](https://github.com/roseforljh/EveryTalk/commit/e30df7bab71058d238b88860ece20ff095e486e9))
* **ui:** 修复输入区域弹出面板位置错误 ([3c86d87](https://github.com/roseforljh/EveryTalk/commit/3c86d87c1c179c652dd6bf15347826c751e0738d))

## [1.12.0](https://github.com/roseforljh/EveryTalk/compare/v1.11.3...v1.12.0) (2026-01-18)


### Features

* **infographic:** 重构时间轴布局 + 优化 MDI 图标居中 ([842bf08](https://github.com/roseforljh/EveryTalk/commit/842bf089afba43da9da45f125af02d7529928e75))


### Bug Fixes

* **infographic:** 支持完整 MDI 图标库 + 修复 HTML 内容丢失 ([2319081](https://github.com/roseforljh/EveryTalk/commit/23190816719bce42d997ae4a83a9efc0880c5e38))
* **markdown:** 修复标题中内联数学公式不渲染的问题 ([fb7c9b3](https://github.com/roseforljh/EveryTalk/commit/fb7c9b36e6e3df3cd449514044264eefec5a8db2))
* **ui:** 修复 Markdown 渲染问题 + 优化表格和代码块样式 ([ef31e15](https://github.com/roseforljh/EveryTalk/commit/ef31e157d03007fcadae7b45620b879e44adb55b))

## [1.11.3](https://github.com/roseforljh/EveryTalk/compare/v1.11.2...v1.11.3) (2026-01-18)


### Bug Fixes

* 删除垃圾 ([96076d0](https://github.com/roseforljh/EveryTalk/commit/96076d09ed38c3c66a28fdee2594067b06add00e))

## [1.11.2](https://github.com/roseforljh/EveryTalk/compare/v1.11.1...v1.11.2) (2026-01-18)


### Bug Fixes

* revert to PAT to allow triggering build workflow ([283cd40](https://github.com/roseforljh/EveryTalk/commit/283cd402af98a06e38b9aa06af1a7da93d085d4a))

## [1.11.1](https://github.com/roseforljh/EveryTalk/compare/v1.11.0...v1.11.1) (2026-01-18)


### Bug Fixes

* keep long LaTeX blocks readable with horizontal scroll ([a539a69](https://github.com/roseforljh/EveryTalk/commit/a539a69e60b31eda90ad93a700b6f8f5c2d522ef))
* math formula rendering and click preview ([0b61a4f](https://github.com/roseforljh/EveryTalk/commit/0b61a4f6cb97dded004c861e58264dd932e4282a))
* use github token for release-please workflow ([0b70845](https://github.com/roseforljh/EveryTalk/commit/0b70845dffbe5032db29eb9e0dcc98d4d75510cd))

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
