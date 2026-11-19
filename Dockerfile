FROM eclipse-temurin:17-jre-alpine
RUN mkdir -p /app /data \
    && chmod 0755 /app \
    && chmod 0770 /data \
    && chown 10001:10001 /app /data
WORKDIR /app
COPY --chown=10001:10001 target/gxtodo-server.jar /app/server.jar
COPY deploy/healthcheck.sh /app/healthcheck.sh
RUN chmod 0555 /app/healthcheck.sh && chown 10001:10001 /app/healthcheck.sh
USER 10001:10001
ENV HOME=/tmp
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:MaxRAMPercentage=70", "-jar", "/app/server.jar"]
