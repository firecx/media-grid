package io.mediagrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.storage.config.StorageProperties;
import io.mediagrid.storage.file.FileVariantRepository;
import io.mediagrid.storage.events.FileAnnouncer;
import io.mediagrid.storage.file.StoredFileRepository;
import io.mediagrid.storage.file.VariantKind;
import io.mediagrid.storage.link.LinkSigner;
import io.mediagrid.storage.media.MediaClient;
import io.mediagrid.storage.media.MediaInfo;
import io.mediagrid.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Служба на настоящих PostgreSQL и RabbitMQ: загрузка целиком и с докачкой, проверки, ссылки,
 * частичная выдача, удаление по событию. Служба медиаданных заменена заглушкой.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StorageServiceTest.Infrastructure.class)
class StorageServiceTest {

    private static final String UPLOADED_PROBE = "test.file-uploaded";

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

    @MockitoBean
    MediaClient mediaClient;

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    StoredFileRepository files;

    @Autowired
    LinkSigner signer;

    @Autowired
    FileVariantRepository variants;

    @Autowired
    StorageProperties storageProperties;

    @Autowired
    FileAnnouncer fileAnnouncer;

    @Autowired
    JdbcTemplate jdbc;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    @BeforeEach
    void probeUploadedEvents() {
        Queue probe = new Queue(UPLOADED_PROBE, true);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(Events.EXCHANGE))
                .with(Events.FILE_UPLOADED));
        amqpAdmin.purgeQueue(UPLOADED_PROBE, false);
    }

    @Test
    void wholeFileIsStoredAndAnnounced() throws Exception {
        byte[] data = bytes(1000);
        UUID id = pendingMedia(alice, data.length);

        upload(alice, id, data, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Upload-Offset", "1000"))
                .andExpect(jsonPath("$.complete").value(true));

        Object event = rabbit.receiveAndConvert(UPLOADED_PROBE, 10_000);
        assertThat(event).isEqualTo(new FileUploadedEvent(id, alice, "video/mp4", 1000));
        mvc.perform(get(link(alice, id, false))).andExpect(status().isOk()).andExpect(content().bytes(data));
        // Шина подтвердила приём — досылать нечего
        assertThat(announcedAt(id)).isNotNull();
    }

    @Test
    void unconfirmedUploadEventIsResent() throws Exception {
        byte[] data = bytes(100);
        UUID id = pendingMedia(alice, data.length);
        upload(alice, id, data, null).andExpect(status().isOk());
        assertThat(rabbit.receiveAndConvert(UPLOADED_PROBE, 10_000)).isInstanceOf(FileUploadedEvent.class);
        // Как если бы шина была недоступна в момент получения файла минуту назад
        jdbc.update("UPDATE storage.stored_files SET announced_at = NULL, completed_at = now() - interval '2 minutes' "
                + "WHERE media_id = ?", id);

        fileAnnouncer.announcePending();

        assertThat(rabbit.receiveAndConvert(UPLOADED_PROBE, 10_000))
                .isEqualTo(new FileUploadedEvent(id, alice, "video/mp4", 100));
        assertThat(announcedAt(id)).isNotNull();
    }

    private Object announcedAt(UUID id) {
        return jdbc.queryForObject("SELECT announced_at FROM storage.stored_files WHERE media_id = ?", Object.class, id);
    }

    @Test
    void interruptedUploadContinuesFromReceivedOffset() throws Exception {
        byte[] data = bytes(30);
        UUID id = pendingMedia(alice, data.length);

        upload(alice, id, Arrays.copyOfRange(data, 0, 10), "bytes 0-9/30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(10))
                .andExpect(jsonPath("$.complete").value(false));
        mvc.perform(head("/api/files/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string("Upload-Offset", "10"))
                .andExpect(header().string("Upload-Length", "30"));

        // Пропуск части или повтор уже полученной — отказ с указанием верного места
        upload(alice, id, Arrays.copyOfRange(data, 20, 30), "bytes 20-29/30")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_OFFSET_MISMATCH"))
                .andExpect(jsonPath("$.message", containsString("10")));

        upload(alice, id, Arrays.copyOfRange(data, 10, 30), "bytes 10-29/30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true));
        assertThat(rabbit.receiveAndConvert(UPLOADED_PROBE, 10_000)).isInstanceOf(FileUploadedEvent.class);
        mvc.perform(get(link(alice, id, false))).andExpect(content().bytes(data));
    }

    @Test
    void uploadWithoutRangeStartsOver() throws Exception {
        byte[] data = bytes(20);
        UUID id = pendingMedia(alice, data.length);
        upload(alice, id, Arrays.copyOfRange(data, 0, 5), "bytes 0-4/20").andExpect(status().isOk());

        upload(alice, id, data, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true));
        mvc.perform(get(link(alice, id, false))).andExpect(content().bytes(data));
    }

    @Test
    void wrongSizesAreRejectedWithoutStoringData() throws Exception {
        UUID id = pendingMedia(alice, 10);

        upload(alice, id, bytes(20), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SIZE_MISMATCH"));
        upload(alice, id, bytes(5), "bytes 0-4/99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SIZE_MISMATCH"));
        upload(alice, id, bytes(5), "bytes 5-0/10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTENT_RANGE"));
        assertThat(files.findById(id)).hasValueSatisfying(f -> assertThat(f.getReceivedSize()).isZero());
    }

    @Test
    void onlyOwnerUploadsAndOnlyIntoPendingRecord() throws Exception {
        UUID id = pendingMedia(alice, 10);
        upload(bob, id, bytes(10), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        UUID uploadedElsewhere = UUID.randomUUID();
        when(mediaClient.get(eq(uploadedElsewhere), any()))
                .thenReturn(new MediaInfo(uploadedElsewhere, alice, "a.mp4", "video/mp4", 10, "READY"));
        upload(alice, uploadedElsewhere, bytes(10), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_UPLOADED"));
    }

    @Test
    void linksGivePartialContentForSeekingAndDownloadsWithFileName() throws Exception {
        byte[] data = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
        UUID id = pendingMedia(alice, data.length);
        upload(alice, id, data, null).andExpect(status().isOk());

        String stream = link(alice, id, false);
        mvc.perform(get(stream).header(HttpHeaders.RANGE, "bytes=10-14"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-14/20"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
                .andExpect(content().bytes("abcde".getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(get(stream).header(HttpHeaders.RANGE, "bytes=15-"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes("fghij".getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(get(stream).header(HttpHeaders.RANGE, "bytes=100-200"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */20"));
        mvc.perform(get(stream))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")));

        mvc.perform(get(link(alice, id, true)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("UTF-8''")));
    }

    @Test
    void forgedOrExpiredLinksAreRefused() throws Exception {
        UUID id = pendingMedia(alice, 10);
        upload(alice, id, bytes(10), null).andExpect(status().isOk());
        String good = link(alice, id, false);

        mvc.perform(get(good.replaceAll("signature=[^&]+", "signature=AAAA")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));
        // Ссылку нельзя продлить, переправив срок
        mvc.perform(get(good.replaceAll("expires=\\d+", "expires=" + (Instant.now().getEpochSecond() + 999_999))))
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));
        // Ни на скачивание вместо просмотра
        mvc.perform(get(good.replace("download=false", "download=true")))
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));
        // Ни на производный файл вместо исходного
        mvc.perform(get(good.replace("variant=original", "variant=preview")))
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));

        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        String expired = "/api/files/" + id + "/content?expires=" + past.getEpochSecond()
                + "&download=false&signature=" + signer.sign(id, VariantKind.ORIGINAL,
                Instant.ofEpochSecond(past.getEpochSecond()), false);
        mvc.perform(get(expired))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
    }

    @Test
    void noLinkBeforeFileIsUploaded() throws Exception {
        UUID id = pendingMedia(alice, 10);
        mvc.perform(post("/api/files/" + id + "/links").header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_UPLOADED"));
    }

    @Test
    void deletedRecordRemovesFile() throws Exception {
        UUID id = pendingMedia(alice, 10);
        upload(alice, id, bytes(10), null).andExpect(status().isOk());
        String link = link(alice, id, false);

        rabbit.convertAndSend(Events.EXCHANGE, Events.MEDIA_DELETED, new MediaDeletedEvent(id));

        await().atMost(Duration.ofSeconds(10)).until(() -> files.findById(id).isEmpty());
        mvc.perform(get(link)).andExpect(status().isNotFound());
    }

    @Test
    void unavailableMediaServiceGives503() throws Exception {
        UUID id = UUID.randomUUID();
        when(mediaClient.get(eq(id), any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE", "Служба медиаданных временно недоступна"));
        upload(alice, id, bytes(10), null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void processingServiceReadsOriginalAndStoresVariants() throws Exception {
        byte[] data = bytes(100);
        UUID id = pendingMedia(alice, data.length);
        upload(alice, id, data, null).andExpect(status().isOk());

        mvc.perform(get("/internal/files/" + id).header(HttpHeaders.AUTHORIZATION, service()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(content().bytes(data));

        // Пока версии для воспроизведения нет, браузер получает исходный файл
        assertThat(JsonPath.<String>read(linkBody(alice, id, "playback", false), "$.variant")).isEqualTo("original");
        mvc.perform(post("/api/files/" + id + "/links").header(HttpHeaders.AUTHORIZATION, user(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variant\": \"thumbnail\"}"))
                .andExpect(status().isNotFound());

        byte[] preview = bytes(40);
        storeVariant(id, "preview", "image/jpeg", preview)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("preview"))
                .andExpect(jsonPath("$.sizeBytes").value(40));
        storeVariant(id, "playback", "video/mp4", bytes(70)).andExpect(status().isOk());

        mvc.perform(get(link(alice, id, "preview", true)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("-preview.jpg")))
                .andExpect(content().bytes(preview));
        String playback = linkBody(alice, id, "playback", false);
        assertThat(JsonPath.<String>read(playback, "$.variant")).isEqualTo("playback");
        mvc.perform(get(JsonPath.<String>read(playback, "$.url")).header(HttpHeaders.RANGE, "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-9/70"));

        // Повторная обработка заменяет версию, прежняя удаляется из хранилища
        byte[] newPreview = bytes(25);
        storeVariant(id, "preview", "image/jpeg", newPreview).andExpect(status().isOk());
        mvc.perform(get(link(alice, id, "preview", false))).andExpect(content().bytes(newPreview));
        Path variantDir = storageProperties.root().resolve("variants").resolve(id.toString().substring(0, 2))
                .resolve(id.toString());
        assertThat(filesIn(variantDir)).isEqualTo(2);

        rabbit.convertAndSend(Events.EXCHANGE, Events.MEDIA_DELETED, new MediaDeletedEvent(id));
        await().atMost(Duration.ofSeconds(10)).until(() -> files.findById(id).isEmpty());
        assertThat(variants.findByMediaId(id)).isEmpty();
        assertThat(filesIn(variantDir)).isZero();
    }

    @Test
    void internalPathsAreForServicesOnly() throws Exception {
        UUID id = pendingMedia(alice, 10);
        upload(alice, id, bytes(10), null).andExpect(status().isOk());

        mvc.perform(get("/internal/files/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/files/" + id).header(HttpHeaders.AUTHORIZATION, service()).content(bytes(10)))
                .andExpect(status().isForbidden());

        storeVariant(id, "original", "video/mp4", bytes(5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VARIANT"));
        storeVariant(id, "poster", "image/jpeg", bytes(5))
                .andExpect(jsonPath("$.code").value("INVALID_VARIANT"));
        storeVariant(UUID.randomUUID(), "preview", "image/jpeg", bytes(5))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/files/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, service()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadNeedsToken() throws Exception {
        mvc.perform(put("/api/files/" + UUID.randomUUID()).content(bytes(10)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- вспомогательное ---

    /** Запись каталога, ожидающая загрузки, — так её «видит» служба медиаданных. */
    private UUID pendingMedia(UUID owner, long size) {
        UUID id = UUID.randomUUID();
        when(mediaClient.get(eq(id), any()))
                .thenReturn(new MediaInfo(id, owner, "Клип № 1.mp4", "video/mp4", size, "PENDING_UPLOAD"));
        return id;
    }

    private ResultActions upload(UUID user, UUID id, byte[] data, String contentRange) throws Exception {
        var request = put("/api/files/" + id).header(HttpHeaders.AUTHORIZATION, user(user))
                .contentType(MediaType.valueOf("video/mp4"))
                .content(data);
        if (contentRange != null) {
            request.header(HttpHeaders.CONTENT_RANGE, contentRange);
        }
        return mvc.perform(request);
    }

    private String link(UUID user, UUID id, boolean download) throws Exception {
        return link(user, id, "original", download);
    }

    private String link(UUID user, UUID id, String variant, boolean download) throws Exception {
        return JsonPath.read(linkBody(user, id, variant, download), "$.url");
    }

    private String linkBody(UUID user, UUID id, String variant, boolean download) throws Exception {
        return mvc.perform(post("/api/files/" + id + "/links").header(HttpHeaders.AUTHORIZATION, user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"download\": " + download + ", \"variant\": \"" + variant + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions storeVariant(UUID id, String kind, String contentType, byte[] data) throws Exception {
        return mvc.perform(put("/internal/files/" + id + "/variants/" + kind)
                .header(HttpHeaders.AUTHORIZATION, service())
                .contentType(MediaType.valueOf(contentType))
                .content(data));
    }

    private static long filesIn(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    private static String user(UUID id) {
        return TestTokens.bearer(id, "USER");
    }

    private static String service() {
        return TestTokens.service("processing-service");
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }
}
