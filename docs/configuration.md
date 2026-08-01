# 配置与密钥管理

## 原则

- 仓库只保存配置结构和安全默认值，不保存真实地址、账号、密码、Token 或 API Key。
- 本机配置放在 `src/main/resources/application.properties`，本机密钥放在仓库根目录的
  `secrets.properties`；这两个文件都被 `.gitignore` 排除。
- 环境变量优先级高于本地配置文件，部署环境应使用平台自己的 Secret 管理能力注入。
- GitHub Actions 只运行不依赖本地基础设施的默认测试集，不启动或连接 MySQL、Postgres、
  MinIO、MCP，也不需要业务 API Key。

## 本地初始化

```powershell
Copy-Item src/main/resources/application-example.properties src/main/resources/application.properties
Copy-Item secrets.properties.example secrets.properties
```

然后只修改两个被忽略的本地文件。可用以下命令确认它们不会进入提交：

```powershell
git check-ignore -v src/main/resources/application.properties secrets.properties
```

## 配置优先级

从高到低：

1. 操作系统环境变量或部署平台 Secret；
2. 仓库根目录的 `secrets.properties`；
3. 本机 `application.properties` 中的非敏感默认值。

`application.properties` 通过
`spring.config.import=optional:file:./secrets.properties` 加载本地密钥文件。

## 必需的敏感或环境专属配置

| 能力 | 变量 |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| DashScope/Qwen/Embedding/文生图 | `DASHSCOPE_API_KEY` |
| Tavily 搜索 | `TAVILY_API_KEY` |
| MySQL | `AGENTTRAIL_DB_URL`、`AGENTTRAIL_DB_USERNAME`、`AGENTTRAIL_DB_PASSWORD` |
| PgVector | `AGENTTRAIL_PGVECTOR_URL`、`AGENTTRAIL_PGVECTOR_USERNAME`、`AGENTTRAIL_PGVECTOR_PASSWORD` |
| MinIO | `AGENTTRAIL_MINIO_ENDPOINT`、`AGENTTRAIL_MINIO_ACCESS_KEY`、`AGENTTRAIL_MINIO_SECRET_KEY`、`AGENTTRAIL_MINIO_BUCKET`、`AGENTTRAIL_PPT_IMAGE_MINIO_BUCKET` |
| 本地 mcp-echarts | `MCP_ECHARTS_URL` |

业务能力未启用时，对应 API Key 可以留空；需要数据库或对象存储的能力必须提供完整连接信息。

## 非敏感可调配置

模型、超时、重试、MCP 地址、PPT 路径、bucket 名称及 DeepResearch 并发上限等配置统一列在
`application-example.properties` 中，并全部支持环境变量覆盖。新增配置时必须同时更新示例文件
和本文档；新增密钥时还必须更新 `secrets.properties.example`，且不得提供真实默认值。

Skills 目录是可选的本机路径，只有启用该能力时才在本地 `application.properties` 中取消
`agenttrail.skills.directory` 的注释并配置 `AGENTTRAIL_SKILLS_DIRECTORY`；不要提交绝对路径。

## 密钥轮换

如果真实密钥曾进入 Git 提交、CI 日志或聊天记录，应立即在服务提供方撤销并重新生成；仅从当前
文件删除并不能让历史中的密钥失效。轮换后只更新本机 Secret 文件或部署平台 Secret。
