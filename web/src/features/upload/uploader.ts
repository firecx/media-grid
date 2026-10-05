import { ApiError } from '@/api/http';

/** Как загрузчик обращается к серверу; в тестах подменяется. */
export interface UploadTransport {
  /** Запись в каталоге; её номер — номер файла в хранилище. */
  createRecord(file: File, contentType: string, options: UploadOptions): Promise<{ id: string }>;
  /** Часть файла [start, end) из total; onProgress — сколько байт части уже отправлено. */
  putChunk(mediaId: string, chunk: Blob, start: number, total: number, onProgress: (sent: number) => void,
    signal: AbortSignal): Promise<{ receivedBytes: number; complete: boolean }>;
  /** Сколько байт сервер уже получил. */
  receivedBytes(mediaId: string, signal: AbortSignal): Promise<number>;
  /** Удалить запись вместе с полученной частью файла (отмена загрузки). */
  discard(mediaId: string): Promise<void>;
}

export interface UploadOptions {
  visibility: 'PRIVATE' | 'PUBLIC';
  categoryId: string | null;
  tags: string[];
}

export type UploadPhase = 'queued' | 'creating' | 'uploading' | 'retrying' | 'done' | 'error' | 'cancelled';

export interface UploadItem {
  key: string;
  name: string;
  size: number;
  contentType: string;
  mediaId: string | null;
  phase: UploadPhase;
  /** Сколько байт уже на сервере (с учётом отправляемой части). */
  sentBytes: number;
  error: string | null;
  /** Номер повтора после сбоя связи (0 — сбоев не было). */
  attempt: number;
}

export interface UploaderSettings {
  chunkSize: number;
  concurrency: number;
  maxAttempts: number;
  /** Первая пауза перед повтором; дальше удваивается. */
  retryDelayMs: number;
}

export const defaultSettings: UploaderSettings = {
  chunkSize: 8 * 1024 * 1024,
  concurrency: 2,
  maxAttempts: 8,
  retryDelayMs: 1000,
};

interface Task {
  item: UploadItem;
  file: File;
  options: UploadOptions;
  controller: AbortController | null;
}

/**
 * Очередь загрузок. Файл передаётся частями; после обрыва загрузчик спрашивает у сервера, сколько
 * уже получено, и продолжает с этого места (ТЗ, п. 4.1.4). Живёт вне React: загрузка не прерывается,
 * если пользователь уходит на другую страницу приложения.
 */
export class Uploader {
  private readonly tasks: Task[] = [];
  private readonly listeners = new Set<() => void>();
  private snapshot: UploadItem[] = [];
  private active = 0;
  private counter = 0;

  constructor(
    private readonly transport: UploadTransport,
    private readonly settings: UploaderSettings = defaultSettings,
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {}

  add(files: File[], options: UploadOptions): void {
    for (const file of files) {
      const contentType = guessContentType(file);
      const item: UploadItem = {
        key: `upload-${++this.counter}`,
        name: file.name,
        size: file.size,
        contentType,
        mediaId: null,
        phase: 'queued',
        sentBytes: 0,
        error: null,
        attempt: 0,
      };
      const problem = file.size === 0
        ? 'Пустой файл'
        : contentType ? null : 'Поддерживаются только изображения, видео и звук';
      if (problem) {
        item.phase = 'error';
        item.error = problem;
      }
      this.tasks.push({ item, file, options, controller: null });
    }
    this.changed();
    this.pump();
  }

  cancel(key: string): void {
    const task = this.find(key);
    if (!task || task.item.phase === 'done') {
      return;
    }
    task.controller?.abort();
    this.patch(task, { phase: 'cancelled', error: null });
    // Недокачанный файл не нужен: запись и полученная часть удаляются сразу, не дожидаясь очистки через сутки
    if (task.item.mediaId) {
      void this.transport.discard(task.item.mediaId).catch(() => undefined);
    }
  }

  /** Повторить после ошибки: загрузка продолжится с того места, где остановилась. */
  retry(key: string): void {
    const task = this.find(key);
    if (task && task.item.phase === 'error' && task.item.contentType && task.file.size > 0) {
      this.patch(task, { phase: 'queued', error: null, attempt: 0 });
      this.pump();
    }
  }

  /** Убрать из списка завершённые, отменённые и неудачные. */
  clearFinished(): void {
    for (let i = this.tasks.length - 1; i >= 0; i--) {
      const phase = this.tasks[i]!.item.phase;
      if (phase === 'done' || phase === 'cancelled' || phase === 'error') {
        this.tasks.splice(i, 1);
      }
    }
    this.changed();
  }

  hasActive(): boolean {
    return this.tasks.some((t) => ['queued', 'creating', 'uploading', 'retrying'].includes(t.item.phase));
  }

  items = (): UploadItem[] => this.snapshot;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private pump(): void {
    while (this.active < this.settings.concurrency) {
      const next = this.tasks.find((t) => t.item.phase === 'queued');
      if (!next) {
        return;
      }
      this.active++;
      next.controller = new AbortController();
      this.patch(next, { phase: next.item.mediaId ? 'uploading' : 'creating' });
      void this.run(next, next.controller.signal).finally(() => {
        this.active--;
        this.pump();
      });
    }
  }

  private async run(task: Task, signal: AbortSignal): Promise<void> {
    try {
      if (!task.item.mediaId) {
        const record = await this.transport.createRecord(task.file, task.item.contentType, task.options);
        this.patch(task, { mediaId: record.id, phase: 'uploading' });
      } else {
        // Продолжение после ошибки: сервер знает, сколько уже получил
        this.patch(task, { sentBytes: await this.transport.receivedBytes(task.item.mediaId, signal) });
      }
      await this.send(task, signal);
      this.patch(task, { phase: 'done', sentBytes: task.file.size, error: null });
    } catch (error) {
      if (signal.aborted) {
        return; // отменено пользователем — состояние уже выставлено
      }
      this.patch(task, { phase: 'error', error: describe(error) });
    }
  }

  private async send(task: Task, signal: AbortSignal): Promise<void> {
    const mediaId = task.item.mediaId!;
    const size = task.file.size;
    let offset = task.item.sentBytes;
    while (offset < size) {
      const end = Math.min(offset + this.settings.chunkSize, size);
      const start = offset;
      try {
        const state = await this.transport.putChunk(mediaId, task.file.slice(start, end), start, size,
          (sent) => this.patch(task, { sentBytes: start + sent }), signal);
        offset = state.receivedBytes;
        this.patch(task, { sentBytes: offset, phase: 'uploading', attempt: 0 });
        if (state.complete) {
          return;
        }
      } catch (error) {
        if (signal.aborted) {
          throw error;
        }
        if (error instanceof ApiError && error.code === 'ALREADY_UPLOADED') {
          return;
        }
        if (!isRecoverable(error)) {
          throw error;
        }
        const attempt = task.item.attempt + 1;
        if (attempt > this.settings.maxAttempts) {
          throw error;
        }
        this.patch(task, { phase: 'retrying', attempt, error: describe(error) });
        await this.sleep(this.settings.retryDelayMs * 2 ** (attempt - 1));
        if (signal.aborted) {
          throw error;
        }
        // Где остановились на самом деле: часть могла дойти, а ответ — потеряться
        try {
          offset = await this.transport.receivedBytes(mediaId, signal);
          this.patch(task, { sentBytes: offset });
        } catch {
          // сервер всё ещё недоступен — следующая попытка покажет
        }
      }
    }
  }

  private find(key: string): Task | undefined {
    return this.tasks.find((t) => t.item.key === key);
  }

  private patch(task: Task, changes: Partial<UploadItem>): void {
    task.item = { ...task.item, ...changes };
    this.changed();
  }

  private changed(): void {
    this.snapshot = this.tasks.map((t) => t.item);
    this.listeners.forEach((listener) => listener());
  }
}

/** Сбой связи, 5xx, сервер занят или сдвиг разошёлся — можно продолжить; остальное — ошибка запроса. */
export function isRecoverable(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return true;
  }
  return error.status === 0 || error.status >= 500 || error.status === 408 || error.status === 429
    || error.code === 'UPLOAD_OFFSET_MISMATCH' || error.code === 'UPLOAD_IN_PROGRESS';
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'Неизвестная ошибка';
}

/** Браузер не всегда знает тип (например, .mkv или .flac в Windows) — тогда по расширению. */
const typesByExtension: Record<string, string> = {
  mkv: 'video/x-matroska',
  webm: 'video/webm',
  mov: 'video/quicktime',
  avi: 'video/x-msvideo',
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  mpg: 'video/mpeg',
  mpeg: 'video/mpeg',
  ts: 'video/mp2t',
  flac: 'audio/flac',
  ogg: 'audio/ogg',
  oga: 'audio/ogg',
  opus: 'audio/opus',
  m4a: 'audio/mp4',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  aac: 'audio/aac',
  wma: 'audio/x-ms-wma',
  heic: 'image/heic',
  heif: 'image/heif',
  webp: 'image/webp',
  avif: 'image/avif',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  tif: 'image/tiff',
  tiff: 'image/tiff',
};

export function guessContentType(file: File): string {
  if (/^(image|video|audio)\//.test(file.type)) {
    return file.type;
  }
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
  return typesByExtension[extension] ?? '';
}

/** «Отпуск 2024.mov» → «Отпуск 2024». */
export function titleFromFilename(name: string): string {
  const dot = name.lastIndexOf('.');
  return (dot > 0 ? name.slice(0, dot) : name).trim() || name;
}
