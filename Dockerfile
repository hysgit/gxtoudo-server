FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 gxtodo \
    && useradd --uid 10001 --gid gxtodo --no-create-home gxtodo \
    && mkdir -p /app /data && chown gxtodo:gxtodo /app /data
WORKDIR /app
COPY --chown=gxtodo:gxtodo target/gxtodo-server.jar /app/server.jar
USER 10001:10001
ENV GXTODO_DATABASE=/data/gxtodo.sqlite3
EXPOSE 8765
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:${GXTODO_PORT:-8765}/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/server.jar"]
