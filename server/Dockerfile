FROM eclipse-temurin:17-jdk-jammy AS prepare
WORKDIR /work
COPY server/build/libs/ /work/libs/
RUN set -eux; \
    jar_path="$(find /work/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "${jar_path}"; \
    cp "${jar_path}" /work/app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=prepare /work/app.jar /app/app.jar
# Keep compatibility with SHARED_DIR_PATH=../shared in current .env
COPY shared /shared
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
