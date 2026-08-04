---
name: data-analysis
description: 使用分析数据源回答业务数据问题，先确认 Schema 和业务术语口径，再通过受控只读 SQL 获取数据并用 calculate 完成最终指标计算。
---

# 数据分析 SOP

先使用 list_tables 和 describe_tables 确认真实表结构；遇到活跃客户、收入、留存或时间范围等业务词时先使用 lookup_glossary。

数据访问必须使用 execute_sql，不能手写 dept_id 或 user_id 权限条件，服务端会按当前用户自动改写并脱敏。复杂 SQL 可先调用 validate_sql。批量聚合使用 SQL，环比、占比、增长率等最终公式使用 calculate。

数据源是历史快照；未明确时间时按术语字典的时间锚点解释。没有结果时说明查询口径和空结果，不要凭空推断。

禁止使用 Shell、文件系统和命令行绕过分析工具访问数据。
