# aster-ai-workspace

Aster Cloud 的 AI 工作区服务：Spring Boot 4 + Vaadin 25 + AG-UI（agui-vaadin），以 iframe 形式嵌入 aster-cloud 的编辑器面板。

- 所有模型调用都经 aster-cloud 的 `/api/llm/*`，本服务不直连 aster-api 或模型供应商。
- 会话持久化在 PostgreSQL 库 `aster_cloud` 的 `ai_workspace` schema，默认保留 180 天。
- 所有响应带 `Content-Security-Policy: frame-ancestors <AIW_CLOUD_ORIGIN>`，不带 `X-Frame-Options`。

## 本地运行

前置：JDK 21、Node 24（Vaadin 前端构建）、Maven 3.9+、本地已安装 `com.wontlost:agui-vaadin:0.2.0`。

```bash
export AIW_DB_URL='jdbc:postgresql://localhost:5432/aster_cloud?currentSchema=ai_workspace'
export AIW_DB_USERNAME=aster_ai_user
export AIW_DB_PASSWORD=...
mvn spring-boot:run          # 开发模式，http://localhost:8080
```

测试（H2 内存库，无需 Postgres）：

```bash
mvn -B -ntp verify
```

生产构建（前端打进 jar，产物 `target/aster-ai-workspace-*.jar`）：

```bash
mvn -B -ntp -Pproduction package
```

健康探针：`/actuator/health/liveness`、`/actuator/health/readiness`；指标：`/actuator/prometheus`。

## 环境变量

| 变量 | 含义 | 默认值 |
| --- | --- | --- |
| `AIW_CLOUD_URL` | aster-cloud 基础地址（服务端调用 `/api/llm/*`） | `https://aster-lang.cloud` |
| `AIW_CLOUD_ORIGIN` | aster-cloud origin（frame-ancestors 与 postMessage 目标） | `https://aster-lang.cloud` |
| `AIW_HMAC_KEY` | 服务到 cloud 的 HMAC-SHA256 签名密钥 | 空 |
| `AIW_HANDOFF_PUBLIC_KEY` | 校验 handoff 令牌的 Ed25519 公钥（base64 X.509 DER） | 空 |
| `AIW_TICKET_KEY` | 短期票据密钥 | 空 |
| `AIW_RETENTION_DAYS` | 会话与审计保留天数 | `180` |
| `AIW_DB_URL` / `AIW_DB_USERNAME` / `AIW_DB_PASSWORD` | 数据库连接 | 见 `application.yml` |
| `AGUI_PRO_LICENSE` | agui Pro/EE 许可证密钥；未设置时 Pro 功能降级，仅基础聊天 | 空 |

## 备注

- Pro/EE 模块（0.1.2）基于 agui-vaadin 0.1.1 构建，`pom.xml` 在 dependencyManagement 中固定 0.2.0。
- 启用调度后存在多个 `AsyncTaskExecutor`，`ExecutorConfig` 提供 `@Primary` 的虚拟线程执行器供 AG-UI 使用。
