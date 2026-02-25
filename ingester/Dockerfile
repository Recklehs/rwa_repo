FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /work

COPY ingester/gradlew /work/gradlew
COPY ingester/gradle /work/gradle
COPY ingester/build.gradle /work/build.gradle
COPY ingester/src /work/src

RUN chmod +x /work/gradlew && \
    /work/gradlew --no-daemon clean installDist

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /work/build/install/ingester /app
# Keep compatibility with SHARED_DIR_PATH=/shared in AKS env contract.
COPY shared /shared

ENV SHARED_DIR_PATH=/shared
ENTRYPOINT ["/app/bin/ingester"]
