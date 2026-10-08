#!/bin/sh
# Сборка настроек nginx при запуске контейнера (выполняется из /docker-entrypoint.d перед nginx).
#
# MEDIAGRID_TLS=auto (по умолчанию) — HTTPS на 443, с 80 перенаправление на HTTPS. Сертификат и ключ —
#   в /etc/nginx/certs (MEDIAGRID_TLS_CERT_FILE, MEDIAGRID_TLS_KEY_FILE, по умолчанию fullchain.pem
#   и privkey.pem). Если файлов по умолчанию нет — создаётся самоподписанный сертификат на
#   MEDIAGRID_HOSTNAME (для проверки; браузер предупредит о нём).
# MEDIAGRID_TLS=off — только HTTP на 80 (разработка на своей машине).
#
# MEDIAGRID_PROXY_PROTOCOL=on — ещё два входа для внешнего пересыльщика соединений (VDS с белым адресом,
#   docker-compose.vds.yaml): 8080 (HTTP) и 8443 (HTTPS). На них каждое соединение начинается заголовком
#   PROXY protocol с настоящим адресом посетителя — он попадает в журналы и в ограничение попыток входа.
#   Заголовку верят, только если соединение пришло с адресов MEDIAGRID_REAL_IP_FROM (по умолчанию
#   частные сети: туннель WireGuard и внутренние адреса Docker). Обычные 80 и 443 работают как раньше.
set -eu

CONF=/etc/nginx/conf.d/default.conf
CERTS=/etc/nginx/certs
MODE="${MEDIAGRID_TLS:-auto}"
HOST="${MEDIAGRID_HOSTNAME:-localhost}"
HTTPS_PORT="${MEDIAGRID_HTTPS_PORT:-443}"
CERT="$CERTS/${MEDIAGRID_TLS_CERT_FILE:-fullchain.pem}"
KEY="$CERTS/${MEDIAGRID_TLS_KEY_FILE:-privkey.pem}"
PROXY="${MEDIAGRID_PROXY_PROTOCOL:-off}"
REAL_IP_FROM="${MEDIAGRID_REAL_IP_FROM:-10.0.0.0/8 172.16.0.0/12 192.168.0.0/16}"

# Входы с PROXY protocol: адрес посетителя берётся из заголовка. На обычных входах заголовка нет,
# и адрес остаётся адресом соединения
PROXY_HTTP_LISTEN=""
PROXY_HTTPS_LISTEN=""
REAL_IP=""
if [ "$PROXY" = "on" ]; then
    PROXY_HTTP_LISTEN="listen 8080 proxy_protocol;"
    PROXY_HTTPS_LISTEN="listen 8443 ssl proxy_protocol;"
    for net in $REAL_IP_FROM; do
        REAL_IP="${REAL_IP}set_real_ip_from $net; "
    done
    REAL_IP="${REAL_IP}real_ip_header proxy_protocol;"
fi

envsubst '$GATEWAY_ADDRESS' < /etc/nginx/mediagrid/http.conf.template > "$CONF"

if [ "$MODE" = "off" ]; then
    cat >> "$CONF" <<EOF

server {
    listen 80;
    $PROXY_HTTP_LISTEN
    $REAL_IP
    include /etc/nginx/mediagrid/app.conf;
}
EOF
    echo "mediagrid: только HTTP (MEDIAGRID_TLS=off)${PROXY_HTTP_LISTEN:+, PROXY protocol на 8080}"
    exit 0
fi

if [ ! -s "$CERT" ] || [ ! -s "$KEY" ]; then
    if [ -n "${MEDIAGRID_TLS_CERT_FILE:-}" ] || [ -n "${MEDIAGRID_TLS_KEY_FILE:-}" ]; then
        echo "mediagrid: не найден сертификат $CERT или ключ $KEY" >&2
        exit 1
    fi
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=$HOST" \
        -addext "subjectAltName=DNS:$HOST,DNS:localhost,IP:127.0.0.1" \
        -keyout "$KEY" -out "$CERT" 2>/dev/null
    chmod 600 "$KEY"
    echo "mediagrid: создан самоподписанный сертификат для $HOST ($CERT)"
fi

# Строгий HTTPS (HSTS) — только с настоящим сертификатом: самоподписанный браузер запомнил бы
# вместе с запретом заходить без HTTPS
HSTS=""
if [ "$(openssl x509 -in "$CERT" -noout -subject | sed 's/^subject=//')" != \
     "$(openssl x509 -in "$CERT" -noout -issuer | sed 's/^issuer=//')" ]; then
    HSTS='add_header Strict-Transport-Security "max-age=15552000" always;'
fi

# Перенаправление с HTTP: порт указывается, если HTTPS не на стандартном 443
PORT_SUFFIX=""
if [ "$HTTPS_PORT" != "443" ]; then
    PORT_SUFFIX=":$HTTPS_PORT"
fi

cat >> "$CONF" <<EOF

# HTTP: проверка готовности, выпуск сертификата Let's Encrypt (certbot --webroot), остальное — на HTTPS
server {
    listen 80;
    server_tokens off;

    location = /healthz {
        access_log off;
        default_type text/plain;
        return 200 "ok\n";
    }

    location /.well-known/acme-challenge/ {
        root /var/www/acme;
    }

    location / {
        return 301 https://\$host$PORT_SUFFIX\$request_uri;
    }
}
EOF

# HTTP через пересыльщика: снаружи это стандартные 80 и 443, поэтому перенаправление — без порта
if [ "$PROXY" = "on" ]; then
    cat >> "$CONF" <<EOF

server {
    $PROXY_HTTP_LISTEN
    $REAL_IP
    server_tokens off;

    location /.well-known/acme-challenge/ {
        root /var/www/acme;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}
EOF
fi

cat >> "$CONF" <<EOF

server {
    listen 443 ssl;
    $PROXY_HTTPS_LISTEN
    $REAL_IP
    http2 on;

    ssl_certificate $CERT;
    ssl_certificate_key $KEY;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:tls:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;
    $HSTS

    include /etc/nginx/mediagrid/app.conf;
}
EOF
echo "mediagrid: HTTPS на 443, сертификат $CERT${HSTS:+ (HSTS включён)}${PROXY_HTTPS_LISTEN:+, PROXY protocol на 8080 и 8443}"
