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
| `AIW_GDPR_HMAC_KEY` | cloud → 本服务 GDPR 导出 / 清除（`POST /internal/gdpr/{export,erase}`）的 HMAC 密钥，与 cloud 的 `AI_WORKSPACE_GDPR_HMAC_KEY` 相同；未设置时两个端点返回 503 | 空 |
| `AIW_RETENTION_DAYS` | 会话与审计保留天数 | `180` |
| `AIW_DB_URL` / `AIW_DB_USERNAME` / `AIW_DB_PASSWORD` | 数据库连接 | 见 `application.yml` |
| `AGUI_PRO_LICENSE` | agui Pro/EE 许可证密钥；未设置时 Pro 功能降级，仅基础聊天 | 空 |

## 界面

- `/`：工作台。左栏为 Pro 会话侧栏（license 覆盖 `conversations` 时）或本会话历史；默认「问答」，「生成 / 建议」可粘贴策略源码作为上下文。
- `/panel?policyId=`：编辑器侧栏（iframe），默认线程 `policy:{policyId}`、默认「建议」；经 `src/main/frontend/aster-bridge.ts`
  与 cloud 父页面以 postMessage v1 交换上下文、写回（插入 / 替换）与重新交接，只向 `AIW_CLOUD_ORIGIN` 发送。
- 未交接的访问一律跳回 cloud `/api/ai-workspace/handoff` 重签。
- Pro 闸门：仅当 `License.status()` 有效且 key 覆盖该功能时启用会话持久化 / 审计；否则不调用任何 Pro 绑定器，历史保存在 Vaadin 会话内存。
- 数据库不可用时对话照常，界面提示「本次对话未能保存」。
- 界面文案：`src/main/resources/i18n/ui_{en,zh,de,hi}.properties`，语言取交接时的用户 locale。

## 备注

- Pro/EE 模块（0.1.2）基于 agui-vaadin 0.1.1 构建，`pom.xml` 在 dependencyManagement 中固定 0.2.0。
- 启用调度后存在多个 `AsyncTaskExecutor`，`ExecutorConfig` 提供 `@Primary` 的虚拟线程执行器供 AG-UI 使用。

## 镜像与发布

- `Dockerfile` 构建 arm64 镜像（`docker.io/wontlost/aster-ai-workspace`），以 uid 1001 运行，根文件系统只读，仅 `/tmp` 可写；默认 `JAVA_OPTS` 见 Dockerfile。
- 构建阶段在容器内用 Maven + Node 24 构建 Vaadin 生产包。本地无法在容器内拉取依赖时，可先在宿主机 `mvn -Pproduction -DskipTests package`，再：

  ```bash
  podman build --platform linux/arm64 --build-arg JAR_SOURCE=local --target runtime -t aster-ai-workspace:local .
  podman run --rm --read-only --tmpfs /tmp -p 8080:8080 aster-ai-workspace:local
  ```

- `.github/workflows/ci.yml` 在 PR / main 上跑 `mvn verify`；`deploy.yml` 在 main 上构建并推送 `<sha7>` 与 `latest` 标签、校验 arm64、cosign 签名验签，再向 k3s 开 image-pin PR。
- 所需 secrets：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`（组织级 secrets）、`IMAGE_PIN_APP_PRIVATE_KEY`，变量 `IMAGE_PIN_APP_CLIENT_ID`。
- 依赖 `com.wontlost:agui-vaadin:0.2.0`（已发布到 Maven Central）。
