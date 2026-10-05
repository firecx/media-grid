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
# Шаблоны из /etc/nginx/templates при запуске дополняются переменными среды (envsubst) и кладутся в conf.d
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/proxy.conf /etc/nginx/mediagrid/proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Адрес шлюза внутри сети docker compose
ENV GATEWAY_ADDRESS=gateway:8080
EXPOSE 80
