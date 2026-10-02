Проект MediaGrid: самостоятельно разворачиваемая медиасистема (хранение, каталогизация и выдача изображений, видео, аудио через веб-интерфейс). Микросервисная архитектура, монорепозиторий: https://github.com/firecx/media-grid (ветка main). Прикладываю ТЗ.docx.

Решения
- Язык и каркас: Java 21, Spring Boot 4.1.x (в pom.xml 4.1.1), Spring Cloud 2025.1.x (2025.1.2). Ветка Spring Boot 3.5 снята с поддержки, поэтому выбрана 4.1.
- Сборка: Maven 3.10.0. Родительский pom.xml в корне хранит только версии и список модулей; у каждой службы свой pom.xml. Контекст сборки образов — корень репозитория, чтобы подтягивался модуль common.
- База данных: образ postgres:18.6-alpine3.24, том подключается к /var/lib/postgresql. Одна база, по одной схеме на службу: auth, media, storage, processing (infra/postgres/init.sql).
- Шина сообщений: RabbitMQ 4 (выбрана по умолчанию, не обсуждалась отдельно).
- Файлохранилище: пока локальная файловая система за интерфейсом StorageProvider (save, getReadStream, getDownloadUrl, delete, exists); MinIO отложен. В таблице media_files поля storage_backend и storage_key.
- Версии проекта 1.2.3: первое число — глобальные релизы (законченное ПО), второе — крупное обновление, возможно с новой службой, третье — мелкие правки и исправления. Сейчас 0.3.0 (выпуск: discovery, config-server, gateway). Порядок выпуска: работа ведётся под X.Y.0-SNAPSHOT; при выпуске убрать суффикс, перенести раздел «в работе» в CHANGELOG.md под дату, после коммита поставить метку vX.Y.0 и перейти на следующую -SNAPSHOT. Версия образа службы равна версии проекта (переменная MEDIAGRID_VERSION). Смена: mvn versions:set -DnewVersion=0.4.0-SNAPSHOT -DgenerateBackupPoms=false. При каждой смене версии добавлять запись в CHANGELOG.md (новые сверху, разделы «Добавлено», «Изменено», «Исправлено», «Удалено»).
- В русском тексте не использовать английские термины.

Состав служб (по ТЗ): discovery (регистр служб), config-server (центр конфигурации), gateway (шлюз), auth-service (авторизация), media-service (метаданные, поиск, теги), storage-service (загрузка и хранение, потоковая передача, ссылки с ограниченным сроком действия), processing-service (транскодирование, превью, очередь), позже веб-интерфейс. Поддержка устойчивости к сбоям, сквозная трассировка, централизованные журналы.

Сделано
- Корень: pom.xml, docker-compose.yaml (postgres, rabbitmq, discovery, config-server, gateway), CHANGELOG.md, .env.example (с MEDIAGRID_VERSION), .dockerignore, .gitignore, README.md.
- common: события FileUploadedEvent и ProcessingCompletedEvent, единый формат ошибки ApiError. Бизнес-логики нет.
- discovery (Eureka, порт 8761): стартер spring-cloud-starter-netflix-eureka-server. Образ собирается, контейнер здоров.
- config-server (порт 8888): режим native, читает каталог config/ (локально file:./config/, в контейнере том ./config:/config, переменная CONFIG_LOCATION). Регистрируется в discovery. Образ собирается, контейнер здоров, настройки раздаются (GET /<служба>/default).
- config/application.yml: общие настройки служб (адрес регистра через EUREKA_URL, prefer-ip-address, открытые точки health и info). Пароли в config/ не хранить, только подстановки ${...} из переменных среды службы.
- Устройство Dockerfile служб: COPY . . (лишнее отсекает .dockerignore, каталог config/ в образы не попадает), сборка mvn -pl <служба> -am с кэшем BuildKit для ~/.m2, запуск на eclipse-temurin:21-jre-alpine. Проверка готовности в docker-compose через wget на /actuator/health.
- Кэш балансировщика нагрузки отключён в discovery и config-server (они никого не вызывают). Службам, которые вызывают друг друга через балансировщик, подключать зависимость caffeine.
- gateway (порт 8080, служебные точки на 8081, наружу не публикуется): стартер spring-cloud-starter-gateway-server-webflux (старого spring-cloud-starter-gateway в 2025.1 нет; настройки под spring.cloud.gateway.server.webflux.*). Настройки берёт из config-server (CONFIG_URL), маршруты в config/gateway.yml: /api/auth/** → auth-service, /api/media/** → media-service, /api/files/** → storage-service, путь передаётся без изменений (службы сами обслуживают /api/<раздел>/**). Точка /actuator/gateway/routes открыта только на чтение (management.endpoint.gateway.access: read-only). Проверено: регистрация в discovery, 503 для маршрутов к ещё не созданным службам, 404 для неизвестных путей, правка config/ подхватывается перезапуском без пересборки.
- Пока не сделано в шлюзе: проверка токенов, повторные попытки и размыкание цепи (делать вместе с первой прикладной службой, чтобы было что проверить; ограничение времени размыкателя не должно обрывать долгую потоковую передачу файлов), ответ об ошибке в формате ApiError.

Порядок дальше: auth-service, media-service, storage-service, processing-service, веб-интерфейс. Прикладные службы получают настройки через spring.config.import: configserver:http://config-server:8888.

Правило работы: когда отправляешь файл, указывай полный путь от корня проекта и пометку «новый» или «изменён».