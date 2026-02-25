FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /work

COPY ingester/gradlew /work/gradlew
COPY ingester/gradle /work/gradle
COPY ingester/build.gradle /work/build.gradle
COPY ingester/src /work/src

RUN chmod +x /work/gradlew && \
    /work/gradlew --no-daemon clean installDist && \
    dist_dir="$(find /work/build/install -mindepth 1 -maxdepth 1 -type d | head -n 1)" && \
    test -n "${dist_dir}" && \
    cp -R "${dist_dir}" /work/dist

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /work/dist /app
# Keep compatibility with SHARED_DIR_PATH=/shared in AKS env contract.
COPY shared /shared
RUN set -eux; \
    main_script="$(find /app/bin -maxdepth 1 -type f ! -name '*.bat' | head -n 1)"; \
    test -n "${main_script}"; \
    ln -sf "${main_script}" /app/bin/ingester; \
    chmod +x /app/bin/ingester

ENV SHARED_DIR_PATH=/shared
ENTRYPOINT ["/app/bin/ingester"]
