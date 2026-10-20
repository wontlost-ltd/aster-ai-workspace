# 生产镜像：arm64、只读根文件系统、uid 1001，仅 /tmp 可写。
#
# JAR_SOURCE 决定 jar 的来源，默认 build（CI 与生产）：
#   build —— 在 build 阶段用 Maven + Node 24 构建 Vaadin 生产包（必须在 JAR_SOURCE 之前声明才能用于 FROM）
#   local —— 直接取宿主机已构建的 target/aster-ai-workspace-*.jar，仅用于无法在容器内拉取依赖的本地冒烟
ARG JAR_SOURCE=build

# 构建阶段：产物（jar）与 CPU 架构无关，固定在构建机原生平台执行，避免 QEMU 下跑 Maven/Node
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS jar-build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_24.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src src
RUN mvn -B -ntp -Pproduction -DskipTests package \
 && cp target/aster-ai-workspace-*.jar /app.jar

# 本地冒烟用：jar 由宿主机预先构建（见 .dockerignore 对 target 的放行）
FROM --platform=$BUILDPLATFORM scratch AS jar-local
COPY target/aster-ai-workspace-*.jar /app.jar

FROM jar-${JAR_SOURCE} AS jar

# 运行阶段：非 root 与只读根文件系统由 USER 与 Pod securityContext 共同保证
FROM eclipse-temurin:21-jre AS runtime
# 与 CI 的架构校验互为补充：镜像内容必须是真 arm64，防止跨架构构建产出错标镜像
RUN test "$(uname -m)" = "aarch64"
WORKDIR /app
COPY --from=jar /app.jar /app/app.jar
ENV JAVA_OPTS="-Xms128m -Xmx448m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Djava.io.tmpdir=/tmp" \
    MALLOC_ARENA_MAX=2
EXPOSE 8080
USER 1001
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
