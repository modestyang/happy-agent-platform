FROM eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38

WORKDIR /app

COPY --chmod=755 deploy/production/app-entrypoint.sh /app/app-entrypoint.sh
COPY starter/target/starter-*-exec.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

ENTRYPOINT ["/app/app-entrypoint.sh", "-Xms256m", "-Xmx1200m", "-XX:MaxMetaspaceSize=256m", "-Xss512k"]
