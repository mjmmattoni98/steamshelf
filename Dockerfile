# syntax=docker/dockerfile:1
# SteamShelf's web server. Builds anywhere Docker runs, the Raspberry Pi included; the Android app (and the
# Android SDK) aren't needed. See docker-compose.yml and the README's "Web app" section.

FROM eclipse-temurin:17-jdk-noble AS build
ARG VERSION=dev
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY server server
# A modest heap and an in-process Kotlin compiler keep the build within a Raspberry Pi's memory.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain \
      -Psteamshelf.serverOnly=true \
      -Dorg.gradle.jvmargs="-Xmx1g -Dfile.encoding=UTF-8" \
      -Pkotlin.compiler.execution.strategy=in-process \
      -PversionName="${VERSION}" \
      :server:installDist

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system --gid 10001 steamshelf \
    && useradd --system --uid 10001 --gid steamshelf --home-dir /data --no-create-home --shell /usr/sbin/nologin steamshelf \
    && mkdir -p /data \
    && chown steamshelf:steamshelf /data
COPY --from=build /src/server/build/install/steamshelf-server /opt/steamshelf
ENV STEAMSHELF_DATA_DIR=/data \
    STEAMSHELF_HOST=0.0.0.0 \
    STEAMSHELF_PORT=8080
USER steamshelf
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=1m --timeout=5s --start-period=30s CMD curl -fsS http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/opt/steamshelf/bin/steamshelf-server"]
