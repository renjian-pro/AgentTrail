# Ticket 8／9：评测前端页面 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。`Blocked by`
> [Ticket 7](backend-phase3-governance-ticket-07.md)（依赖它的三个后端端点）。这是前端票，
> 技术栈 Vue3 + TS + Vite，对齐 `docs/specs/phase2a-auth/frontend-phase2-auth.md` 已经定的前端工程约定。

## 0. 范围边界

**这一票只做**：一个评测页面（触发评测、看进度、看历史报告、两次报告对比），挂在现有的
`admin` 子应用下（已核实 `frontend/src/admin/views/` 现在有 `RoleListView.vue`/
`UserManagementView.vue` 两个页面，`admin/api/sys-api.ts` 是现有唯一的 API 模块，这一票沿用
这套命名/组织方式）。**不做**压缩 trade-off 曲线的可视化（Out of Scope，见下）。

## 1. 新增文件

```
frontend/src/admin/api/evaluation-api.ts     -- 对齐 sys-api.ts 的写法：三个函数分别对应
                                                  POST /run、GET /{taskId}、GET /history
frontend/src/admin/views/EvaluationView.vue  -- 页面本体
```

**先验证**：`admin` 子应用的路由注册方式（现有两个页面是怎么挂到路由表和侧边菜单上的，
沿用同样的接入方式，不要另起一套路由组织逻辑）。

## 2. 页面功能拆解

### 2.1 触发评测

一个"开始评测"按钮，点击后调用 `POST /agent/v1/evaluation/run`，拿到 `taskId` 后开始轮询
（沿用 DeepResearch/PPT 前端轮询组件的写法——**先验证**这两个能力包的前端轮询逻辑现在长什么样，
是不是已经抽成了一个可复用的 composable，如果是，这一票直接复用，不要重新写一遍轮询逻辑）。

### 2.2 进度展示

评测跑一次可能要几分钟到十几分钟（Ticket 7 已经说明），页面要展示"已完成 N/总数"这种粒度的
进度，不能是一个转圈圈直到全部跑完才有反馈——**这依赖 Ticket 7 的后端进度上报粒度**，如果
Ticket 7 落地时只支持"跑完/没跑完"两态（类似 DeepResearch 现在的粒度），这一票的进度展示
也只能做到同样粗的粒度，不能凭空要求后端没有的数据。

### 2.3 报告展示

评测完成后展示：总体通过率、按 `dimension` 分组的通过率、失败 case 列表（点开能看到
`question`/`reason`/`actualResult` 这些字段，直接对应 `GoldenTaskReport.GoldenObservation`
的字段形状，后端 JSON 序列化后前端 TS 类型定义要和它对齐）。

### 2.4 历史报告 + 两次对比

`GET /agent/v1/evaluation/history` 返回报告列表，选两份做并排对比——**这次先做最简单的对比
形式**：两列并排展示总体通过率和按 dimension 的通过率差值，不做逐 case 级别的 diff 高亮
（那是更精细的 UI 工作，超出这一票"能用"的范围，Further Notes 里记一下可以作为后续增强）。

## 3. Testing Decisions

前端这一票以人工验收为主（这个项目现有的前端测试基础设施覆盖到什么程度**先验证**，不要假设
有一整套前端单元测试框架已经就绪）：

- [ ] 点击"开始评测"能看到进度更新，最终展示完整报告
- [ ] 失败 case 能展开看到详情
- [ ] 历史报告列表能正确加载，选两份能看到对比视图
- [ ] 网络请求失败/后端 500 时页面有明确的错误提示，不是白屏或者控制台报错但 UI 无反应

## Out of Scope

- 压缩 trade-off 曲线的图表展示
- 逐 case 级别的报告 diff
- 移动端适配（这是内部维护者用的页面，不需要）
