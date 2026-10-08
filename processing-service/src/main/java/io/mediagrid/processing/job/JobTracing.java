package io.mediagrid.processing.job;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Трасса задачи обработки. Событие file.uploaded приходит в трассе запроса, которым загрузили файл;
 * задача ждёт в базе и выполняется позже, в другом потоке. Чтобы трасса не обрывалась, её заголовок
 * (W3C traceparent) сохраняется в задаче, и исполнитель продолжает ту же трассу: в журналах обработки
 * тот же traceId, а вызовы службы хранения и итог обработки — её участки.
 */
@Component
public class JobTracing {

    private static final String VERSION = "00";
    private static final String SAMPLED = "01";

    private final Tracer tracer;

    public JobTracing(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    /** Текущая трасса как W3C traceparent: 00-<traceId>-<spanId>-01; null — вне трассы. */
    public String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        TraceContext context = span.context();
        return String.join("-", VERSION, context.traceId(), context.spanId(), SAMPLED);
    }

    /** Участок «обработка файла» — продолжение сохранённой трассы или, если её нет, новая трасса. */
    public Span startJobSpan(JobTicket ticket) {
        Span.Builder builder = tracer.spanBuilder().name("обработка файла").kind(Span.Kind.CONSUMER);
        TraceContext parent = parse(ticket.traceParent());
        if (parent != null) {
            builder.setParent(parent);
        }
        return builder.start()
                .tag("mediagrid.media.id", ticket.mediaId().toString())
                .tag("mediagrid.content.type", ticket.contentType())
                .tag("mediagrid.attempt", String.valueOf(ticket.attempt()));
    }

    /** Сделать участок текущим в этом потоке: журналы и вызовы других служб попадут в него. */
    public Tracer.SpanInScope inScope(Span span) {
        return tracer.withSpan(span);
    }

    private TraceContext parse(String traceParent) {
        if (traceParent == null) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return null;
        }
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled(SAMPLED.equals(parts[3]))
                .build();
    }
}
