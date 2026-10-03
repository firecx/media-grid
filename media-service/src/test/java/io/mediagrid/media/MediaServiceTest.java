package io.mediagrid.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.common.events.ProcessingCompletedEvent;
import io.mediagrid.media.media.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Служба на настоящих PostgreSQL и RabbitMQ: каталог, права, поиск, события, очистка. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MediaServiceTest.Infrastructure.class)
class MediaServiceTest {

    /** Очередь теста, куда попадают события удаления, отправленные службой. */
    private static final String DELETED_PROBE = "test.media-deleted";

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6-alpine3.24");
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management");
        }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestTokens.decoder();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MediaService mediaService;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void probeDeletedEvents() {
        Queue probe = new Queue(DELETED_PROBE, true);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(Events.EXCHANGE))
                .with(Events.MEDIA_DELETED));
        amqpAdmin.purgeQueue(DELETED_PROBE, false);
    }

    @Test
    void createdRecordWaitsForUploadAndIsSeenOnlyByOwner() throws Exception {
        String id = createMedia(alice, "Отпуск", "video/MP4; codecs=avc1", "PUBLIC", "Море", " море ", "Лето")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.mediaKind").value("VIDEO"))
                .andExpect(jsonPath("$.contentType").value("video/mp4"))
                .andExpect(jsonPath("$.ownerId").value(alice.toString()))
                .andExpect(jsonPath("$.tags", contains("лето", "море")))
                .andReturn().getResponse().getContentAsString();
        String mediaId = JsonPath.read(id, "$.id");

        mvc.perform(get("/api/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isOk());
        // Пока файл не загружен, даже общедоступная запись чужим не видна
        mvc.perform(get("/api/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void onlyImagesVideoAndAudioWithinSizeLimitAreAccepted() throws Exception {
        createMedia(alice, "Документ", "application/pdf", "PRIVATE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        mvc.perform(post("/api/media").header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mediaJson("Большой", "video/mp4", 21L * 1024 * 1024 * 1024, "PRIVATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void statusFollowsUploadAndProcessingEvents() throws Exception {
        UUID id = createdId(alice, "Клип", "video/mp4", "PRIVATE");

        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED,
                new FileUploadedEvent(id, alice, "video/mp4", 1024));
        awaitStatus(alice, id, "UPLOADED");

        rabbit.convertAndSend(Events.EXCHANGE, Events.PROCESSING_COMPLETED,
                new ProcessingCompletedEvent(id, true, true, null));
        awaitStatus(alice, id, "READY");
        mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.hasPreview").value(true))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty());
    }

    @Test
    void processingResultArrivingBeforeUploadEventStillCompletesRecord() throws Exception {
        UUID id = createdId(alice, "Быстрое фото", "image/jpeg", "PRIVATE");

        rabbit.convertAndSend(Events.EXCHANGE, Events.PROCESSING_COMPLETED,
                new ProcessingCompletedEvent(id, true, true, null));
        awaitStatus(alice, id, "READY");
        mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty());

        // Запоздавшее событие о загрузке статус не откатывает
        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED, new FileUploadedEvent(id, alice, "image/jpeg", 1));
        Thread.sleep(500);
        awaitStatus(alice, id, "READY");
    }

    @Test
    void serviceTokenIsNotAcceptedOnUserPaths() throws Exception {
        mvc.perform(get("/api/media").header(HttpHeaders.AUTHORIZATION, TestTokens.service("processing-service")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void failedProcessingKeepsFileAvailable() throws Exception {
        UUID id = createdId(alice, "Битый клип", "video/mp4", "PRIVATE");
        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED, new FileUploadedEvent(id, alice, "video/mp4", 1));
        awaitStatus(alice, id, "UPLOADED");

        rabbit.convertAndSend(Events.EXCHANGE, Events.PROCESSING_COMPLETED,
                new ProcessingCompletedEvent(id, false, false, "ffmpeg: неизвестный кодек"));
        awaitStatus(alice, id, "FAILED");
        mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.processingError").value("ffmpeg: неизвестный кодек"));
    }

    @Test
    void uploadForDeletedRecordAsksStorageToRemoveFile() {
        UUID unknown = UUID.randomUUID();
        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED,
                new FileUploadedEvent(unknown, UUID.randomUUID(), "video/mp4", 1));
        assertThat(receiveDeleted()).isEqualTo(new MediaDeletedEvent(unknown));
    }

    @Test
    void privateFilesAreHiddenAndPublicAreReadOnlyForOthers() throws Exception {
        UUID privateId = uploaded(alice, "Личное", "image/png", "PRIVATE");
        UUID publicId = uploaded(alice, "Для всех", "image/png", "PUBLIC");

        mvc.perform(get("/api/media/" + privateId).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/media/" + publicId).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/media/" + publicId).header(HttpHeaders.AUTHORIZATION, user(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Моё\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/media/" + publicId).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isForbidden());

        // Администратор видит и меняет всё
        mvc.perform(get("/api/media/" + privateId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/media/" + privateId).header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\": \"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void searchCombinesConditionsAndRespectsVisibility() throws Exception {
        UUID owner = UUID.randomUUID();
        String marker = "поиск" + UUID.randomUUID().toString().substring(0, 8);
        uploaded(owner, marker + " закат", "image/jpeg", "PUBLIC", "природа", "небо");
        uploaded(owner, marker + " рассвет", "image/jpeg", "PUBLIC", "природа");
        uploaded(owner, marker + " песня", "audio/mpeg", "PUBLIC", "природа");
        uploaded(owner, marker + " тайна", "image/jpeg", "PRIVATE", "природа");

        mvc.perform(get("/api/media").param("q", marker.toUpperCase()).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/media").param("q", marker).param("type", "IMAGE").param("tag", "природа")
                        .param("sort", "title").param("direction", "asc")
                        .header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].title", contains(marker + " закат", marker + " рассвет")));
        mvc.perform(get("/api/media").param("q", marker).param("tag", "Природа").param("tag", "небо")
                        .header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/media").param("q", marker).param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, userWithId(owner)))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/media").param("q", "%").header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isOk());
    }

    @Test
    void categoriesAreManagedByAdminAndUsedForFiltering() throws Exception {
        String name = "Концерты " + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post("/api/media/admin/categories").header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isForbidden());

        String body = mvc.perform(post("/api/media/admin/categories").header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String categoryId = JsonPath.read(body, "$.id");
        mvc.perform(post("/api/media/admin/categories").header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name.toUpperCase() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));

        UUID id = createdId(alice, "Живой звук", "audio/flac", "PRIVATE");
        mvc.perform(patch("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\": \"" + categoryId + "\"}"))
                .andExpect(jsonPath("$.category.name").value(name));
        mvc.perform(get("/api/media").param("categoryId", categoryId).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/media/categories").header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.hasItem(name)));

        // Удаление категории оставляет файлы без категории
        mvc.perform(delete("/api/media/admin/categories/" + categoryId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.category").doesNotExist());
    }

    @Test
    void tagsAreReplacedAndValidated() throws Exception {
        UUID id = createdId(alice, "Фото", "image/webp", "PRIVATE", "старый");
        mvc.perform(patch("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\": [\"новый\", \"ещё один\"]}"))
                .andExpect(jsonPath("$.tags", containsInAnyOrder("новый", "ещё один")));
        mvc.perform(patch("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\": [\"<script>\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TAG"));
    }

    @Test
    void deletionRemovesRecordAndNotifiesStorage() throws Exception {
        UUID id = uploaded(alice, "Удаляемый", "image/png", "PRIVATE");
        mvc.perform(delete("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isNotFound());
        assertThat(receiveDeleted()).isEqualTo(new MediaDeletedEvent(id));
    }

    @Test
    void recordsWithoutUploadedFileAreCleanedUp() throws Exception {
        UUID stale = createdId(alice, "Брошенная загрузка", "video/mp4", "PRIVATE");
        UUID fresh = createdId(alice, "Свежая загрузка", "video/mp4", "PRIVATE");
        jdbc.update("UPDATE media.media_files SET created_at = now() - interval '25 hours' WHERE id = ?", stale);

        assertThat(mediaService.deleteStalePendingUploads()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/media/" + stale).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/media/" + fresh).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isOk());
        assertThat(receiveDeleted()).isEqualTo(new MediaDeletedEvent(stale));
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/media"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- вспомогательное ---

    private ResultActions createMedia(UUID owner, String title, String contentType, String visibility,
                                      String... tags) throws Exception {
        return mvc.perform(post("/api/media").header(HttpHeaders.AUTHORIZATION, userWithId(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mediaJson(title, contentType, 1024, visibility, tags)));
    }

    private UUID createdId(UUID owner, String title, String contentType, String visibility, String... tags)
            throws Exception {
        String body = createMedia(owner, title, contentType, visibility, tags)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Запись, для которой служба хранения уже сообщила о загрузке файла. */
    private UUID uploaded(UUID owner, String title, String contentType, String visibility, String... tags)
            throws Exception {
        UUID id = createdId(owner, title, contentType, visibility, tags);
        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED,
                new FileUploadedEvent(id, owner, contentType, 1024));
        awaitStatus(owner, id, "UPLOADED");
        return id;
    }

    private void awaitStatus(UUID owner, UUID id, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/media/" + id).header(HttpHeaders.AUTHORIZATION, userWithId(owner)))
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    private MediaDeletedEvent receiveDeleted() {
        Object message = rabbit.receiveAndConvert(DELETED_PROBE, 10_000);
        assertThat(message).as("событие удаления в шине").isInstanceOf(MediaDeletedEvent.class);
        return (MediaDeletedEvent) message;
    }

    private static String mediaJson(String title, String contentType, long size, String visibility, String... tags) {
        StringBuilder tagsJson = new StringBuilder();
        for (String tag : tags) {
            tagsJson.append(tagsJson.isEmpty() ? "" : ", ").append('"').append(tag).append('"');
        }
        return "{\"title\": \"" + title + "\", \"originalFilename\": \"file.bin\", \"contentType\": \""
                + contentType + "\", \"sizeBytes\": " + size + ", \"visibility\": \"" + visibility
                + "\", \"tags\": [" + tagsJson + "]}";
    }

    private String user(UUID id) {
        return TestTokens.bearer(id, "USER");
    }

    private String userWithId(UUID id) {
        return id.equals(admin) ? adminToken() : user(id);
    }

    private String adminToken() {
        return TestTokens.bearer(admin, "ADMIN");
    }
}
