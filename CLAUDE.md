Проект MediaGrid: самостоятельно разворачиваемая медиасистема (хранение, каталогизация и выдача изображений, видео, аудио через веб-интерфейс). Микросервисная архитектура, монорепозиторий: https://github.com/firecx/media-grid (ветка main). Прикладываю ТЗ.docx.

Решения
- Язык и каркас: Java 21, Spring Boot 4.1.x (в pom.xml 4.1.1), Spring Cloud 2025.1.x (2025.1.2). Ветка Spring Boot 3.5 снята с поддержки, поэтому выбрана 4.1.
- Сборка: Maven 3.10.0. Родительский pom.xml в корне хранит только версии и список модулей; у каждой службы свой pom.xml. Контекст сборки образов — корень репозитория, чтобы подтягивался модуль common.
- База данных: образ postgres:18.6-alpine3.24, том подключается к /var/lib/postgresql. Одна база, по одной схеме на службу: auth, media, storage, processing (infra/postgres/init.sql).
- Шина сообщений: RabbitMQ 4 (выбрана по умолчанию, не обсуждалась отдельно).
- Файлохранилище: пока локальная файловая система за интерфейсом StorageProvider (save, getReadStream, getDownloadUrl, delete, exists); MinIO отложен. В таблице media_files поля storage_backend и storage_key.
- Версии проекта 1.2.3: первое число — глобальные релизы (законченное ПО), второе — крупное обновление, возможно с новой службой, третье — мелкие правки и исправления. Сейчас 0.1.0-SNAPSHOT. Версия образа службы равна версии проекта (переменная MEDIAGRID_VERSION). Смена: mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
- В русском тексте не использовать английские термины.

Состав служб (по ТЗ): discovery (регистр служб), config-server (центр конфигурации), gateway (шлюз), auth-service (авторизация), media-service (метаданные, поиск, теги), storage-service (загрузка и хранение, потоковая передача, ссылки с ограниченным сроком действия), processing-service (транскодирование, превью, очередь), позже веб-интерфейс. Поддержка устойчивости к сбоям, сквозная трассировка, централизованные журналы.

Сделано
- Корень: pom.xml, docker-compose.yaml (postgres, rabbitmq, discovery), .env.example (с MEDIAGRID_VERSION), .dockerignore, .gitignore, README.md.
- common: события FileUploadedEvent и ProcessingCompletedEvent, единый формат ошибки ApiError. Бизнес-логики нет.
- discovery (Eureka, порт 8761): pom.xml (стартер spring-cloud-starter-netflix-eureka-server), DiscoveryApplication.java, application.yml, Dockerfile. Сборка mvn package проходит, jar запускается, /actuator/health отвечает UP. Сборка образа через docker compose ещё не проверялась.
- Известная проблема: Dockerfile службы копирует только корневой pom.xml, common и свой модуль. Как только в корневой pom.xml добавится следующий модуль, Maven в образе не найдёт его каталог и сборка упадёт. Решить при добавлении config-server (например, копировать все pom.xml модулей).

Порядок дальше: config-server (конфигурация из каталога config/ в этом же репозитории), gateway (пока без проверки токенов), auth-service, media-service, storage-service, processing-service, веб-интерфейс.

Правило работы: когда отправляешь файл, указывай полный путь от корня проекта и пометку «новый» или «изменён».