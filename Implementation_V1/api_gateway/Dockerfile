FROM apache/apisix:3.10.0-debian

USER root

# Create nginx temp directories required by APISIX
RUN mkdir -p \
    /usr/local/apisix/client_body_temp \
    /usr/local/apisix/proxy_temp \
    /usr/local/apisix/fastcgi_temp \
    /usr/local/apisix/uwsgi_temp \
    /usr/local/apisix/scgi_temp \
    /usr/local/apisix/plugins/java \
 && chown -R apisix:apisix /usr/local/apisix

USER apisix
