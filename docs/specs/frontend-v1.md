# AgentTrail 前端工程 — 需求 Spec（v1：对话 / 文件问答 / DeepResearch / PPT）

> 状态：已拆分为 GitHub issue（`renjian-pro/AgentTrail`，`ready-for-agent` 标签）—— [#38](https://github.com/renjian-pro/AgentTrail/issues/38) 后端 SSE+持久化、[#39](https://github.com/renjian-pro/AgentTrail/issues/39) 前端脚手架、[#40](https://github.com/renjian-pro/AgentTrail/issues/40) 对话界面、[#41](https://github.com/renjian-pro/AgentTrail/issues/41) 文件问答、[#42](https://github.com/renjian-pro/AgentTrail/issues/42) DeepResearch 界面、[#43](https://github.com/renjian-pro/AgentTrail/issues/43) PPT 生成界面。本文件继续作为完整设计留档维护，issue 是具体施工的落地单位，两边内容变化时都要同步。
> 披露规则：本 spec 及后续衍生文档不点名具体的个人历史参考仓库，方法论表述为"研读了一个真实生产形态框架的前端设计模式后独立实现"。

## Problem Statement

AgentTrail 最初只有后端（Runtime + 四类能力入口：对话、DeepResearch、PPT 生成、文件问答），全部通过 `curl`/Postman 验证。前端 v1 落地前意味着：

- 面试演示只能靠 curl 输出的 JSON，看不出 ReAct loop 的多轮决策、TodoProgress、推理过程这些"过程性"亮点——这些恰恰是本项目相对于"调一次 API"的差异化价值
- 部分能力（PPT 状态机断点续传、文件问答的大文件 RAG 分流）没有可视化验证手段，只能读代码/读 DB 确认行为是否符合预期
- 后端当时的 V1 入口（`/agent/v1/*`）还是"同步骨架"：无 SSE 流式、无会话持久化落地、无历史查询。前端 v1 已倒逼并完成前三项；Skills 管理 HTTP 面仍不在本期范围

## Solution

已经按目标形态实现一个 Vue3 + TypeScript + Vite 单页应用，覆盖后端现有四类能力入口（对话 / 文件问答 / DeepResearch / PPT 生成），采用参考框架前端验证过的交互范式（SSE 分阶段时间线、推理折叠面板、TodoProgress、会话历史）独立实现。

`/agent/v1/chat` 现已使用 SSE 返回事件流，并通过稳定的 `conversationId` 落库；DeepResearch 与 PPT 虽然仍是同步 HTTP 调用，但也复用同一个会话编号并写入同一张会话表。前端刷新后从统一历史接口恢复普通消息和能力卡片，不再把能力结果留在组件内存里。

## User Stories

### 核心对话

1. 作为演示访问者，我想发送一条消息并看到 agent 的回复，以便评估其对话能力
2. 作为演示访问者，我想看到回复像真实 LLM 产品一样逐 token 流式输出，以便获得"正在思考"的观感
3. 作为演示访问者，我想在独立的可折叠面板里看到模型的推理过程（Thinking），以便在不打乱主回答阅读体验的前提下检查推理链路
4. 作为演示访问者，我想看到工具调用（开始/结束）以时间线分阶段渲染，以便理解 agent 每一步在做什么
5. 作为演示访问者，当 agent 规划出多步任务清单时，我想看到 TodoProgress 进度条实时反映已完成/进行中/待办，以便追踪复杂任务的执行状态
6. 作为演示访问者，我想能中断正在进行的回复生成，以便打断跑偏或不需要的输出
7. 作为回访用户，我想我的对话历史在刷新页面后仍然保留，以便不丢失上下文
8. 作为回访用户，我想浏览分页的历史会话列表，以便回看之前的对话
9. 作为演示访问者，我想在发送消息前切换"联网搜索"开关，以便控制 agent 是否使用外部搜索工具
10. 作为演示访问者，如果后端支持多模型，我想能选择本次对话使用的模型，以便对比不同模型的表现
11. 作为演示访问者，当后端调用失败时我想看到清晰的错误提示，以便不用打开开发者工具就能理解发生了什么

### 文件问答

12. 作为演示访问者，我想在当前对话中上传文件（拖拽或选择器），以便针对文件内容提问
13. 作为演示访问者，我想看到上传/解析的进度与失败原因，以便知道文件何时可以开始提问
14. 作为演示访问者，我想针对已上传文件提问并得到基于文件内容的回答，以便验证大文件走检索问答这条链路是否生效
15. 作为演示访问者，我想看到当前对话已附加哪些文件，以便了解现在有哪些上下文可用
16. 作为演示访问者，我想移除或替换一个已附加的文件，以便纠正误传的文件

### DeepResearch

17. 作为演示访问者，我想提交一个研究问题并得到结构化报告，以便评估多阶段研究能力
18. 作为演示访问者，即使后端是同步阻塞调用、耗时较长，我也想保留刚提交的研究问题，并看到耗时、阶段说明和明确的"研究进行中"状态；等待期间能力开关与发送入口应锁定，避免重复提交
19. 作为演示访问者，我想报告以清晰分节的格式渲染（而不是原始 JSON），以便读起来像一份真正的研究报告
20. 作为演示访问者，我想研究失败的错误提示和对话失败的错误提示在视觉上有区分，以便快速定位是哪个能力出了问题

### PPT 生成

21. 作为演示访问者，我想用一句话描述需求并触发 PPT 生成，以便看到状态机驱动生成的过程
22. 作为演示访问者，我想看到当前任务所处的状态（排队/运行/某个阶段/失败/完成），以便理解状态机走到了哪一步
23. 作为演示访问者，当任务失败时我想能从失败的状态点恢复（而不是从头重来），以便验证断点续传机制
24. 作为演示访问者，我想下载或预览生成的 PPT 文件，以便查看最终产物

### 跨能力 / 工程

25. 作为面试官/评估者，我想在一个页面内（或清晰互链的几个视图）看到对话/文件问答/研究/PPT 四类能力，以便不用东找西找 URL 就能过完整个能力面
26. 作为前端维护者，我想把 SSE 流式事件契约用 TypeScript 类型固定下来，以便后端事件结构变化时编译期就能发现不一致
27. 作为前端维护者，我想把所有后端调用收敛在一层带类型的 API client 之后，以便未来某个接口从同步换成 SSE/轮询时改动不会扩散到每个组件里
28. 作为回访用户，我想让普通问答、DeepResearch 和 PPT 都出现在同一份会话记录里，以便切换历史会话后按原顺序恢复文字与能力卡片

## Implementation Decisions

### 技术栈

- Vue 3 + TypeScript + Vite；组合式 API（`<script setup>`），不用 Options API
- 状态管理：Pinia；`stores/chat.ts` 作为会话聚合根，统一管理会话列表、当前会话编号和时间线。上传控件等短生命周期 UI 状态留在组件内，不为拆 store 而拆 store
- 路由：Vue Router 只有 `/chat` 一个视图（`/` 重定向过去）。~~四个一级视图 `/chat`/`/research`/`/ppt`~~ 这条初始决策已推翻：对话/文件问答/联网搜索/DeepResearch/PPT 生成全部收进同一个入口，不做分页面路由——用户不需要先判断"这个任务该去哪个页面做"。DeepResearch/PPT 通过输入框上方的模式选择器（"+"式）触发，结果作为卡片渲染进同一条时间线，不跳转页面
- HTTP/SSE：`fetch` + `ReadableStream` 手动解析 SSE（而不是浏览器原生 `EventSource`），因为原生 `EventSource` 只支持 GET、不能带自定义 header/body，后端流式接口大概率是 `POST` + `text/event-stream`
- 样式：不引入重量级组件库（Element Plus/Ant Design Vue 这类），用轻量的自定义组件 + CSS variables，保持和"面试演示页"的定位匹配，避免为一次性演示页引入过重的依赖面

### 项目结构与部署

不另开仓库、不单独部署。`frontend/` 作为子目录放进 AgentTrail 同一个仓库，是独立的 Vite 项目（自己的 `package.json`），但用 `frontend-maven-plugin` 绑定到 `mvn package`：自动跑 `npm install && npm run build`，构建产物直接输出到 `src/main/resources/static/`，随主工程一起打进同一个 JAR。最终形态是一个进程、一个端口，`java -jar`/`mvn spring-boot:run` 一条命令起来，前后端不分开跑——和现有的单 JAR 部署形态保持一致，不引入独立的前端服务器。

开发期用 `vite dev` 单独起一个端口做热更新，`vite.config.ts` 里配 `server.proxy` 把 `/agent/**` 转发到后端端口，避免 CORS，也不用每改一行代码就重新打包一次。

### 模块划分

| 模块 | 组件 | 说明 |
|---|---|---|
| API client | `src/api/*.ts` | 按后端 controller 对齐：`chatApi`、`researchApi`、`pptApi`、`fileApi`，每个方法返回类型化的 Promise/AsyncGenerator |
| 对话（唯一入口） | `ChatView`、`MessageInput`、`ThinkingPanel`、`ToolTimeline`、`TodoProgressBar` | 消费 SSE 事件流，按 `AgentStart/Thinking/Text/ToolStart/ToolEnd/TodoProgress/StageOutput/Error/Complete` 类型分发渲染，事件类型定义在 `src/types/stream-event.ts`；会话历史侧栏在 `App.vue`，由 `stores/chat.ts` 驱动 |
| 文件问答 | `FileUploadWidget`、`AttachedFileList` | 挂在 `ChatView` 内，`conversationId` 复用当前会话 |
| DeepResearch | `ChatView` 内的模式选择器 + `ResearchReportCard` | 下一条消息路由到 `researchApi.run(conversationId, question)`；卡片始终保留研究问题，等待时展示耗时与研究阶段，成功时按结构化字段/Markdown 分节渲染，澄清分支单独呈现 |
| PPT 生成 | `ChatView` 内的模式选择器 + `PptTaskCard` | 下一条消息路由到 `pptApi.create()`；卡片保留原始需求，"继续"按钮调 `/resume/{taskId}`，失败态展示 `errorMsg`；成功态一律从 `taskId` 构造 `GET /agent/v1/ppt/{taskId}/download`，因此旧历史里残留的服务端文件路径也不会成为浏览器链接 |
| 统一历史 | `stores/chat.ts` + `CapabilityConversationService` | 普通对话、DeepResearch、PPT 都复用 `conversationId`。每轮占 `agent_session` 一行；`question`/`answer` 保持可读，结构化能力结果以 `StageOutput` 写入 `timeline` 数组，历史恢复时再还原为对应卡片 |

### 后端前置依赖（已完成）

跟踪为 [issue #38](https://github.com/renjian-pro/AgentTrail/issues/38)：

1. `/agent/v1/chat` 已返回 `Flux<AgentStreamEvent>`，媒体类型为 `text/event-stream`；前端按事件到达顺序即时渲染，而不是等连接关闭后一次刷新
2. `AgentLoopExecutorConfig` 已接入 `TurnPersistenceHook`/`JdbcSessionStore`，同一会话内复用稳定的 `conversationId`
3. 会话列表与详情接口已复用 `agent_session`；标题取首轮问题保持稳定，最近活动时间取最后一轮
4. DeepResearch/PPT 已接入 `CapabilityConversationService`，和普通问答共用同一套会话分组与 `TimelineEntry[]` 契约

### 同步能力的接口约束

DeepResearch（[#42](https://github.com/renjian-pro/AgentTrail/issues/42)）与 PPT 生成（[#43](https://github.com/renjian-pro/AgentTrail/issues/43)）当前仍返回同步 JSON。前端不能伪造精确的后端进度百分比，只展示已确认的宏观阶段、已等待时间和可解释的提示；请求完成后使用结构化响应渲染，并由后端把同一份结果写入当前会话。PPT 完成响应中的 `outputPath` 是受控下载 URL（`/agent/v1/ppt/{taskId}/download`），不是服务器的磁盘路径；下载接口以 attachment 响应真实的 `.pptx` 文件，文件不存在时返回 404。

## Testing Decisions

- 组件测试：Vitest + Vue Test Utils，覆盖每个视图的核心交互（发送消息、上传文件、触发 research/ppt）
- SSE 解析逻辑单独做单元测试：用固定的"脚本化事件流"fixture（文本形式的 SSE 帧序列）驱动解析器，断言分发出的事件类型和顺序正确——这个思路对应后端已经在用的"确定性测试"方式（脚本化输入代替真实网络/LLM），不依赖真实后端
- API client 测试：mock `fetch`/`ReadableStream`，覆盖同步 JSON 响应和流式响应两种形态
- 流式 UI 测试必须让两个 `Text` 事件之间真正挂起，先断言首段已经出现在 DOM，再结束流；只检查最终拼接文本无法发现"网络是流式、页面却最后一次性刷新"的问题
- 统一历史使用真实 MySQL 测试验证：普通轮次与能力轮次共用 `conversation_id`，`timeline` 根节点始终是数组，能力结果使用 `StageOutput` 恢复
- 只测外部行为（组件渲染结果、发出的请求、展示的状态），不测内部实现细节（不断言某个 ref 的中间值）
- 浏览器 QA：至少覆盖"发送消息→生成中已经看到首段文字"和"提交 DeepResearch→问题仍可见、能力按钮已锁定、等待态持续更新"两条黄金路径

## Out of Scope

- **登录/RBAC/数据权限**：不是因为不需要，而是因为这块和 `docs/roadmap.md` Phase 2（SQL 数据分析）里已经规划好的"完整数据权限模型"（`sys_user`/`sys_role`/`sys_dept` + `DataScopeResolver`/`DataScopeRewriter` + 敏感字段脱敏）是同一件事——登录本身只有门面价值，只有配合权限过滤/脱敏生效才有东西可演示。作为独立一份绑定 Phase 2 的 spec 规划，见 [`frontend-phase2-auth.md`](frontend-phase2-auth.md)。v1 的四个能力（对话/文件问答/DeepResearch/PPT）继续走 `RunnableParams` 里写死的 `"anonymous"` 身份，不受影响
- Skills 管理侧栏（启用/停用/上传/删除技能）：`agent_skill` 表已存在但没有对应 controller，属于另一张后端票的范围，等接口落地后单独开一个前端票
- 移动端像素级适配：保证基本可用（不横向溢出、按钮可点），不做专门的移动端交互重设计
- 国际化（i18n）：面试演示场景默认中文，不做多语言
- 部署/CI 流水线：属于 Phase 11"部署与运维"的另一部分，本 spec 只覆盖前端应用本身
- ~~深色/浅色主题切换~~：已过时——确认的 ChatGPT 风格视觉方向本身就要求同时支持浅色/深色两套 token，不再是 Out of Scope，改记在下面「视觉方向」里

## Further Notes

- Phase 11 的"参考实现"验证过的交互范式（流式时间线、推理折叠面板、TodoProgress、历史会话分页）是本 spec 的设计起点，但不照搬代码，只借鉴机制设计——这和 `AGENTS.md` 里对后端 Runtime 机制的要求是同一原则的前端延伸
- 视觉方向已定稿：不走自造的"控制台/trace rail"风格，改为成熟桌面助理的轻量壳（紧凑侧栏 + 大留白居中工作区 + 宽幅悬浮输入框 + 能力胶囊 + Thought/Tool chip 可展开交互）；空态标题与输入框组成同一个视觉组，进入对话后输入框再固定到底部。浅色/深色两套 token 都要做，研究报告必须保持单列可读宽度，不能被消息行样式挤成多栏。
- 后端三项前置依赖（SSE、persistenceHook 接线、会话历史查询）已由 [issue #38](https://github.com/renjian-pro/AgentTrail/issues/38) 落地；后续又把同步能力结果接入相同会话存储，避免历史侧栏只恢复普通聊天
- v1 已拆分为 6 张 GitHub issue（#38-#43，见文件顶部状态行），本文件继续作为完整设计留档维护
- 登录 + RBAC + 数据权限前端，作为独立一份 spec 绑定 Phase 2（SQL 数据分析）一起规划，见 [`frontend-phase2-auth.md`](frontend-phase2-auth.md)——尚未拆票，Phase 2 后端本身也还没开始
