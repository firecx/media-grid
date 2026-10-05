# Веб-интерфейс: сборка на Node, отдача через nginx. Контекст сборки — каталог web/.
FROM node:26-alpine AS build
WORKDIR /app
# Сначала только описание зависимостей: пока оно не меняется, слой с node_modules берётся из кэша
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30-alpine
# openssl — для самоподписанного сертификата, если настоящего нет
RUN apk add --no-cache openssl
# Части настроек; итоговые собирает 40-mediagrid.sh при запуске (HTTPS или только HTTP)
COPY nginx/http.conf.template nginx/app.conf nginx/proxy.conf /etc/nginx/mediagrid/
COPY --chmod=755 nginx/40-mediagrid.sh /docker-entrypoint.d/40-mediagrid.sh
COPY --from=build /app/dist /usr/share/nginx/html
RUN mkdir -p /etc/nginx/certs /var/www/acme
# Адрес шлюза внутри сети docker compose
ENV GATEWAY_ADDRESS=gateway:8080
EXPOSE 80 443
