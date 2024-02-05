FROM nginx

# Configure for angular fallback routes
COPY nginx.conf /etc/nginx/nginx.conf

# Copy built app to wwwroot
COPY dist/kafka-cost-control-frontend/browser /usr/share/nginx/html

HEALTHCHECK --interval=5s CMD curl http://localhost:80
