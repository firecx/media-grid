import { ApiError } from '@/api/http';
import { guessContentType, titleFromFilename, type UploadTransport, Uploader } from './uploader';

const options = { visibility: 'PRIVATE' as const, categoryId: null, tags: [] };

/** Сервер в памяти: принимает части только с текущего места, может «обрываться». */
function fakeServer() {
  const received = new Map<string, number>();
  const failures: Array<{ afterBytes: number; error: Error; delivered: boolean }> = [];
  const discarded: string[] = [];
  const chunks: string[] = [];
  let ids = 0;
  const transport: UploadTransport = {
    async createRecord() {
      const id = `m${++ids}`;
      received.set(id, 0);
      return { id };
    },
    async putChunk(mediaId, chunk, start, total, onProgress) {
      const offset = received.get(mediaId) ?? 0;
      chunks.push(`${start}-${start + chunk.size - 1}/${total}`);
      const failure = failures[0];
      if (failure && start + chunk.size > failure.afterBytes) {
        failures.shift();
        if (failure.delivered) {
          // Часть дошла, но ответ потерялся
          received.set(mediaId, start + chunk.size);
        }
        throw failure.error;
      }
      if (start !== offset) {
        throw new ApiError(409, 'UPLOAD_OFFSET_MISMATCH', `Продолжать нужно с ${offset}`);
      }
      onProgress(chunk.size);
      const now = start + chunk.size;
      received.set(mediaId, now);
      return { receivedBytes: now, complete: now === total };
    },
    async receivedBytes(mediaId) {
      return received.get(mediaId) ?? 0;
    },
    async discard(mediaId) {
      discarded.push(mediaId);
    },
  };
  return { transport, received, failures, discarded, chunks };
}

function file(size: number, name = 'clip.mp4', type = 'video/mp4'): File {
  return new File([new Uint8Array(size)], name, { type });
}

async function settled(uploader: Uploader): Promise<void> {
  for (let i = 0; i < 200 && uploader.hasActive(); i++) {
    await new Promise((r) => setTimeout(r, 0));
  }
}

const settings = { chunkSize: 10, concurrency: 2, maxAttempts: 3, retryDelayMs: 1 };
const noSleep = async () => undefined;

describe('Uploader', () => {
  it('sends a file in parts with Content-Range and finishes', async () => {
    const server = fakeServer();
    const uploader = new Uploader(server.transport, settings, noSleep);

    uploader.add([file(25)], options);
    await settled(uploader);

    expect(server.chunks).toEqual(['0-9/25', '10-19/25', '20-24/25']);
    expect(uploader.items()[0]).toMatchObject({ phase: 'done', sentBytes: 25, mediaId: 'm1' });
  });

  it('continues from what the server received after a broken connection', async () => {
    const server = fakeServer();
    server.failures.push({ afterBytes: 15, error: new ApiError(0, 'NETWORK_ERROR', 'обрыв'), delivered: true });
    const uploader = new Uploader(server.transport, settings, noSleep);

    uploader.add([file(30)], options);
    await settled(uploader);

    // Вторая часть дошла, ответ потерялся: после уточнения сдвига она не отправляется заново
    expect(server.chunks).toEqual(['0-9/30', '10-19/30', '20-29/30']);
    expect(uploader.items()[0]).toMatchObject({ phase: 'done', sentBytes: 30, attempt: 0 });
  });

  it('gives up after the allowed number of attempts and can be resumed by hand', async () => {
    const server = fakeServer();
    for (let i = 0; i < 4; i++) {
      server.failures.push({ afterBytes: 0, error: new ApiError(503, 'SERVICE_UNAVAILABLE', 'недоступна'),
        delivered: false });
    }
    const uploader = new Uploader(server.transport, settings, noSleep);

    uploader.add([file(12)], options);
    await settled(uploader);
    expect(uploader.items()[0]).toMatchObject({ phase: 'error', error: 'недоступна' });

    uploader.retry(uploader.items()[0]!.key);
    await settled(uploader);
    expect(uploader.items()[0]).toMatchObject({ phase: 'done', sentBytes: 12 });
  });

  it('stops at once on errors that a retry cannot fix', async () => {
    const server = fakeServer();
    server.failures.push({ afterBytes: 0, error: new ApiError(403, 'FORBIDDEN', 'Нет прав'), delivered: false });
    const uploader = new Uploader(server.transport, settings, noSleep);

    uploader.add([file(5)], options);
    await settled(uploader);

    expect(uploader.items()[0]).toMatchObject({ phase: 'error', error: 'Нет прав', attempt: 0 });
  });

  it('rejects empty and unsupported files without asking the server', async () => {
    const server = fakeServer();
    const uploader = new Uploader(server.transport, settings, noSleep);

    uploader.add([file(0), file(5, 'notes.pdf', 'application/pdf')], options);

    expect(uploader.items().map((i) => i.phase)).toEqual(['error', 'error']);
    expect(server.received.size).toBe(0);
  });

  it('cancelling removes the half-uploaded record', async () => {
    const server = fakeServer();
    let release: () => void = () => undefined;
    const slow: UploadTransport = {
      ...server.transport,
      putChunk: (...args) => new Promise((resolve, reject) => {
        args[5].addEventListener('abort', () => reject(new DOMException('Отменено', 'AbortError')));
        release = () => resolve(server.transport.putChunk(...args));
      }),
    };
    const uploader = new Uploader(slow, settings, noSleep);

    uploader.add([file(25)], options);
    await new Promise((r) => setTimeout(r, 0));
    uploader.cancel(uploader.items()[0]!.key);
    release();
    await settled(uploader);

    expect(uploader.items()[0]!.phase).toBe('cancelled');
    expect(server.discarded).toEqual(['m1']);
  });
});

describe('file names and types', () => {
  it('guesses the type by extension when the browser does not know it', () => {
    expect(guessContentType(file(1, 'film.mkv', ''))).toBe('video/x-matroska');
    expect(guessContentType(file(1, 'song.FLAC', ''))).toBe('audio/flac');
    expect(guessContentType(file(1, 'photo.jpg', 'image/jpeg'))).toBe('image/jpeg');
    expect(guessContentType(file(1, 'notes.txt', 'text/plain'))).toBe('');
  });

  it('makes a title from the file name', () => {
    expect(titleFromFilename('Отпуск 2024.mov')).toBe('Отпуск 2024');
    expect(titleFromFilename('.hidden')).toBe('.hidden');
    expect(titleFromFilename('README')).toBe('README');
  });
});
