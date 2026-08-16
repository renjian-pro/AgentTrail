# 前端能力模型重构 拆票总览

> 这是三张票的索引和依赖图。**开工前先读这一页**，再去读要做的那张票。
>
> 需求 Spec：[`frontend-capability-model.md`](frontend-capability-model.md)
> 上位需求：[`requirements.md`](../../requirements.md) §7（R13–R17）

## 现状

- `ChatView.vue`（306 行）把四个模式做成同一排 chip，作用域完全不同的三种东西混在一个视觉层级
- `ChatView.vue:100` 发送后 `pendingMode.value = undefined`，模式是消息级一次性的
- `chat.ts` 的会话状态里没有 Agent 概念，会话列表也没有 Agent 标记

## 三张票

全部已发布为 GitHub issue（`ready-for-agent` 标签）。

| # | issue | 票 | 做什么 | 依赖 | 规模 |
|---|---|---|---|---|---|
| F6 | [#92](https://github.com/renjian-pro/AgentTrail/issues/92) | 会话级 Agent 绑定 | `chat` store 加 `agentKind`；锁定点＝首条消息；`AgentHeader` 常驻标识；会话列表 Agent 图标 | — | 大 |
| F7 | [#93](https://github.com/renjian-pro/AgentTrail/issues/93) | 三层视觉分组 | PPT／深度研究从模式 chip 改为动作按钮（无 active 态）；联网搜索归位为 toggle；输入框动作区重排 | F6 | 中 |
| F8 | [#94](https://github.com/renjian-pro/AgentTrail/issues/94) | 数据问题引导卡片 | 关键词识别（纯函数）＋ inline 卡片 ＋「用数据分析新建会话」 | F6 | 中 |

## 依赖图

```
F6 会话级 Agent 绑定（地基：store 结构 + 锁定语义）
 ├─ F7 三层视觉分组
 └─ F8 引导卡片
```

F7 和 F8 在 F6 完成后互不阻塞，可并行。

**关键路径**：`F6 → (F7 | F8)`。

## 建议实现顺序

1. **F6**——它改的是 store 结构和会话语义，后两张票都在它建立的模型上做文章；也只有它单独就能止住"编造数据"那条静默失败链路
2. **F7**——纯视觉与交互重排，不碰数据流，跑得快
3. **F8**——新增组件 + 纯函数识别，独立性最强，放最后当收尾

## 全局约束（每张票都适用）

### 技术栈

- Vue 3 `<script setup>` + TypeScript + Pinia，**不引入新依赖**
- 测试用 `vitest` + `@vue/test-utils`，参照 `SqlToolCard.spec.ts` / `ResearchReportCard.spec.ts` 的既有风格
- **不重写 `ChatView.vue`**——它有 306 行且带着大量踩坑注释，每张票只做精确的局部改动，改动点在各自票里列到行

### 不碰后端

这三张票**不改任何后端代码、不新增接口**。以下都是独立推进的后端工作，前端只在接口上预留位置，后端没就位时不显示假信息：

| 后端需求 | 影响哪张票 |
|---|---|
| R1 六个分析工具改常驻 | 无（但它是数据分析会话真正可用的前提） |
| R15 `ConversationDigest` | F8 的「带上下文摘要」、任务的上下文提示行 |
| R16 图表数据来源校验 | 无（本期前端不做拒绝卡片） |

### 必须钉住的回归

**同一会话连续两条消息带的 `mode` 必须一致**——这是本次修复的核心，必须有断言钉住，否则改回去了没人发现。

现有 `ChatView.spec.ts` / `chat.spec.ts` / `interaction.spec.ts` 全部仍需通过。

### 有意识的取舍（不是遗漏，别"顺手补上"）

- **存量会话一律标为普通对话，不做迁移**——理由见 spec 3.4。看到历史会话里有 `execute_sql` 记录不要去写迁移脚本
- **默认 Agent 是普通对话**，不弹选择框——见 spec 3.3
- **引导卡片用关键词不用 LLM**——见 spec 3.5，沿用踩坑点 #52 的结论
