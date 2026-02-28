FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy all built jars and keep only the executable Spring Boot jar.
COPY build/libs/ /app/libs/
RUN set -eux; \
    boot_jar="$(find /app/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$boot_jar"; \
    mv "$boot_jar" /app/app.jar; \
    rm -rf /app/libs

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
