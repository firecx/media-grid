package io.mediagrid.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.IntConsumer;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.common.events.ProcessingCompletedEvent;
import io.mediagrid.processing.job.JobContext;
import io.mediagrid.processing.job.JobFailure;
import io.mediagrid.processing.media.MediaTools;
import io.mediagrid.processing.media.Probe;
import io.mediagrid.processing.storage.StorageClient;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Служба на настоящих PostgreSQL и RabbitMQ: от события file.uploaded до processing.completed.
 * ffmpeg и служба хранения подменены; сам ffmpeg проверяется в Docker.
 */
@SpringBootTest(properties = {
        "mediagrid.processing.retry-delay=200ms",
        "mediagrid.processing.poll-interval=200ms"})
@AutoConfigureMockMvc
@Import(ProcessingFlowTest.Infrastructure.class)
class ProcessingFlowTest {

    private static final String COMPLETED_PROBE = "test.processing-completed";
    private static final String MKV = "video/x-matroska";

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
    StorageClient storage;

    @MockitoBean
    MediaTools tools;

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Queue probe = new Queue(COMPLETED_PROBE, true);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(Events.EXCHANGE))
                .with(Events.PROCESSING_COMPLETED));
        amqpAdmin.purgeQueue(COMPLETED_PROBE, false);

        // Видео H.264 со звуком Opus в MKV: картинка копируется, звук перекодируется
        doAnswer(inv -> write(inv.getArgument(1))).when(storage).downloadOriginal(any(), any());
        when(tools.probe(any(), any())).thenReturn(new Probe("matroska,webm", 60_000L,
                new Probe.Stream(0, "h264", 1280, 720, "yuv420p"), new Probe.Stream(1, "opus", null, null, null),
                null));
        doAnswer(inv -> write(inv.getArgument(4)))
                .when(tools).snapshot(any(), anyInt(), anyDouble(), anyInt(), any(), any());
        doAnswer(inv -> {
            inv.<IntConsumer>getArgument(5).accept(50);
            return write(inv.getArgument(3));
        }).when(tools).transcode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadedFileIsProcessedAndResultAnnounced() throws Exception {
        UUID id = uploaded(alice, MKV);

        ProcessingCompletedEvent event = awaitCompleted(id);
        assertThat(event.success()).isTrue();
        assertThat(event.hasPreview()).isTrue();
        assertThat(event.errorMessage()).isNull();
        verify(storage).storeVariant(eq(id), eq("thumbnail"), any(), eq("image/jpeg"));
        verify(storage).storeVariant(eq(id), eq("preview"), any(), eq("image/jpeg"));
        verify(storage).storeVariant(eq(id), eq("playback"), any(), eq("video/mp4"));

        mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.durationMs").value(60_000))
                .andExpect(jsonPath("$.width").value(1280))
                .andExpect(jsonPath("$.videoCodec").value("h264"))
                .andExpect(jsonPath("$.audioCodec").value("opus"))
                .andExpect(jsonPath("$.transcoded").value(true))
                .andExpect(jsonPath("$.hasPreview").value(true))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        // Чужой ход обработки не виден, администратору виден
        mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, user(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk());
    }

    @Test
    void brokenFileFailsAtOnceAndAdminCanRetry() throws Exception {
        UUID id = UUID.randomUUID();
        when(tools.probe(inDirectoryOf(id), any()))
                .thenThrow(new JobFailure.Permanent("Файл не распознан (код 1): Invalid data found"))
                .thenReturn(new Probe("mov,mp4,m4a,3gp,3g2,mj2", 1_000L,
                        new Probe.Stream(0, "h264", 640, 360, "yuv420p"), null, null));
        send(id, alice, "video/mp4");

        ProcessingCompletedEvent failed = awaitCompleted(id);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errorMessage()).contains("Invalid data found");
        mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(1));

        mvc.perform(post("/api/processing/admin/jobs/" + id + "/retry").header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/processing/admin/jobs?status=FAILED").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.mediaId == '" + id + "')]").exists());
        mvc.perform(post("/api/processing/admin/jobs/" + id + "/retry").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk());

        ProcessingCompletedEvent retried = awaitCompleted(id);
        assertThat(retried.success()).isTrue();
        // Уже подходящее для браузера видео не перекодируется
        verify(storage, never()).storeVariant(eq(id), eq("playback"), any(), any());
    }

    @Test
    void temporaryStorageFailureIsRetriedWithPause() throws Exception {
        UUID id = UUID.randomUUID();
        doAnswer(inv -> {
            throw new JobFailure.Transient("Служба хранения ответила 503");
        }).doAnswer(inv -> write(inv.getArgument(1)))
                .when(storage).downloadOriginal(eq(id), any());
        send(id, alice, MKV);

        assertThat(awaitCompleted(id).success()).isTrue();
        mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void deletingRecordStopsRunningProcessing() throws Exception {
        UUID id = UUID.randomUUID();
        // Перекодирование идёт, пока задачу не остановят
        doAnswer(inv -> {
            JobContext context = inv.getArgument(4);
            await().atMost(Duration.ofSeconds(20)).until(context::isCancelled);
            context.checkCancelled();
            return null;
        }).when(tools).transcode(inDirectoryOf(id), any(), any(), any(), any(), any());
        send(id, alice, MKV);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, user(alice)))
                        .andExpect(jsonPath("$.status").value("RUNNING"))
                        .andExpect(jsonPath("$.stage").value("TRANSCODING")));

        rabbit.convertAndSend(Events.EXCHANGE, Events.MEDIA_DELETED, new MediaDeletedEvent(id));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/processing/" + id).header(HttpHeaders.AUTHORIZATION, admin()))
                        .andExpect(status().isNotFound()));
        Thread.sleep(500);
        verify(storage, never()).storeVariant(eq(id), any(), any(), any());
        assertThat(rabbit.receiveAndConvert(COMPLETED_PROBE, 500)).isNull();
    }

    @Test
    void repeatedUploadEventResendsResultInsteadOfProcessingAgain() throws Exception {
        UUID id = uploaded(alice, MKV);
        awaitCompleted(id);

        send(id, alice, MKV);

        assertThat(awaitCompleted(id).success()).isTrue();
        verify(storage).downloadOriginal(eq(id), any());
    }

    @Test
    void userPathsNeedUserToken() throws Exception {
        mvc.perform(get("/api/processing/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/processing/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service("storage-service")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/processing/admin/jobs?status=NOPE").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- вспомогательное ---

    private UUID uploaded(UUID owner, String contentType) {
        UUID id = UUID.randomUUID();
        send(id, owner, contentType);
        return id;
    }

    private void send(UUID id, UUID owner, String contentType) {
        rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED, new FileUploadedEvent(id, owner, contentType, 3));
    }

    /** Ждёт processing.completed об этом файле; итоги других файлов пропускаются. */
    private ProcessingCompletedEvent awaitCompleted(UUID id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Object message = rabbit.receiveAndConvert(COMPLETED_PROBE, 1_000);
            if (message instanceof ProcessingCompletedEvent event && event.mediaId().equals(id)) {
                return event;
            }
        }
        throw new AssertionError("Нет processing.completed для " + id);
    }

    private static Path inDirectoryOf(UUID id) {
        return argThat(path -> path != null && path.toString().contains(id.toString()));
    }

    private static Object write(Path file) throws Exception {
        Files.write(file, new byte[] {1, 2, 3});
        return null;
    }

    private static String user(UUID id) {
        return TestTokens.bearer(id, "USER");
    }

    private String admin() {
        return TestTokens.bearer(UUID.randomUUID(), "ADMIN");
    }
}
