# Multi-stage: build Moqui WAR with the demo component, then run
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Clone Moqui framework (pinned version)
RUN git clone --depth 1 --branch v4.0.0 https://github.com/moqui/moqui-framework.git framework 2>/dev/null || \
    git clone --depth 1 https://github.com/moqui/moqui-framework.git framework

# Copy demo component into runtime/component
COPY components/ framework/runtime/component/

# Build WAR (includes component JARs and data)
WORKDIR /build/framework
RUN ./gradlew addRuntime -Pcomponent=moqui-sv-localization -x test 2>/dev/null || true && \
    ./gradlew build -x test

# Unzip the WAR for optimal Docker startup
RUN mkdir -p /opt/moqui && \
    cd /opt/moqui && \
    unzip -q /build/framework/moqui.war -d . && \
    # Copy runtime with component data into the image
    cp -r /build/framework/runtime /opt/moqui/runtime

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

WORKDIR /opt/moqui

COPY --from=builder /opt/moqui/ .

# Create DB volume mount point
VOLUME ["/opt/moqui/runtime/db", "/opt/moqui/runtime/log"]

EXPOSE 80

ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]
CMD ["conf=conf/MoquiDevConf.xml", "port=80"]

HEALTHCHECK --interval=30s --timeout=60s --start-period=180s \
    CMD curl -f http://localhost/status || exit 1
