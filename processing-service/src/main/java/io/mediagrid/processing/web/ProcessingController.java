package io.mediagrid.processing.web;

import java.util.List;
import java.util.UUID;

import io.mediagrid.processing.job.JobRunner;
import io.mediagrid.processing.job.JobService;
import io.mediagrid.processing.job.JobStatus;
import io.mediagrid.support.security.CurrentUser;
import io.mediagrid.support.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/processing")
public class ProcessingController {

    static final int MAX_PAGE_SIZE = 100;

    private final JobService jobs;
    private final JobRunner runner;

    public ProcessingController(JobService jobs, JobRunner runner) {
        this.jobs = jobs;
        this.runner = runner;
    }

    /**
     * Ход обработки файла (ТЗ, п. 4.1.6): видит владелец и администратор. Чужой файл выглядит
     * как несуществующий, чтобы не раскрывать его наличие.
     */
    @GetMapping("/{mediaId}")
    public JobResponse job(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.of(jwt);
        return jobs.find(mediaId)
                .filter(job -> user.admin() || job.getOwnerId().equals(user.id()))
                .map(JobResponse::of)
                .orElseThrow(() -> ApiException.notFound("Задача обработки не найдена"));
    }

    /** Очередь для администратора: например, status=FAILED — что не удалось обработать. */
    @GetMapping("/admin/jobs")
    public PageResponse<JobResponse> list(@RequestParam(required = false) JobStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<JobResponse> result = jobs.list(status, request).map(JobResponse::of);
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    /** Обработать заново завершённую задачу (после исправления настроек или обновления ffmpeg). */
    @PostMapping("/admin/jobs/{mediaId}/retry")
    public JobResponse retry(@PathVariable UUID mediaId) {
        JobResponse response = JobResponse.of(jobs.retry(mediaId));
        runner.dispatch();
        return response;
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
