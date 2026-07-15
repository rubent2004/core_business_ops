# Moqui demo image (framework + MarbleERP + SimpleScreens + deps)
FROM moqui/moquidemo:latest

# SARA localization (screens, services, fiscal SV)
COPY components/moqui-sv-localization /opt/moqui/runtime/component/moqui-sv-localization

# SARA UI shell: login, dark sidebar, CORE BUSINESS/OPS groups, logos, card CSS
COPY overlays/webroot/ /opt/moqui/runtime/base-component/webroot/

# Spanish card dashboards (Catalog, Inventario, Producción, etc.)
COPY overlays/SimpleScreens/ /opt/moqui/runtime/component/SimpleScreens/

# Brand MarbleERP as CORE Business/Ops
COPY overlays/MarbleERP/screen/marble.xml /opt/moqui/runtime/component/MarbleERP/screen/marble.xml

# Runtime conf
COPY runtime/conf/MoquiDevConf.xml /opt/moqui/runtime/conf/MoquiDevConf.xml

RUN rm -f /opt/moqui/runtime/component/moqui-sv-localization/data/SvDemoData.xml 2>/dev/null || true

EXPOSE 80

ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]
CMD ["conf=conf/MoquiDevConf.xml", "port=80"]

HEALTHCHECK --interval=30s --timeout=60s --start-period=300s \
    CMD curl -f http://localhost/status || exit 1
