# Build: run `./gradlew installDist` first (CI/scripts do this), so the image needs no Gradle.
# Runtime image: Eclipse Temurin 17 JRE on Ubuntu Jammy, running as an unprivileged user.
FROM eclipse-temurin:17.0.12_7-jre-jammy

RUN groupadd --system smsbridge && useradd --system --gid smsbridge --home /app smsbridge
WORKDIR /app
COPY build/install/sms-bridge-server/ /app/
USER smsbridge

ENV SMSBRIDGE_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"ok\"' <&3"]
ENTRYPOINT ["/app/bin/sms-bridge-server"]
