# Ticket 6／9：部署侧 Prometheus/Grafana/Langfuse + SLO 面板 + 告警规则 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。`Blocked by`
> [Ticket 5](backend-phase3-governance-ticket-05.md)——没有指标端点和 OTLP 输出，这一票没有
> 东西可抓取/可展示。

## 0. 范围边界

**这一票只做**：本机可跑的 Prometheus + Grafana + Langfuse 三个容器 + 抓取/导出配置 +
三个 SLI 面板 + 告警规则。**不做**应用本身的容器化（项目目前**没有任何 `docker-compose.yml`**，
`mvn spring-boot:run`/IDE 直接跑，这是 Phase 11"本地开发环境 Docker Compose: app+MySQL+
Redis+PgVector"的范围，这一票不提前做那件事，只解决"给已经在跑的应用配一套可观测性容器"）。

## 1. 现状：项目目前没有 docker-compose.yml，这一票是第一次引入

`docker-compose.yml` 放在仓库根目录，这一票只声明 `prometheus`/`grafana`/`langfuse` 三个
service，应用本身继续按现有方式跑在宿主机上（不进容器网络）——Prometheus 抓取应用指标端点
时不能用容器名互相解析，要用 `host.docker.internal:8080`（Docker Desktop for Windows/Mac
支持这个特殊 DNS 名；**先验证** CI/其他开发者环境是不是用 Docker Desktop，Linux 宿主机上
`host.docker.internal` 默认不可用，需要 `extra_hosts` 显式映射或者改用宿主机真实 IP）。

## 2. `docker-compose.yml`

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./deploy/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"   # Linux 宿主机兼容，Windows/Mac Docker Desktop 上是空操作

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin   # 仅限本地开发，不是生产配置
    volumes:
      - ./deploy/grafana/provisioning:/etc/grafana/provisioning:ro
    depends_on:
      - prometheus

  langfuse:
    image: langfuse/langfuse:latest
    ports:
      - "3001:3000"   # 和 Grafana 的 3000 冲突，改映射到宿主机 3001
    environment:
      - DATABASE_URL=postgresql://langfuse:langfuse@langfuse-db:5432/langfuse
      - NEXTAUTH_SECRET=local-dev-only-not-a-real-secret
      - SALT=local-dev-only-not-a-real-secret
    depends_on:
      - langfuse-db

  langfuse-db:
    image: postgres:16
    environment:
      - POSTGRES_USER=langfuse
      - POSTGRES_PASSWORD=langfuse
      - POSTGRES_DB=langfuse
    volumes:
      - langfuse-db-data:/var/lib/postgresql/data

volumes:
  langfuse-db-data:
```

**先验证**：`langfuse/langfuse` 镜像的实际必需环境变量列表以官方文档为准，上面这份是最小
猜测集，不保证一次 `docker compose up` 就能正常跑起来——Langfuse 本身版本迭代较快，环境变量
名称可能变化，实现时要对照 Langfuse 官方 self-hosting 文档核实一遍，不要照抄这份文档就当作
最终答案。

## 3. `deploy/prometheus.yml`

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: agenttrail
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

## 4. Grafana 面板：三个 SLI

用 `deploy/grafana/provisioning/dashboards/` 下的 JSON 声明式配置（Grafana 的 provisioning
机制，容器启动时自动加载，不需要手工在 UI 里点）。三个面板对应总纲定的 SLI：

1. **TTFT P95**：`histogram_quantile(0.95, rate(agenttrail_llm_ttft_seconds_bucket[5m]))`
   （具体指标名以 Ticket 5 实际落地的命名为准——**先核对** Ticket 5 完成后 `/actuator/prometheus`
   输出的真实指标名，不要在这里假设一个 Ticket 5 里没有的名字）
2. **端到端首响应 P95**：同样是 histogram_quantile，指标名对应 Ticket 5 里的"总耗时"Timer
3. **工具调用成功率**：`sum(rate(agenttrail_tool_duration_seconds_count{outcome="success"}[5m]))
   / sum(rate(agenttrail_tool_duration_seconds_count[5m]))`

## 5. 告警规则

Grafana Alerting（不是独立的 Alertmanager，避免多一层部署复杂度——单机开发场景 Grafana
内置的 Alerting 已经够用）：

- TTFT P95 > 2s 持续 5 分钟 → 触发
- 端到端首响应 P95 > 5s 持续 5 分钟 → 触发
- 工具调用成功率 < 99% 持续 5 分钟 → 触发

**验收标准是"规则能触发、Grafana UI 上能看到告警状态变化"**，不接真实 Slack/邮件通道——总纲
已经明确这一点，人为制造一次超阈值场景（比如临时把某个工具改成必定失败）来验证规则真的会触发，
不能只是"配置文件写对了"就算完成，必须实测触发一次。

## 6. Testing Decisions

这一票大部分是部署配置，不是 Java 代码，"测试"以人工验收步骤为主，写成一份可执行的 checklist
而不是自动化测试：

- [ ] `docker compose up -d` 三个 service 都能正常启动，无重启循环
- [ ] Prometheus UI（`localhost:9090`）的 Targets 页面显示 `agenttrail` job 状态是 `UP`
- [ ] Grafana（`localhost:3000`）能看到三个 SLI 面板，且有真实数据点（不是空面板）
- [ ] 人为制造一次工具调用失败率升高，验证对应告警规则在 5 分钟内变成触发状态
- [ ] Langfuse（`localhost:3001`）能看到至少一条 trace 记录（验证 OTLP 导出链路通了，不只是
      Prometheus 那一侧通）

## Out of Scope

- 应用本身容器化（Phase 11）
- 真实告警通道对接（Slack/邮件）
- Langfuse 的用户账号体系/多租户配置（这次是单人本地开发场景）
