FROM nginxinc/nginx-unprivileged:stable-alpine

USER root
# Configure for angular fallback routes
COPY nginx.conf /etc/nginx/nginx.conf

# Copy built app to wwwroot
COPY --chown=1001:1001 dist/kafka-cost-control-frontend/browser /usr/share/nginx/html/

USER 1001

EXPOSE 8080

HEALTHCHECK --interval=5s CMD curl http://localhost:8080
