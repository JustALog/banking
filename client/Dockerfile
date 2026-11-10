# Production image for the banking website. Vite 8 needs Node >= 20.19, so the build stage uses Node 22.
#   docker build -t banking/web:dev client/
#   docker run -p 8088:80 -e GATEWAY_URL=http://host.docker.internal:8080 banking/web:dev
ARG NODE_VERSION=22.12.0
FROM node:${NODE_VERSION}-bullseye-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm test && npm run build

FROM nginx:1.27-alpine
# nginx's entrypoint runs envsubst on /etc/nginx/templates/*.template at start-up.
ENV GATEWAY_URL=http://gateway-service:8080
COPY deploy/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
