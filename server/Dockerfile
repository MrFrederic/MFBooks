# Build from the repo root: docker build -f server/Dockerfile -t mfbooks-server .
# The web player and the jar are platform-independent, so they build once on the build machine; only the last stage is per-arch.
ARG BUILDPLATFORM=linux/amd64
FROM --platform=$BUILDPLATFORM node:24-alpine AS web
WORKDIR /web
COPY server/web/package.json server/web/package-lock.json ./
RUN npm ci
COPY server/web/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS jar
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY server/build.gradle.kts server/
COPY server/src server/src
ENV MFBOOKS_SERVER_ONLY=1
RUN ./gradlew --no-daemon --console=plain :server:installDist -x test

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ffmpeg && adduser -D -u 1000 mfbooks && mkdir /data && chown mfbooks /data
COPY --from=jar /src/server/build/install/server /app
COPY --from=web /web/dist /app/web
ENV MFBOOKS_DATA=/data MFBOOKS_WEB=/app/web MFBOOKS_PORT=8080 JAVA_OPTS="-Xms16m -Xmx256m -XX:+UseSerialGC"
USER mfbooks
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
