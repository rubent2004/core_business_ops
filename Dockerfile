# Use the official Moqui demo image as base (has framework + all dependencies)
FROM moqui/moquidemo:latest

# Copy our demo component into the runtime
COPY components/moqui-sv-localization /opt/moqui/runtime/component/moqui-sv-localization

# Copy our dev conf
COPY runtime/conf/MoquiDevConf.xml /opt/moqui/runtime/conf/MoquiDevConf.xml

# Remove the default demo data (we have our own)
RUN rm -f /opt/moqui/runtime/component/moqui-sv-localization/data/SvDemoData.xml 2>/dev/null || true

EXPOSE 80

ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]
CMD ["conf=conf/MoquiDevConf.xml", "port=80"]

HEALTHCHECK --interval=30s --timeout=60s --start-period=300s \
    CMD curl -f http://localhost/status || exit 1
