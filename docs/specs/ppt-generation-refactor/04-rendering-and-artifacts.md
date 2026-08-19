# Python 渲染、质量验证与产物

> 上级文档：[PPT 生成能力重构需求文档](../ppt-generation-refactor-requirements.md)
> 需求范围：R22–R25

## 1. 边界

RENDER 只负责把已验证的页面 Schema、模板版本与素材引用转换为 PPTX；VERIFY 负责决定产物是否有资格进入 SUCCESS。文件被创建不等于生成成功。

## 2. R22：复杂模板复制

复制模板页时必须正确处理：

- shape 和 group shape；
- 图片、背景和媒体关系；
- 超链接；
- 图表和嵌入对象；
- notes 和必要的布局引用；
- 资源 relationship ID 重映射。

不支持的元素必须在模板校验阶段明确拒绝，不能在生成后静默丢失。页复制逻辑必须有包含真实关系资源的固定测试模板。

## 3. R23：填充失败可见

- 必填 shape 缺失使 RENDER 或 VERIFY 失败；
- 可选 shape 缺失允许 warning 降级；
- 填充文本尽量保留模板 run 样式；
- 图片替换保持预期裁剪与宽高比；
- 字体缺失使用明确替代规则并产生 warning；
- 超长文本优先在 Schema 阶段压缩，渲染器只做确定性兜底；
- 渲染脚本标准输出与错误输出需要持续消费，避免子进程阻塞；
- 超时或取消时终止进程及子进程树。

## 4. R24：渲染后 VERIFY

产物进入 SUCCESS 前至少验证：

- PPTX 可以重新打开；
- 页数符合 Schema；
- 必填 shape 已填充；
- 图片和关系引用可解析；
- 文件大小和 checksum 合法；
- 没有残留模板页；
- 没有明显空白内容页；
- 关键文字未被完全截空。

条件允许时把每页渲染为预览图，检测文本溢出、遮挡和越界。自动修复必须限制次数，超过预算进入 FAILED，不得无限执行 `VERIFY → RENDER`。

VERIFY 的结构检查应作为硬门禁；依赖视觉模型的主观质量检查可以分级启用，并记录模型版本与 warning。

## 5. R25：产物对象存储

渲染并通过 VERIFY 后上传对象存储，数据库只保存 artifactId。上传成功前不得推进 SUCCESS；上传后本地临时目录按保留策略清理。

artifact 元数据至少包含：

- artifactId；
- ownerId/taskId；
- objectKey；
- checksum；
- size；
- contentType；
- createdAt；
- retentionStatus。

下载时通过鉴权代理或临时签名 URL 访问。签名 URL 不写入 checkpoint、会话历史或长期日志。

## 6. 关键异常窗口

### 6.1 文件已渲染，checkpoint 未提交

重跑 RENDER 可以覆盖同一临时目标或根据 attempt 写入隔离目标；旧临时文件由清理任务回收，不能因此误判成功。

### 6.2 对象已上传，SUCCESS 未提交

上传使用可重放的 artifact 标识。恢复时先校验对象 checksum，已存在且匹配则复用，再提交 SUCCESS，不能重复上传无限版本。

### 6.3 SUCCESS 后下载失败

下载故障是访问产物的独立错误，不得把已经成功的生成任务改为 FAILED。修复对象访问或重新签名即可。

## 7. 验收要点

- 图片、背景、组合元素和关系复制后仍可正常打开；
- 必填 shape 缺失不会静默成功；
- 渲染超时和取消都能终止进程树；
- 空白页、残留模板页、关系损坏会阻止 SUCCESS；
- 任意应用实例都能下载已经生成的产物；
- 对象已上传但提交中断后可幂等恢复。
