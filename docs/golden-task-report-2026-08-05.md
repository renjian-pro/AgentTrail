# Golden Tasks —— 2026-08-05 首次真实跑通

> 之前 `GoldenTaskRunner`/`GoldenTaskRunnerTest` 只验证了 harness 本身（loader/assertion/report
> 拼装），喂给它的是写死"永远成功"的假 executor，从没有真正调用过 `AgentLoopExecutorFactory
> .forAnalytics()`，因此从没产出过一份真实的准确率数字。这是第一次真的接上真实 Agent + 真实
> LLM + 真实 sakila 数据跑通（见 `GoldenTaskLiveIT`）。跑了 4 轮（前 3 轮边跑边修 fixture 和
> harness 本身的 bug，第 4 轮是修完之后、fixture 集合定稿后的完整一轮），下面是**最后一轮**的
> 详表；4 轮的对比才是这次评测最大的产出，写在下面。

## 结论摘要

1. **发现并修了三条明显写错的 fixture**（不是产品 bug，是评测集本身的 placeholder 从没被验证过）：
   - `sql-001`/`sql-003`：`scalar_equals value: 1` 断言"admin 查询租赁总量/付款总额应该等于 1"——
     sakila 的 rental 有 16044 行、payment 总额 67416.51，这两个数字显然是占位符没改。改成
     `result_matches_reference`（独立跑一遍 `reference_sql` 比对结果，不依赖会随数据变的硬编码值）。
   - `sql-002`：`row_count_equals value: 1` 断言"按客户分组统计应该恰好 1 行"——GROUP BY 599 个
     客户显然不可能是 1 行，实测被 `max-rows: 200` 截断在 200 行左右。改成 `row_count_between`。
   - `perm-001`：断言 admin 的查询也应该被注入 `dept_id` 过滤——和 `DataScope.ALL` 的语义定义
     直接矛盾（`DataScopeContext.java` 明确写了"ALL 不需要部门列表"）。改成 `result_matches_reference`，
     反过来验证"admin 的 ALL scope 真的看得到全部数据、和不加权限过滤的参考查询结果一致"。
   - 顺手把 `perm-006`/`perm-007` 的提问措辞从抽象的"OR 条件的权限改写"/"LEFT JOIN 不丢主表"
     改成贴近真实用户会问的具体数据问题，并新增 `sql-008`（"一共有多少条租赁记录"，唯一断言
     `tool_called: execute_sql`）作为长期探针。
2. **最重要的发现：ToolSearch 对 `execute_sql` 的召回不稳定，比"SQL 写错了"更根本。**
   看失败详情（`golden-task-report-2026-08-05-failures.md`）会发现好几轮里 `empty-001`/
   `empty-003`/`perm-003`/`perm-004`/`mask-003`/`sql-006` 这些失败案例的 `toolCalls` 几乎都只有
   `search_tools`（有的还加一次 `lookup_glossary`），**从没有真正调用到 `execute_sql`**——模型
   反而回答"目前没有找到能查询数据的工具"，或转而向用户反问澄清问题。这不是 SQL 生成质量问题，
   是延迟工具发现（issue #0.6 ToolSearch，HYBRID 关键词优先+LLM 兜底）没有稳定地把 `execute_sql`
   召回给模型看。
3. **4 轮之间同一条 fixture 的结果大幅波动，这本身就是"reproducibility"维度测出来的真实信号，
   不是测试噪音**——这是这次评测第二重要的发现：
   - `perm-002`（analyst_test，之前 3 轮全过）在第 4 轮突然失败；`perm-007`（改措辞后）第 4 轮
     首次通过；`sql-002`（已修复断言）第 4 轮又以另一种方式失败（`row_count_between failed:
     -9223372036854775808`，说明这轮的 SQL 提取/复算链路整个没跑通，不是数字算错）。
   - **"executor failed: null" 这个报错在 4 轮里出现了 3 次，命中的 case 每次都不一样**
     （`repro-004`→`repro-004`→`sql-005`），说明它不是绑定某个特定 fixture 的 bug，是一个
     ~3-4% 概率、和具体问题无关的随机瞬时失败（`GoldenTaskRunner.run()` 只记录了
     `failure.getMessage()`，没记堆栈，现在还看不出根因，需要下次改造成打印完整堆栈才能继续查）。
   - 结论：ToolSearch 召回 + 模型对同一问题的处理路径本身就不是完全确定性的。当前的
     "reproducibility" 维度断言（`rounds_at_most`）测不出"结果内容不一致"这种波动，只测得出
     "轮次没有失控"——这是评测设计上一个值得后续补的缺口。
4. `sql-006`（"计算环比增长"期望调用 `calculate` 工具）持续失败是因为 fixture 本身没给具体数字，
   模型反问用户要数据——这是合理行为，这条 fixture 的断言前提本身有问题，不是产品缺陷。

## 未来的最小后续动作（不在本次范围内，如实记录留痕）

- 调查 ToolSearch 对 `execute_sql` 的召回稳定性本身（关键词打分阈值？候选池大小？）——这是比
  继续修 fixture 更有价值的下一步，`sql-008` 只是一个持续暴露这个问题的探针，不是修复。
- `GoldenTaskLiveIT` 的 `executor failed: null` 分支需要改成打印完整异常堆栈（不只是
  `getMessage()`），下次复现时才有材料查根因。
- `perm-006` 涉及 `WHERE status = 1 OR amount > 100` 这种 OR 条件——roadmap 里 #26 号踩坑点
  专门讲过"运算符优先级绕过"这个真实陷阱，这条 fixture 反复失败，值得对照检查
  `DataScopeRewriter` 对 OR 条件的改写是不是真的把权限条件正确地整体括号包裹了，而不是想当然
  地认为 #26 已经完全解决。

## 详表（第 4 轮，最终 fixture 定稿后）：16/28

| id | dimension | passed | rounds | elapsedMs | reason |
|---|---|---:|---:|---:|---|
| cost-001 | cost | true | 2 | 5218 |  |
| cost-002 | cost | true | 4 | 8054 |  |
| empty-001 | empty_result | false | 5 | 9405 | output_contains_any failed: [没有匹配数据, 空结果] |
| empty-002 | empty_result | true | 2 | 4432 |  |
| empty-003 | empty_result | false | 2 | 4334 | output_contains_any failed: [查询成功, 没有匹配数据] |
| mask-001 | masking | true | 3 | 13580 |  |
| mask-002 | masking | true | 4 | 7240 |  |
| mask-003 | masking | false | 2 | 4018 | output_contains_any failed: [********, 脱敏] |
| mask-004 | masking | true | 3 | 6093 |  |
| perm-001 | permission | false | 4 | 7520 | result_matches_reference failed |
| perm-002 | permission | false | 3 | 5246 | sql_contains_scope_filter failed: dept_id |
| perm-003 | permission | false | 3 | 5819 | sql_contains_scope_filter failed: dept_id |
| perm-004 | permission | false | 3 | 6313 | sql_contains_scope_filter failed: dept_id |
| perm-005 | permission | true | 17 | 31718 |  |
| perm-006 | permission | false | 12 | 18548 | sql_contains_scope_filter failed: dept_id |
| perm-007 | permission | true | 5 | 8173 |  |
| repro-001 | reproducibility | true | 3 | 5414 |  |
| repro-002 | reproducibility | true | 3 | 4775 |  |
| repro-003 | reproducibility | true | 3 | 5794 |  |
| repro-004 | reproducibility | true | 8 | 11486 |  |
| sql-001 | sql_correctness | true | 7 | 9853 |  |
| sql-002 | sql_correctness | false | 3 | 6198 | row_count_between failed: -9223372036854775808 |
| sql-003 | sql_correctness | false | 3 | 4657 | result_matches_reference failed |
| sql-004 | sql_correctness | true | 3 | 5411 |  |
| sql-005 | sql_correctness | false | 0 | 0 | executor failed: null; tool_called failed: lookup_glossary |
| sql-006 | sql_correctness | false | 2 | 4081 | tool_called failed: calculate |
| sql-007 | sql_correctness | true | 2 | 4902 |  |
| sql-008 | sql_correctness | true | 8 | 10800 |  |
