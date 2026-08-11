# Ticket 19（Phase 8）：文件问答/RAG 迁移 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 15](refactor-remediation-ticket-15.md)。可与 Ticket 16/17/18 并行。

## 0. 范围边界

**这一票只做**：把 `capability/file/FileQaService.java`（含 `multimodal` 子包）和 `capability/rag/*`
从"一个业务服务类直接依赖 Spring AI/JDBC 具体类型"迁到"用例类只依赖端口接口"的目标形态，
顺带把这个能力包相对独立、之前几轮审计都没细看的几个真实缺口（同步阻塞上传、无大小/MIME/
压缩炸弹防护、文件归属关系隐式化）补上。**不做**：`RagRetrievalService` 内部的检索算法调整
（查询压缩/多查询扩展/rerank 与否，这些是效果调优，不是架构问题）、多模态模型选型、
`db/schema.sql` 之外的向量库 schema 变更。

**开工前的验证结论**（已实际读过 `FileQaService.ingest`/`contentFor` 源码，不是凭空假设）：

- `FileQaService` 目前是一个 195 行的单一编排类，构造函数注入 `FileStore`、`FileTextParser`、
  `FileVectorizationService`、`RagRetrievalService`、`ImageDescriptionService` 五个协作者，
  `ingest`/`contentFor`/`belongsToUser`/`belongsToConversation`/`delete` 五个公开方法混在一起，
  没有按"写入"和"读取/检索"两条职责线拆分——这是本票目标①要解决的真实问题，不是臆测。
- `ingest()`（[FileQaService.java:82-106](../../src/main/java/com/agenttrail/capability/file/FileQaService.java)）
  对 `FileKind.TEXT` 的路径是**完全同步阻塞**的：`parser.parse(content, fileName)`（Tika 解析）
  和超阈值时的 `vectorizationService.vectorize(id, parsedText)`（分块 + 调用 embedding 模型批量
  写入 PgVector）都在同一次方法调用里跑完，方法返回之前 HTTP 线程一直被占着——
  `FileUploadController.upload()` 直接 `return FileUploadResponse.from(fileQaService.ingest(...))`，
  中间没有任何异步跳转。**这条验证结论推翻了票面上"可能已经是异步"的预设**：现状确实是同步
  阻塞，目标②必须做，不能标成 Out of Scope。
- 现状**没有任何**文件大小/MIME allowlist/解压炸弹检测：`application.yml` 只有 Spring
  `spring.servlet.multipart.max-file-size: 50MB` 这一道全局硬上限（对齐 Boot 默认 1MB/10MB 太小
  才调大，注释原话"文件问答需要接收真实文档"），没有按租户/会话的配额，没有内容类型白名单
  （`FileKindDetector` 只用来判断走图片还是文本两条路由，不是安全校验），`FileTextParser` 直接
  `tika.parseToString(content)`——不限制解析后文本长度、不限制解析耗时，一个声明很小但解压后
  体积暴涨的 zip/Office 文档（Office Open XML 本质是 zip）理论上可以让 Tika 在 CPU/内存上失控。
- `capability.file.FileStore`（[FileStore.java](../../src/main/java/com/agenttrail/capability/file/FileStore.java)）
  本身已经是一个不泄漏实现细节的接口（`JdbcFileStore`/`InMemoryFileStore` 两个实现），
  `FileUploadController` 传给 `FileQaService.ingest` 的是 `InputStream`+`long sizeBytes` 而不是
  `MultipartFile`——**这两点现状已经是对的，不需要本票改**，本票的 ①③ 目标是在这个基础上补
  `EmbeddingPort`/`RetrievalPort`（当前 `FileVectorizationService`/`RagRetrievalService` 直接依赖
  `org.springframework.ai.vectorstore.VectorStore`/`ChatModel`，属于 Spring AI 类型泄漏到业务层，
  违反 §5 的 `capability.* 不得依赖 org.springframework.ai.*` 规则）。
- `loop/core/AgentLoopExecutor.java` 当前**直接**持有 `FileStore fileStore` 字段（第 114 行）、
  在 Builder 上暴露 `fileStore(FileStore)`（第 460 行），并在两处直接调用：组装 system prompt 时
  `FilePromptFormatter.formatSection(fileStore.findByConversationId(conversationId))`（第 1041 行），
  轮次完成时 `fileStore.linkFilesToTurn(context.conversationId(), turnId)`（第 1076 行）——这是
  Runtime 核心层直接依赖业务能力包的真实耦合点，不是文档臆测，目标⑤要解决的正是这两处调用。

## 1. 拆分 `FileIngestUseCase` / `FileContentQueryUseCase`（`FileRetrievalUseCase`）

按"改动理由是否相同"把现有 `FileQaService` 拆成两个用例类，放到
`capability.fileqa.application` 包（对齐 `refactor-blueprint.md` §2.8 迁移映射表给的目标位置
`capability.fileqa.application.FileIngestUseCase` + `FileQueryUseCase`；`FileRetrievalUseCase` 是
这一票在映射表基础上把"读取小文件全文"和"大文件语义检索"进一步拆开的细化，检索逻辑变化频率
明显高于"按 fileId 取值"这类查询，值得分开）：

- **`FileIngestUseCase`**：`ingest(...)`（上传/解析/向量化）、`delete(...)`（含向量库联动清理，
  因为删除本质是撤销一次 ingest，和 ingest 是同一条改动理由线）。
- **`FileContentQueryUseCase`**：`belongsToUser`/`belongsToConversation`（归属校验）、
  `contentFor` 里"小文件直接返回全文"这条分支。
- **`FileRetrievalUseCase`**：`contentFor` 里"大文件走 RAG 检索"这条分支，内部委托
  `RetrievalPort`——之所以从 `FileContentQueryUseCase` 里再切一层，是因为检索管线（查询压缩/
  多查询扩展/rerank 策略）未来会独立演进，不应该和"按 fileId 查一条记录"这种简单读取混在同一个
  改动理由里。`FileContentQueryUseCase.contentFor` 保留门面方法、按阈值分流到自己的小文件分支或
  委托给 `FileRetrievalUseCase`，对上层（`FileContentTool`/`FileUploadController`）的调用点无感。

**允许依赖的端口**（对齐 `refactor-blueprint.md` §2.8 迁移映射表这一行）：

```java
package com.agenttrail.capability.fileqa.port;

public interface FileStorePort {
    long save(Attachment attachment);          // 见目标④，Attachment 取代裸 UploadedFile
    Optional<Attachment> findById(long id);
    List<Attachment> findByConversationId(String conversationId);
    void updateParsedText(long id, String parsedText);
    void linkFilesToTurn(String conversationId, long turnId);
    void delete(long id);
}

public interface EmbeddingPort {
    int embedAndStore(long fileId, String fullText);   // 现 FileVectorizationService.vectorize
    void deleteByFileId(long fileId);
}

public interface RetrievalPort {
    List<String> retrieve(long fileId, String question); // 现 RagRetrievalService.retrieve
}
```

**现状到目标的落地方式**：`FileStore` 接口本身已经不泄漏实现（见 §0），改名/挪包成
`FileStorePort` 即可，`JdbcFileStore`/`InMemoryFileStore` 挪到 `infrastructure.persistence.jdbc`
和测试目录实现它——这一步是机械搬迁，不是重新设计。`FileVectorizationService`/
`RagRetrievalService` 现在直接依赖 `org.springframework.ai.vectorstore.VectorStore`/
`ChatModel`，这两个类整体挪到 `infrastructure.rag`（或类似 adapter 包），分别实现
`EmbeddingPort`/`RetrievalPort`，两个用例类构造函数只接口面依赖，编译期看不到任何
`org.springframework.ai.*` 类型——这条要配 ArchUnit 规则（复用 Ticket 11 已经建的架构护栏，
加一条 `capability.fileqa..` 不得依赖 `org.springframework.ai..`）才能防止回归，不能只靠代码评审。

**禁止依赖**：Web 层 `MultipartFile`（`FileUploadController` 已经在调用处把
`file.getInputStream()`/`file.getSize()` 转成裸参数传进去，这一步现状已经做对，只需要在用例类
签名上继续保持）；`VectorStore`/`ChatModel`/`JdbcClient`/`DataSource` 等具体实现类型一律不出现在
`capability.fileqa.application` 包内。

## 2. 大文件向量化改成异步任务

**已验证现状是同步阻塞**（见 §0），这条必须做，不是"确认已异步、标 Out of Scope"。

目标形态：`POST /agent/v1/files` 改成"快速校验 + 落一条 `Attachment` 记录（状态
`INGESTING`）+ 提交 Ticket 15 的异步 Task + 立即返回 `{fileId, ingestTaskId}`"，不再在 HTTP
线程里跑完 Tika 解析和向量化。

> Ticket 15（`Phase 4：统一 Run/Task/Checkpoint/Event`）在写这张票时还没成文，具体类名以 Ticket 15
> 落地结果为准；这里按 `refactor-blueprint.md` §1.8/§2.8 已经定好的目标设计引用：统一 Task 模型
> （`taskId/workflowId/tenantId/userId/conversationId/status/currentNode/attempt/leaseOwner/
> leaseUntil/idempotencyKey/checkpointVersion/inputRef/outputRef/errorCode/createdAt/updatedAt`）
> 和 Task API（`POST .../runs → 202 {runId,taskId}`、`GET .../{taskId}`、`GET .../{taskId}/events`）。
> 文件 ingest 任务复用同一套 Task 基础设施，不是另起一个"文件专用任务表"。

具体拆分（这里给出一个可行方案，不是唯一答案，实现时可以调整，但两条路径不要分叉太远，避免
"小文件走同步快路径、大文件走异步慢路径"变成两套要分别维护的代码）：

1. **同步部分**（HTTP 线程内完成，要求快）：目标③的大小/MIME/配额校验（校验失败直接 4xx，
   不产生任何记录）→ 落一条 `Attachment` 记录，`status=INGESTING`，此时还不知道
   `FileKind`/`routedToRag`（因为没解析）→ 提交异步 Task，Task 输入是 `attachmentId` +
   文件字节的引用（建议原始字节先落一个临时对象存储位置或直接连同请求体一起交给 Task 的
   输入通道，不要求 HTTP 线程等 Task 消费完才能释放请求）→ 返回 `{fileId, ingestTaskId}`。
2. **异步部分**（Task 内执行）：`FileKindDetector.detect` 判定类型 → `IMAGE` 只需存
   `rawBytes`，直接标记 `READY`；`TEXT` 走 `FileTextParser.parse`（带目标③的输出长度/超时限制）
   → 按阈值决定要不要调 `EmbeddingPort.embedAndStore` → 全部成功后 `Attachment.status=READY`，
   任一步失败则 `status=FAILED` + `errorCode`，不再像现状那样让 `VectorizationException` 直接
   从 HTTP 方法抛出变成 502——失败信息通过 `GET .../{taskId}` 或 `GET .../files/{fileId}` 轮询
   拿到，这样统一了"图片""小文本""大文本"三条路径都走同一个任务模型，不需要为"小文件走同步"
   再单独维护一套错误处理。
3. **对上层调用方的影响**：`FileContentQueryUseCase`/`FileRetrievalUseCase` 在 `status=INGESTING`
   时必须能返回一个明确的"还在处理中，请稍后重试"提示，而不是对着还没写入的 `parsedText` 抛
   `NullPointerException`——这是目标④引入的 `Attachment.status` 字段直接消费方，两个目标在这里
   是同一个 schema 改动的两个消费点，不是重复设计。`FileContentTool`（供模型调用）同样要处理
   `INGESTING`/`FAILED` 两种非 `READY` 状态，给模型一个可读的错误字符串而不是让工具调用报未知异常。

## 3. 文件大小/MIME/压缩炸弹检测/租户配额策略

现状完全空白（见 §0），这一票落地最小可用版本：

- **大小**：在现有 Spring 全局 `max-file-size: 50MB` 之外，加一个按用户/会话的**累计配额**
  （比如"单会话已上传文件总大小不超过 N MB"），在同步校验阶段用 `sizeBytes` 参数直接比较，
  不需要等解析完才知道超没超。
- **MIME allowlist**：`FileKindDetector` 现在只做"图片 vs 文本"两路由判定，不是安全校验——
  新增一个显式白名单校验（PDF/Office 全家桶/HTML/纯文本/CSV/Markdown + 现有图片扩展名），
  不在白名单内的类型在同步校验阶段直接拒绝（4xx），不要等 Tika 解析到一半才失败。
- **压缩炸弹检测**：Tika 的 `parseToString(InputStream)` 没有输出长度上限，一个小体积但解压后
  内容暴涨的 zip/Office 文档可能让解析阶段内存/CPU 失控。用 Tika 自带的写入上限能力（
  `org.apache.tika.sax.WriteOutContentHandler`/`BodyContentHandler` 的 `maxWriteLimit` 参数，或
  `Tika#parseToString(InputStream, Metadata, int)` 的 `maxLength` 重载，先验证具体哪个 API 在
  项目当前 Tika 版本下可用，不要凭空假设方法签名）给解析设一个输出字符数上限，超过上限时
  按"解析失败/内容被截断"处理并记录，而不是无限制吃完所有输出；同时给 Task 执行本身设一个
  超时（复用 Ticket 15 的 Task 超时机制），避免单个任务无限占用工作线程。
- **落地位置**：大小/MIME 校验放在 §2 的"同步部分"（快速拒绝，不占用异步资源）；压缩炸弹相关
  的解析输出上限和超时放在 §2 的"异步部分"（因为只有真正解析时才能触发）。

## 4. `Attachment` 聚合对象

现状"文件属于 conversation/turn"是隐式的：`UploadedFile` 这个 record 直接把
`conversationId`/`turnId` 当普通字段摊平在文件元数据里（[UploadedFile.java](../../src/main/java/com/agenttrail/capability/file/UploadedFile.java)），
没有一个显式建模"一次上传归属"的聚合，`turnId` 从 `null` 回填成具体值这个状态迁移
（`FileStore.linkFilesToTurn`）也只是一条 UPDATE 语句，没有聚合方法表达这个业务规则。

目标：新增 `Attachment` 聚合（`capability.fileqa.domain` 包），把"文件"和"它归属哪个
conversation/turn、当前处于什么摄取状态"合并成一个显式对象：

```java
public record Attachment(
        Long id, String userId, String conversationId, Long turnId,
        String fileName, String contentType, long sizeBytes, FileKind kind,
        AttachmentStatus status,          // 新增：INGESTING / READY / FAILED（见目标②）
        String parsedText, byte[] rawBytes, String errorCode,
        long createdAtMillis) {

    public Attachment linkedToTurn(long turnId) { ... }   // 取代裸 UPDATE，表达"这次上传归属确定了"
    public Attachment markReady(String parsedText) { ... }
    public Attachment markFailed(String errorCode) { ... }
}
```

`UploadedFile` 改名/合并进 `Attachment`（字段基本对应，新增 `status`/`errorCode` 两列，
`db/schema.sql` 的 `agent_file` 表要加对应迁移）。这一步同时是 §2 异步状态机和 §0 提到的
"文件属于 conversation/turn"隐式关系的共同落点，不是两个独立的 schema 改动。

## 5. `FilePromptFormatter` 改造成 Chat capability 的 Context Provider

**已验证现状耦合点**（见 §0）：`AgentLoopExecutor` 直接持有 `FileStore` 字段，在组装 system
prompt 时调用 `FilePromptFormatter.formatSection(fileStore.findByConversationId(...))`，在轮次
完成时调用 `fileStore.linkFilesToTurn(...)`——这是 Runtime 核心层直接 import 业务能力包类型
的真实耦合，两处都要处理，不是只处理"读"这一半。

**这一票只负责暴露 File 一侧的 Provider 接口，不重写 `ContextAssembler` 本身**——通用的上下文
组装框架（历史/记忆/附件/系统 Prompt 怎么拼装、Provider 列表怎么注册和排序）属于 Ticket 16
（迁移 Chat）/Ticket 14（Runtime 拆 6 模块，`ContextAssembler` 就是其中一块）的范围，需要和这两张票
协调接口形状，不能各写各的。本票交付的是：

```java
package com.agenttrail.capability.fileqa.application;

public interface FileContextProvider {
    /** 供 ContextAssembler 组装 system prompt 时调用，取代直接 new FilePromptFormatter + FileStore 查询 */
    String contribute(String conversationId);

    /** 供轮次完成回调调用，取代 AgentLoopExecutor 直接调 fileStore.linkFilesToTurn */
    void onTurnCompleted(String conversationId, long turnId);
}
```

`FilePromptFormatter.formatSection` 的格式化逻辑保留（这部分设计是对的，本轮/历史文件分组、
"没有文件时显式声明不要调用工具"这些细节不用改），只是从"被 `AgentLoopExecutor` 直接调用"
变成"被 `FileContextProviderImpl` 内部调用"。`AgentLoopExecutor` 侧删掉 `FileStore fileStore`
字段和 Builder 的 `fileStore(...)` 方法，改成依赖 Ticket 14/16 定义的通用 Provider 注册机制
（具体是"一个 `List<ContextProvider>`"还是"每种 Provider 各一个可选字段"，由 Ticket 14/16 决定，
本票不预先假设）。

## Testing Decisions

- **向量化失败重试**：模拟 `EmbeddingPort.embedAndStore` 抛异常，验证 Task 状态落
  `FAILED`+`errorCode`，且不会让整个 ingest 请求的 HTTP 响应报错（因为此时 HTTP 早已返回
  `202 {fileId, ingestTaskId}`）；验证按 Ticket 15 的重试策略重试后成功能把状态从 `FAILED`
  推进回 `READY`。
- **索引状态**：验证 `status=INGESTING` 时调用 `contentFor`/`FileContentTool` 返回明确的
  "处理中"提示而不是异常；`status=READY` 后能正常返回全文/检索结果；`status=FAILED` 后返回
  明确的失败原因。
- **删除/重建**：删除一个 `READY` 状态的大文件，验证向量库分块联动清理（现状 `delete()` 已经
  做对，迁移后要保留这条行为的测试）；同一 `conversationId` 重新上传同名文件，验证是新记录、
  不覆盖旧记录（当前设计里没有"覆盖"语义，这条测试确认迁移没有意外引入）。
- **租户过滤**：`belongsToUser`/`belongsToConversation` 迁移到 `FileContentQueryUseCase` 后，
  验证跨用户/跨会话读取仍然被拒绝——这条是现有安全行为，迁移后不能退化。
- **大小/MIME/压缩炸弹**：构造一个声明体积很小、扩展名合法，但内容是 zip 结构、解压/解析后
  远超正常文本体积的测试 fixture（不需要真实恶意样本，比如一个几百字节的 zip 里包一个重复填充
  到几十 MB 的文本文件，走 Office/压缩格式的 Tika 解析路径），验证解析输出长度上限生效、任务
  在合理时间内被判定失败而不是无限占用线程；MIME 不在白名单内的上传请求验证被同步拒绝；超过
  会话累计配额的上传请求验证被拒绝且不产生半成品记录。

## Out of Scope

- `RagRetrievalService` 内部检索算法（查询压缩/多查询扩展/rerank）的效果调优。
- 多模态模型（`ImageDescriptionService`）选型和 prompt 调优。
- 向量库从 PgVector 换成其它实现——`EmbeddingPort`/`RetrievalPort` 这两个端口是为了让"换实现"
  未来可行，这一票只搭端口，不做真的换库验证。
- 真实压缩炸弹攻击样本的完整免疫（这一票只做"能拦住一个明显失控的测试样本"，不追求对抗所有
  已知 zip bomb / XML bomb 变种，属于纵深防御的其中一层，不是唯一防线）。
- `Attachment` 的多租户 `tenantId` 字段——现状 schema 完全没有 `tenantId`（`refactor-blueprint.md`
  §4.5 已确认"`grep -rn tenantId src/main/java` 零命中"是全局未动工状态），这属于
  `refactor-remediation.md` Out of Scope 里明确排除的多租户改造，不在本票范围内。
