# =============================================================================
# 星幕影票 —— 应用镜像（多阶段构建，推荐路径）
# =============================================================================
# 阶段一：Maven 构建。用官方 maven 镜像，宿主机不需要装 JDK/Maven，
#         保证「谁构建都是同一份产物」。
# 阶段二：只把 jar 拷进 JRE 镜像，运行时不带 JDK、源码、.m2。
#
# 构建：
#     docker build -t starscreen-ticketing:1.0.0 .
# 单独运行（需自备 MySQL/Redis/RabbitMQ）：
#     docker run --rm -p 8080:8080 -e DB_PASSWORD=xxx starscreen-ticketing:1.0.0
#
# 无法访问镜像仓库、或 CI 已产出 jar 时，改用 Dockerfile.prebuilt（见该文件注释）。
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先只拷 pom.xml 预热依赖：pom 不变时，改代码可复用这一层缓存。
# --mount=type=cache 让 /root/.m2 在多次构建之间持久化（需要 BuildKit）。
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q clean package -DskipTests

# 产物校验：只允许一个可执行 jar，避免 COPY 到多个文件时静默取错
RUN test -f target/starscreen-ticketing-1.0.0.jar \
    && echo "artifact ok: $(ls -lh target/starscreen-ticketing-1.0.0.jar | awk '{print $5}')"

# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# tzdata：让容器内 JVM 与日志时间戳按东八区显示；
# 应用与数据库都要求 Asia/Shanghai，时区错位会让场次与订单时间整体偏 8 小时。
# （Temurin 官方镜像已内置 tzdata，这里只做存在性断言，缺失则构建期就失败。）
RUN test -f /usr/share/zoneinfo/Asia/Shanghai \
    && groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# 以非 root 运行：即使应用被攻破，也改不动镜像内文件、装不了工具
COPY --from=build --chown=app:app /build/target/starscreen-ticketing-1.0.0.jar /app/app.jar

USER app

ENV TZ=Asia/Shanghai \
    # MaxRAMPercentage 让堆跟随容器内存上限，而不是宿主机内存；
    # 不设置时容器里看到的是宿主机总内存，堆会开得过大被 cgroup OOMKill
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 自带健康检查，docker run 单跑时也能反映就绪状态（compose 中会覆盖为同样策略）。
# 探测首页而非端口：首页要读数据库，能挡住「端口开了但库连不上」的假健康。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1

# 用 sh -c 展开 JAVA_OPTS，再 exec 让 java 成为 PID 1，正确接收 SIGTERM 优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
