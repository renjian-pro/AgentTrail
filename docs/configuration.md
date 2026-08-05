# 配置与密钥管理

## 文件职责

| 文件 | 是否提交 | 内容 |
|---|---:|---|
| `src/main/resources/application.yml` | 是 | 模型、超时、重试、路径等安全默认值 |
| `application-local.example.yml` | 是 | 本地配置结构，所有敏感值为空 |
| `application-local.yml` | 否 | 当前机器的地址、账号、密码和 API Key |

项目统一使用 YAML，避免 IDE 对 `.properties` 使用 ISO-8859-1 导致中文乱码。

## 本地初始化

```powershell
Copy-Item application-local.example.yml application-local.yml
```

只修改根目录的 `application-local.yml`。它已被 Git 忽略，可这样确认：

```powershell
git check-ignore -v application-local.yml
```

## 覆盖顺序

1. 操作系统环境变量或部署平台 Secret；
2. 根目录 `application-local.yml`；
3. `src/main/resources/application.yml` 中的安全默认值。

不需要在 YAML 中写 `${同名环境变量:默认值}`。Spring 会把属性名转换为环境变量名，例如：

- `spring.ai.deepseek.api-key` → `SPRING_AI_DEEPSEEK_API_KEY`
- `agenttrail.minio.access-key` → `AGENTTRAIL_MINIO_ACCESS_KEY`
- `spring.datasource.url` → `SPRING_DATASOURCE_URL`

## 本地必填项

按实际启用的能力填写：

- 模型：`spring.ai.deepseek.api-key`、`spring.ai.openai.api-key`
- 搜索：`tavily.api-key`
- MySQL：`spring.datasource.url/username/password`
- PgVector：`agenttrail.pgvector.url/username/password`
- MinIO：`agenttrail.minio.endpoint/access-key/secret-key`
- Skills：`agenttrail.skills.directory`（可选）

图表和 PPT 图片复用一组 MinIO 连接，只在安全默认配置中使用不同 bucket，不再重复连接凭据。

## CI 与生产

GitHub Actions 只运行普通构建和单元测试，不启动或连接本机 MySQL、PgVector、MinIO、MCP，
也不需要业务 API Key。生产环境应通过部署平台的 Secret、Vault 或 KMS 注入敏感值。

如果密钥曾进入 Git 历史、CI 日志或聊天记录，应立即在服务提供方撤销并轮换。
