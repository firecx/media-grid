import { useQuery } from '@tanstack/react-query';
import { filesApi, mediaApi, processingApi } from '@/api/endpoints';
import { ApiError } from '@/api/http';
import type { ProcessingJob, Variant } from '@/api/types';

/** Ссылка действует 4 часа (mediagrid.storage.link-ttl); берём новую заранее, через 3. */
const LINK_FRESH_MS = 3 * 60 * 60 * 1000;

/** Подписанная ссылка на файл или его производное; кэшируется, чтобы не просить её на каждую отрисовку. */
export function useFileLink(mediaId: string, variant: Variant, enabled = true) {
  return useQuery({
    queryKey: ['file-link', mediaId, variant],
    queryFn: () => filesApi.link(mediaId, variant),
    enabled,
    staleTime: LINK_FRESH_MS,
    gcTime: LINK_FRESH_MS,
    retry: false,
  });
}

const POLL_MS = 2000;

/**
 * Ход обработки; пока она идёт, опрашивается раз в 2 секунды. Сразу после загрузки задачи может
 * ещё не быть (событие в пути) — тогда null, это показывается как очередь.
 */
export function useProcessingJob(mediaId: string, enabled = true) {
  return useQuery({
    queryKey: ['processing', mediaId],
    queryFn: async (): Promise<ProcessingJob | null> => {
      try {
        return await processingApi.job(mediaId);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    enabled,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'DONE' || status === 'FAILED' || query.state.error ? false : POLL_MS;
    },
  });
}

export function useCategories() {
  return useQuery({ queryKey: ['categories'], queryFn: mediaApi.categories, staleTime: 5 * 60 * 1000 });
}
