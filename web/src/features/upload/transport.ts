import { mediaApi } from '@/api/endpoints';
import { ApiError, currentToken, refreshToken } from '@/api/http';
import type { UploadState } from '@/api/types';
import { titleFromFilename, type UploadOptions, type UploadTransport, Uploader } from './uploader';

/**
 * Передача через XMLHttpRequest: в отличие от fetch, он сообщает ход отправки тела. Истёкший токен
 * (401) один раз обновляется, и часть отправляется заново.
 */
export const httpTransport: UploadTransport = {
  createRecord: (file: File, contentType: string, options: UploadOptions) =>
    mediaApi.create({
      title: titleFromFilename(file.name),
      originalFilename: file.name,
      contentType,
      sizeBytes: file.size,
      categoryId: options.categoryId,
      tags: options.tags,
      visibility: options.visibility,
    }),

  async putChunk(mediaId, chunk, start, total, onProgress, signal) {
    const contentRange = `bytes ${start}-${start + chunk.size - 1}/${total}`;
    try {
      return await put(mediaId, chunk, contentRange, onProgress, signal);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && (await refreshToken())) {
        return put(mediaId, chunk, contentRange, onProgress, signal);
      }
      throw error;
    }
  },

  async receivedBytes(mediaId, signal) {
    let response = await head(mediaId, signal);
    if (response.status === 401 && (await refreshToken())) {
      response = await head(mediaId, signal);
    }
    if (!response.ok) {
      throw new ApiError(response.status, `HTTP_${response.status}`, 'Не удалось узнать, сколько уже загружено');
    }
    return Number(response.headers.get('Upload-Offset') ?? 0);
  },

  discard: (mediaId) => mediaApi.remove(mediaId),
};

function put(mediaId: string, chunk: Blob, contentRange: string, onProgress: (sent: number) => void,
  signal: AbortSignal): Promise<UploadState> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', `/api/files/${mediaId}`);
    const token = currentToken();
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }
    xhr.setRequestHeader('Content-Range', contentRange);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (event) => onProgress(event.loaded);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText) as UploadState);
        return;
      }
      let code = `HTTP_${xhr.status}`;
      let message = xhr.status >= 500 ? 'Служба временно недоступна' : `Ошибка ${xhr.status}`;
      let traceId: string | null = null;
      try {
        const body = JSON.parse(xhr.responseText) as { code?: string; message?: string; traceId?: string | null };
        code = body.code ?? code;
        message = body.message ?? message;
        traceId = body.traceId ?? null;
      } catch {
        // тело не JSON (например, ответ nginx)
      }
      reject(new ApiError(xhr.status, code, message, traceId));
    };
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK_ERROR', 'Связь прервалась'));
    xhr.onabort = () => reject(new DOMException('Отменено', 'AbortError'));
    signal.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(chunk);
  });
}

function head(mediaId: string, signal: AbortSignal): Promise<Response> {
  const token = currentToken();
  return fetch(`/api/files/${mediaId}`, {
    method: 'HEAD',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
  }).catch(() => {
    throw new ApiError(0, 'NETWORK_ERROR', 'Нет связи с сервером');
  });
}

/** Одна очередь на всё приложение: загрузки идут, пока открыта вкладка. */
export const uploader = new Uploader(httpTransport);

// Закрыть вкладку посреди загрузки — потерять её (продолжить можно будет, загрузив файл заново)
window.addEventListener('beforeunload', (event) => {
  if (uploader.hasActive()) {
    event.preventDefault();
  }
});
