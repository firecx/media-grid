# Запуск MediaGrid с входом через VDS

Пошаговая инструкция с нуля: система работает на своём компьютере с Windows, а VDS с белым адресом служит только точкой входа из интернета. Сертификат на домен — свой (например, выданный провайдером домена).

```
посетитель ──► домен ──► VDS :80/:443 ──► туннель WireGuard ──► компьютер :8080/:8443 ──► MediaGrid
```

- VDS ничего не расшифровывает и ничего не хранит: соединение уходит в туннель как есть. Сертификат, закрытый ключ, файлы и база — только на компьютере.
- Адрес посетителя передаётся по PROXY protocol: он виден в журналах, и ограничение попыток входа считается по каждому посетителю.
- Адреса в туннеле: VDS — `10.8.0.1`, компьютер — `10.8.0.2`.

Ниже `media.example.org` — ваш домен, `203.0.113.10` — белый адрес VDS. Замените их своими везде, где они встречаются.

## 1. Что понадобится

| | Требования |
| --- | --- |
| VDS | виртуализация KVM; 1 vCPU, 1 ГБ памяти, 10 ГБ диска; Debian 12/13 или Ubuntu 24.04; белый IPv4; порт от 200 Мбит/с, трафик без жёсткого лимита (файлы идут через VDS) |
| Компьютер | Windows 10/11 64-бит, 4 ядра, 16 ГБ памяти (системе нужно около 4 ГБ), SSD с местом под медиатеку × 2; Docker Desktop, Git, WireGuard |
| Домен | доступ к записям DNS |
| Сертификат | на `media.example.org`: сертификат, цепочка промежуточных сертификатов, закрытый ключ без пароля |

Скорость отдачи файлов посетителям ограничена исходящим каналом домашнего интернета, а не VDS.

## 2. Домен

В панели DNS создайте запись:

| Тип | Имя | Значение |
| --- | --- | --- |
| A | `media` (или `@` для самого домена) | `203.0.113.10` |

Если у VDS есть IPv6 — ещё запись AAAA с ним. Записи расходятся по сети от минут до нескольких часов, поэтому сделайте это первым. Проверка (PowerShell): `Resolve-DnsName media.example.org` — должен вернуться адрес VDS.

## 3. VDS

Подключитесь по SSH: `ssh root@203.0.113.10`.

### 3.1. Пакеты

```
apt update && apt -y upgrade
apt -y install wireguard nginx libnginx-mod-stream ufw curl
```

### 3.2. Сетевой экран

Сначала разрешите SSH, иначе после включения экрана подключение оборвётся. Если SSH у вас не на 22, укажите свой порт.

```
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 51820/udp
ufw enable
```

### 3.3. Ключи WireGuard

```
umask 077
wg genkey | tee /etc/wireguard/vds.key | wg pubkey > /etc/wireguard/vds.pub
cat /etc/wireguard/vds.pub
```

Последняя команда выводит **открытый ключ VDS** — сохраните его, он нужен на шаге 4. Закрытый ключ (`vds.key`) с VDS никуда не копируйте.

Туннель на VDS настроим после того, как появится ключ компьютера (шаг 4).

## 4. WireGuard на компьютере

1. Установите WireGuard для Windows: https://www.wireguard.com/install/
2. «Добавить туннель» → «Добавить пустой туннель…». Имя — `mediagrid`. Ключи программа создаёт сама: строка `PrivateKey` уже заполнена, а **открытый ключ компьютера** показан вверху окна — скопируйте его.
3. Дополните текст туннеля по образцу [`infra/vds/wg-pc.conf`](../infra/vds/wg-pc.conf) — строку `PrivateKey` оставьте как есть:

   ```
   [Interface]
   PrivateKey = <уже заполнено>
   Address = 10.8.0.2/24

   [Peer]
   PublicKey = <открытый ключ VDS из шага 3.3>
   Endpoint = 203.0.113.10:51820
   AllowedIPs = 10.8.0.1/32
   PersistentKeepalive = 25
   ```

   `AllowedIPs = 10.8.0.1/32` — через туннель идёт только связь с VDS, остальной интернет компьютера работает как обычно.
4. Сохраните. Включать пока рано: VDS ещё не знает ключ компьютера.

Вернитесь на VDS и создайте `/etc/wireguard/wg0.conf` (например, `nano /etc/wireguard/wg0.conf`) по образцу [`infra/vds/wg-vds.conf`](../infra/vds/wg-vds.conf):

```
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = <содержимое /etc/wireguard/vds.key>

[Peer]
PublicKey = <открытый ключ компьютера>
AllowedIPs = 10.8.0.2/32
```

Запустите туннель на VDS, затем нажмите «Подключить» в WireGuard на компьютере:

```
systemctl enable --now wg-quick@wg0
```

Проверка:
- на компьютере: `ping 10.8.0.1` — ответы есть;
- на VDS: `wg show` — у пира есть строка `latest handshake: … seconds ago`.

Ответа на `ping 10.8.0.2` с VDS может не быть: Windows по умолчанию не отвечает на ping. Это не ошибка. Но если ping с VDS пишет `sendmsg: Destination address required`, туннель не соединился ни разу: VDS не получил от компьютера ни одного принятого рукопожатия и не знает, куда слать пакеты.

Ловушка Windows: если туннель не работает, `ping 10.8.0.1` на компьютере выводит «Ответ от 10.8.0.2: Заданный узел недоступен» и считает пакет полученным («потеряно 0 %»). Это ответ самого компьютера. Настоящий ответ VDS — «Ответ от 10.8.0.1: число байт=32 время=…».

Туннель WireGuard в Windows работает как служба: после перезагрузки компьютера он поднимается сам.

## 5. Пересылка на VDS

Скопируйте заготовку на VDS — с компьютера, из папки проекта (шаг 6.1), в PowerShell или Git Bash:

```
scp infra/vds/nginx-stream.conf root@203.0.113.10:/etc/nginx/mediagrid-stream.conf
```

На VDS подключите её и уберите сайт по умолчанию, занимающий порт 80:

```
echo 'include /etc/nginx/mediagrid-stream.conf;' >> /etc/nginx/nginx.conf
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```

`nginx -t` должен ответить `syntax is ok` и `test is successful`. Если у VDS есть IPv6, раскомментируйте строки `listen [::]:…` в `/etc/nginx/mediagrid-stream.conf`.

Строка `include` добавляется в конец файла — вне блока `http`, как и требуется для пересылки соединений. Пересылка пакетов (`ip_forward`) и правила NAT не нужны: соединение в туннель открывает сам nginx.

## 6. Компьютер

### 6.1. Программы и проект

1. Docker Desktop: https://www.docker.com/products/docker-desktop/ — с WSL 2. В настройках включите «Start Docker Desktop when you sign in».
2. Git: https://git-scm.com/download/win
3. Получите проект (Git Bash или PowerShell):

   ```
   git clone https://github.com/firecx/media-grid.git
   cd media-grid
   git checkout v0.9.0
   ```

   `v0.9.0` — метка выпущенной версии (список: `git tag`). Путь к папке лучше без пробелов и кириллицы, например `C:\mediagrid\media-grid`.
4. Отключите сон: «Параметры» → «Система» → «Питание» → «Переводить в спящий режим» — «Никогда». Спящий компьютер — недоступный сайт.

Если памяти меньше 16 ГБ, ограничьте Docker, чтобы осталось Windows: файл `%USERPROFILE%\.wslconfig`:

```
[wsl2]
memory=6GB
```

и перезапустите Docker Desktop.

### 6.2. Настройки (.env)

```
copy .env.example .env
```

Откройте `.env` в редакторе и задайте:

| Переменная | Что указать |
| --- | --- |
| `POSTGRES_PASSWORD` | случайная строка (см. ниже) |
| `RABBITMQ_PASSWORD` | случайная строка |
| `ADMIN_EMAIL` | почта первого администратора |
| `ADMIN_PASSWORD` | его пароль, 8–64 символа |
| `STORAGE_LINK_SECRET` | случайная строка не короче 32 символов |
| `PROCESSING_CLIENT_SECRET` | ещё одна случайная строка не короче 32 символов |
| `MEDIAGRID_HOSTNAME` | `media.example.org` (раскомментируйте строку) |

И добавьте в конец строку — тогда команды `docker compose` не нужно дополнять `-f …` (разделитель в Windows — `;`):

```
COMPOSE_FILE=docker-compose.yaml;docker-compose.vds.yaml
```

Случайную строку даёт PowerShell (каждый запуск — новая):

```
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
```

Важно:
- в значениях не используйте `$` — docker compose считает его подстановкой;
- `POSTGRES_PASSWORD`, `ADMIN_EMAIL` и `ADMIN_PASSWORD` применяются только при **первом** запуске. Позже их правка в `.env` ничего не меняет: пароль администратора меняется в интерфейсе, пароль базы — средствами PostgreSQL;
- `.env` содержит пароли — не публикуйте его и не добавляйте в git (он уже исключён в `.gitignore`).

### 6.3. Сертификат

Создайте в папке проекта папку `certs` (она исключена из git) и положите в неё два файла:

| Файл | Что в нём |
| --- | --- |
| `certs/fullchain.pem` | сертификат домена, **за ним** промежуточные сертификаты цепочки |
| `certs/privkey.pem` | закрытый ключ, без пароля |

Как получить их из того, что выдал провайдер:

- **Отдельные файлы** (`media.example.org.crt`, `ca_bundle.crt` / `chain.pem`, `private.key`) — склейте сертификат и цепочку (PowerShell):

  ```
  Get-Content media.example.org.crt, ca_bundle.crt | Set-Content -Encoding ascii certs\fullchain.pem
  Copy-Item private.key certs\privkey.pem
  ```

  Если провайдер уже дал `fullchain.pem` — его и используйте.
- **Один файл .pfx / .p12** — разберите его в Git Bash (спросит пароль от файла; для старых файлов добавьте `-legacy`):

  ```
  openssl pkcs12 -in cert.pfx -nokeys -out certs/fullchain.pem
  openssl pkcs12 -in cert.pfx -nocerts -nodes -out certs/privkey.pem
  ```

Проверка в Git Bash:

```
# На кого выдан и до какого числа действует
openssl x509 -in certs/fullchain.pem -noout -subject -enddate -ext subjectAltName

# Ключ подходит к сертификату: два отпечатка должны совпасть
openssl x509 -in certs/fullchain.pem -noout -pubkey | openssl sha256
openssl pkey -in certs/privkey.pem -pubout | openssl sha256
```

- В `subjectAltName` должен быть `media.example.org`.
- Если в начале `privkey.pem` написано `ENCRYPTED`, ключ защищён паролем, а nginx такой не откроет. Снимите пароль (спросит его) и замените файл:

  ```
  openssl pkey -in certs/privkey.pem -out certs/privkey-open.pem
  mv certs/privkey-open.pem certs/privkey.pem
  ```

С настоящим сертификатом nginx сам включает HSTS: браузеры полгода будут открывать домен только по HTTPS.

### 6.4. Доступ к портам пересылки

Система принимает соединения от VDS на портах 8080 и 8443. Разрешите их только с адреса VDS в туннеле (PowerShell от имени администратора):

```
New-NetFirewallRule -DisplayName "MediaGrid через VDS" -Direction Inbound -Protocol TCP -LocalPort 8080,8443 -RemoteAddress 10.8.0.1 -Action Allow
```

Проверьте в «Мониторе брандмауэра Защитника Windows» («Правила для входящих подключений»), что нет других разрешающих правил на эти порты для всех адресов (например, от Docker Desktop). Иначе устройство в домашней сети сможет подставить чужой адрес посетителя — в журналы и в счёт попыток входа; к данным это доступа не даёт.

### 6.5. Вход с самого компьютера

Сертификат выдан на домен, а не на `localhost`, поэтому и с этого компьютера заходите по домену. Чтобы запросы не ходили через VDS, откройте Блокнот от имени администратора, затем файл `C:\Windows\System32\drivers\etc\hosts`, и добавьте строку:

```
127.0.0.1 media.example.org
```

## 7. Запуск

В папке проекта:

```
docker compose up -d --build
```

Первая сборка занимает 5–15 минут (скачиваются образы и зависимости). Дальше:

```
docker compose ps
```

Через 1–2 минуты у всех служб должно быть `healthy`. Ещё примерно минуту после этого вход может отвечать «служба временно недоступна»: службы находят друг друга через регистр.

Журналы и трассы в OpenObserve (по желанию, +0,4 ГБ памяти) — добавьте файл в `COMPOSE_FILE`, а в `.env` задайте `OBSERVE_ADMIN_EMAIL` и `OBSERVE_ADMIN_PASSWORD` (8–128 символов: строчные и заглавные буквы, цифра и спецсимвол):

```
COMPOSE_FILE=docker-compose.yaml;docker-compose.vds.yaml;docker-compose.observability.yaml
```

Интерфейс OpenObserve открывается только с самого компьютера: http://localhost:5080.

## 8. Проверка

1. **Цепочка до компьютера** — на VDS:

   ```
   curl -sk --haproxy-protocol -o /dev/null -w '%{http_code}\n' https://10.8.0.2:8443/
   ```

   Ожидается `200`.
2. **Сертификат снаружи** — в Git Bash:

   ```
   openssl s_client -connect media.example.org:443 -servername media.example.org </dev/null 2>/dev/null | grep 'Verify return code'
   ```

   Ожидается `Verify return code: 0 (ok)`. Если файл `hosts` уже изменён, команда проверяет компьютер напрямую. Проверить именно путь через VDS можно, временно закомментировав строку в `hosts`, или с телефона.
3. **Как видит посетитель** — с телефона через мобильный интернет (не через домашний Wi-Fi) откройте `https://media.example.org`: страница входа, замок в адресной строке, без предупреждений.
4. **Адрес посетителя** — на компьютере:

   ```
   docker compose logs --tail 5 web
   ```

   В начале строк — адрес телефона в мобильной сети, а не `172.…`.

## 9. Первый вход

1. Войдите с `ADMIN_EMAIL` и `ADMIN_PASSWORD`.
2. Смените пароль: значок учётной записи справа вверху → «Сменить пароль».
3. Заведите пользователей: «Администрирование» → «Пользователи». Открытой регистрации нет.

## 10. Обслуживание

**Продление сертификата.** Сертификат Let's Encrypt действует 90 дней — поставьте себе напоминание за 2 недели до даты из `-enddate`. Получив новый, замените файлы в `certs/` (как в шаге 6.3) и перечитайте их без остановки:

```
docker compose exec web nginx -s reload
```

**Перезагрузка компьютера.** Ничего делать не нужно: Docker Desktop запускается при входе в Windows, службы — вместе с ним, туннель WireGuard — как служба Windows. Но компьютер должен быть включён и в нём выполнен вход пользователя (без входа Docker Desktop не запустится).

**Остановка и запуск:**

```
docker compose stop        # остановить
docker compose up -d       # запустить
```

Никогда не запускайте `docker compose down -v`: ключ `-v` удаляет тома, то есть базу и все файлы.

**Новая версия:**

```
git fetch --tags
git checkout v0.10.0
```

Затем в `.env` поставьте `MEDIAGRID_VERSION` на ту же версию и выполните `docker compose up -d --build`. Что изменилось — в [CHANGELOG.md](../CHANGELOG.md).

**Резервные копии.** Встроенного резервного копирования пока нет (запланировано). Данные хранятся в томах Docker: база — `postgres-data`, файлы — `storage-data`. Хотя бы копия базы (в Git Bash — Windows PowerShell 5 при `>` меняет кодировку файла):

```
docker compose exec -T postgres pg_dump -U mediagrid mediagrid > mediagrid-backup.sql
```

## 11. Если что-то не работает

| Признак | Где искать |
| --- | --- |
| Снаружи сайт не открывается, соединение сбрасывается | Туннель: `wg show` на VDS — давно ли было рукопожатие; WireGuard на компьютере подключён; Docker Desktop запущен (`docker compose ps`). Проверка из шага 8.1 |
| На VDS у пира в `wg show` нет `endpoint` и `latest handshake` | Рукопожатие не проходит. Проверьте на компьютере `Endpoint` (адрес или имя VDS и порт 51820 — частая ошибка в имени), затем ключи: `PublicKey` в `[Peer]` компьютера = `/etc/wireguard/vds.pub`, открытый ключ компьютера = `PublicKey` пира на VDS. Доходят ли пакеты: на VDS `tcpdump -ni any udp port 51820` во время подключения туннеля; если пусто — сеть не пропускает WireGuard |
| Снаружи сайт не открывается, а проверка 8.1 даёт 200 | Сетевой экран VDS (`ufw status`), nginx на VDS (`systemctl status nginx`, `nginx -t`), запись DNS (`Resolve-DnsName`) |
| Проверка 8.1 не отвечает | Сетевой экран Windows (шаг 6.4), туннель |
| Браузер предупреждает о сертификате | Имя в сертификате не совпадает с адресом в браузере; в `fullchain.pem` нет промежуточных сертификатов; заходите не по домену (например, по `localhost`) |
| `web` не запускается | `docker compose logs web`: при `не найден сертификат` — нет файлов в `certs/` или неверные имена |
| «Служба временно недоступна» сразу после запуска | Подождать 1–2 минуты: службы регистрируются |
| В журнале `web` у всех посетителей адрес `172.…` | Компьютер запущен без `docker-compose.vds.yaml` (проверьте `COMPOSE_FILE` в `.env`), или на VDS пересылка идёт не на 8080/8443 |
| Не входит администратор | `ADMIN_PASSWORD` короче 8 символов или администратор уже был создан раньше с другим паролем (`.env` применяется только при первом запуске) |
| «Слишком много попыток входа» | Не больше 10 попыток в минуту с одного адреса — подождать минуту |
