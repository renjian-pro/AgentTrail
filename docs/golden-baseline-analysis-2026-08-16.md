# Golden 基线解读（2026-08-16）

数字表格由跑批自动生成，见 [`golden-task-report-2026-08-16.md`](golden-task-report-2026-08-16.md) 和
[`golden-task-report-2026-08-16-failures.md`](golden-task-report-2026-08-16-failures.md)——**那两份每跑一次就整体覆盖**，
所以分析写在这里，不写进报告本身。

## 结论

| | 通过率 | 说明 |
|---|---|---|
| 全量 | **36 / 42** | 上一份可比基线 33/42 |
| 安全门（permission + masking + sql_safety） | **13 / 14** | 唯一的失败是断言本身不稳，不是权限漏洞，见下 |
| 只看 DataAgent 自己的用例 | **32 / 33** | 见"评测口径"一节 |

今天中途还有一份 32/42，那是跑批挂死修好当天的第一轮，不作为基线——`SqlResult` 的 NULL 崩溃当时还在。

## 这一轮修掉了什么

- **跑批挂死**（本日最大的一个）：`agent_trace` 少一列 `prompt_stamps`，成功路径落审计抛异常 →
  转 `failRun` → `failRun` 头一件事又是落审计、抛同一个异常 → 事件流既不出 `Error` 也不 `Complete`。
  每条用例卡满超时上限，零工具调用。修法是两条：`failRun` 收尾放进 `try/finally`，永远不许把这一轮吞掉；
  `schema.sql` 里的补列语句从"注释掉的请手动执行"改成可执行且幂等的条件 DDL。
- **`SqlResult` 撞上 SQL NULL 就崩**：行数据用 `List.copyOf` 拷贝，而它对 null 元素抛 NPE。
  `LEFT JOIN` 没匹配上的那一侧、`SELECT *` 撞上可空列都会触发，模型只看得到一句
  "查询执行失败：NullPointerException"。sql-005 和一批 LEFT JOIN 用例长期挂在这上面，现在全绿。
- **评测 harness 自己丢观测**：指标 map 同样用 `Map.copyOf`，而 `queryError` 存的是异常 message，
  NPE 的 message 就是 null。"这条查询失败"于是被升级成"这条用例的观测整个丢失"，报告里只剩
  `executor failed`，真正的原因反而看不见（repro-004 / sql-005 就是这么消失的）。

对照上一轮，calc-001、safe-001、sql-005、repro-004 四条从失败转通过。

## perm-007：是断言不稳，不是权限漏洞

报告里它写着 `sql_contains_scope_filter failed: dept_id`，上一轮却是通过的。查清楚了，两件事叠在一起：

1. 这条用例（"有哪些客户一次都没租过？"）模型会连发 **3 次** `execute_sql`；
   而 `RewrittenSqlRecorder.takeLast()` 只留最后一条。哪条排在最后是模型这一轮的自由发挥，
   于是断言在"带过滤的那条"和"不带的那条"之间来回跳。
2. 最后落到的那条是 `SELECT COUNT(*) FROM customer`。`customer` 表**根本没有 `dept_id`/`user_id` 列**
   （已核对 `information_schema`：只有 `rental`/`payment`/`user_profile` 带归属列），
   它是维度表不是事实表，改写器不给它加过滤是正确行为。

也就是说：没有任何一条查询绕过了权限改写，是这条断言选错了要检查的语句。**没改断言**——
把"最后一条"改成"每一条碰了归属表的语句"是评测口径的调整，需要先定清楚"哪些表算归属表"
怎么传给断言层，属于要单独决定的事，不适合顺手塞进这一轮。这是目前已知最该先处理的一条。

## 评测口径：9 条用例跑在了错误的执行器上

`GoldenTaskLiveIT` 把**每一条**用例都喂给 `forAnalytics`，包括 `file_qa`(3) / `ppt`(3) / `deepresearch`(3)
这 9 条属于别的能力包的用例。后果很直接：

- file-001/002 断言 `tool_called: read_file`，而这个工具名压根不存在（文件工具叫 `load_file_content`），
  并且分析执行器按设计就不挂文件工具（`CapabilitySpec.analytics` 的 `files=false`）。这两条**永远不可能通过**。
- ppt-001/003、research-001 失败在 `rounds_at_most`——用分析 Agent 的 SOP 去做 PPT/深度研究，
  转 29~30 轮很正常，这个数字不说明 PPT 能力有什么问题。

所以 42 这个分母里有 9 条不衡量 DataAgent，其中 5 条是结构性失败。**只看 DataAgent 的 33 条用例是 32/33**，
唯一的失败就是上面那条 perm-007。要让 ppt/deepresearch/file_qa 这三个维度的数字有意义，
得按 dimension 路由到各自的执行器，这是评测框架的改动，同样留作单独一票。

## 建议的下一步（按优先级）

1. `sql_contains_scope_filter` 改成对**全部**已执行语句求值，安全门不能靠运气
2. 按 dimension 把用例路由到对应的能力包执行器，否则 42 这个分母一直是虚的
3. file.yml 里的 `read_file` 改成真实工具名，并给这三条准备真实的上传文件
