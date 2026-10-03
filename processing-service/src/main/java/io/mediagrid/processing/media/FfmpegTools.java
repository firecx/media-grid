package io.mediagrid.processing.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.processing.job.JobContext;
import io.mediagrid.processing.job.JobFailure;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * ffprobe и ffmpeg как отдельные процессы. Процесс останавливается по тайм-ауту и при остановке задачи;
 * в сообщение об ошибке попадают последние строки вывода ffmpeg.
 */
@Component
public class FfmpegTools implements MediaTools {

    /** Разбор файла и снимок кадра — быстрые операции. */
    static final Duration SHORT_TIMEOUT = Duration.ofMinutes(5);
    private static final int STDERR_TAIL_LINES = 15;
    private static final String OUT_TIME_US = "out_time_us=";

    private final ProcessingProperties properties;
    private final JsonMapper json;

    public FfmpegTools(ProcessingProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Probe probe(Path input, JobContext context) {
        StringBuilder out = new StringBuilder();
        run(List.of(properties.ffprobe(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                input.toString()), SHORT_TIMEOUT, context, line -> out.append(line).append('\n'), "Файл не распознан");
        return Probe.parse(out.toString(), json);
    }

    @Override
    public void snapshot(Path input, int streamIndex, double atSeconds, int maxSize, Path output, JobContext context) {
        List<String> command = new ArrayList<>(List.of(properties.ffmpeg(), "-hide_banner", "-nostdin", "-y"));
        if (atSeconds > 0) {
            // Перед -i: быстрый переход по ключевым кадрам, не декодируя всё до нужного места
            command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", atSeconds)));
        }
        command.addAll(List.of("-i", input.toString(), "-map", "0:" + streamIndex, "-frames:v", "1",
                "-vf", "scale=w='min(" + maxSize + ",iw)':h='min(" + maxSize + ",ih)'"
                        + ":force_original_aspect_ratio=decrease",
                "-q:v", "3", output.toString()));
        run(command, SHORT_TIMEOUT, context, line -> { }, "Не удалось получить кадр");
        if (!isNonEmpty(output)) {
            throw new JobFailure.Permanent("Не удалось получить кадр: ffmpeg не вывел изображение");
        }
    }

    @Override
    public void transcode(Path input, List<String> args, Long durationMs, Path output, JobContext context,
                          IntConsumer percent) {
        List<String> command = new ArrayList<>(List.of(properties.ffmpeg(), "-hide_banner", "-nostdin", "-y",
                "-i", input.toString()));
        command.addAll(args);
        // Ход работы — построчно в стандартный вывод: out_time_us=<сколько уже готово, мкс>
        command.addAll(List.of("-progress", "pipe:1", "-nostats", output.toString()));
        Consumer<String> progress = line -> {
            if (durationMs != null && line.startsWith(OUT_TIME_US)) {
                try {
                    long doneUs = Long.parseLong(line.substring(OUT_TIME_US.length()).trim());
                    percent.accept((int) Math.min(100, doneUs / 10 / durationMs));
                } catch (NumberFormatException e) {
                    // «N/A» в начале работы
                }
            }
        };
        run(command, properties.jobTimeout(), context, progress, "Не удалось перекодировать");
        if (!isNonEmpty(output)) {
            throw new JobFailure.Permanent("Не удалось перекодировать: ffmpeg не создал файл");
        }
    }

    /**
     * Запускает процесс и ждёт его. Стандартный вывод построчно передаётся в stdout, из вывода ошибок
     * сохраняются последние строки — для сообщения о сбое.
     */
    private void run(List<String> command, Duration timeout, JobContext context, Consumer<String> stdout,
                     String failureMessage) {
        context.checkCancelled();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new JobFailure.Permanent("Не удалось запустить " + command.getFirst() + ": " + e.getMessage());
        }
        context.attach(process);
        Deque<String> stderrTail = new ArrayDeque<>();
        Thread outReader = Thread.ofVirtual().start(() -> readLines(process.getInputStream(), stdout));
        Thread errReader = Thread.ofVirtual().start(() -> readLines(process.getErrorStream(), line -> {
            synchronized (stderrTail) {
                stderrTail.addLast(line);
                if (stderrTail.size() > STDERR_TAIL_LINES) {
                    stderrTail.removeFirst();
                }
            }
        }));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new JobFailure.Permanent(failureMessage + ": превышено время " + timeout);
            }
            outReader.join();
            errReader.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JobFailure.Gone("Обработка прервана");
        } finally {
            context.detach();
        }
        context.checkCancelled();
        if (process.exitValue() != 0) {
            String details;
            synchronized (stderrTail) {
                details = String.join("\n", stderrTail);
            }
            // Сообщение увидит пользователь: внутренние пути служб ему ни к чему
            String jobDir = properties.workDir().resolve(context.mediaId().toString()).toString();
            details = details.replace(jobDir + "/", "").replace(jobDir + "\\", "");
            throw new JobFailure.Permanent(failureMessage + " (код " + process.exitValue() + "): " + details);
        }
    }

    private static void readLines(InputStream stream, Consumer<String> lines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.accept(line);
            }
        } catch (IOException e) {
            // Процесс остановлен — вывод закрыт
        }
    }

    private static boolean isNonEmpty(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
