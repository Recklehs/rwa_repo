FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /work

COPY flink/gradlew /work/gradlew
COPY flink/gradle /work/gradle
COPY flink/build.gradle /work/build.gradle
COPY flink/src /work/src

RUN chmod +x /work/gradlew && \
    /work/gradlew --no-daemon clean jar copyRuntimeDepsForImage

FROM flink:1.20.0-java17
WORKDIR /opt/flink

COPY --from=build /work/build/libs/rwa-flink-indexer.jar /opt/flink/usrlib/rwa-flink-indexer.jar
COPY --from=build /work/build/image-libs/ /opt/flink/usrlib/
# Keep compatibility with AKS runtime env SHARED_DIR_PATH=/opt/shared.
COPY shared /opt/shared

ENV SHARED_DIR_PATH=/opt/shared
