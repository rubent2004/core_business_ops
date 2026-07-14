# Multi-stage: build Moqui WAR with the demo component, then run
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y git unzip && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Clone Moqui framework
RUN git clone --depth 1 https://github.com/moqui/moqui-framework.git framework

# Copy demo component into runtime/component
COPY components/ framework/runtime/component/

# Build WAR + runtime
WORKDIR /build/framework
RUN ./gradlew build addRuntime -x test

# Unzip the combined WAR
RUN mkdir -p /opt/moqui && \
    cd /opt/moqui && \
    unzip -q /build/framework/moqui-plus-runtime.war

# Copy runtime/conf from the framework source (the WAR doesn't include it)
RUN mkdir -p /opt/moqui/runtime/conf && \
    cp /build/framework/runtime/conf/*.xml /opt/moqui/runtime/conf/ 2>/dev/null || true && \
    cp /build/framework/runtime/conf/*.xsd /opt/moqui/runtime/conf/ 2>/dev/null || true && \
    # Copy runtime/lib for JDBC drivers etc
    mkdir -p /opt/moqui/runtime/lib && \
    cp /build/framework/runtime/lib/*.jar /opt/moqui/runtime/lib/ 2>/dev/null || true && \
    # Copy component data (our demo component)
    mkdir -p /opt/moqui/runtime/component && \
    cp -r /build/framework/runtime/component/moqui-sv-localization /opt/moqui/runtime/component/ && \
    cp -r /build/framework/runtime/component/MarbleERP /opt/moqui/runtime/component/ 2>/dev/null || true && \
    # Copy base components
    for d in tools webroot; do \
      [ -d "/build/framework/runtime/base-component/$d" ] && \
      mkdir -p /opt/moqui/runtime/base-component && \
      cp -r "/build/framework/runtime/base-component/$d" /opt/moqui/runtime/base-component/; \
    done

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

WORKDIR /opt/moqui

COPY --from=builder /opt/moqui/ .

# Volumes for persistent data
VOLUME ["/opt/moqui/runtime/db", "/opt/moqui/runtime/log"]

EXPOSE 80

ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]
CMD ["conf=conf/MoquiDevConf.xml", "port=80"]

HEALTHCHECK --interval=30s --timeout=60s --start-period=180s \
    CMD curl -f http://localhost/status || exit 1
