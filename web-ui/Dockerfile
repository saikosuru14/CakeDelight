# Cake Delight - Web UI (port 80)
#
# Static browser client: plain HTML, CSS, and vanilla JavaScript. No build stage, because
# there is nothing to compile, bundle, or transpile.
#
# The base image tag is pinned; `latest` is never used.
#
# No configuration and no credentials are baked into this image. The only outbound
# dependency is the API Gateway, reached by its service name on the compose network from
# the /api/ proxy in nginx.conf.

FROM nginx:1.27-alpine

# Replace the default server block with the one that both serves the client and proxies
# /api/ to the gateway.
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY index.html styles.css app.js /usr/share/nginx/html/

EXPOSE 80
