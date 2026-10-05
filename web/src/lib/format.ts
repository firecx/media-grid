import type { JobStage, MediaKind, MediaStatus, Tone } from './types';

const sizeUnits = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];

/** 1536 → «1,5 КБ». */
export function formatBytes(bytes: number): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < sizeUnits.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit === 0 || value >= 100 ? 0 : 1;
  return `${value.toLocaleString('ru-RU', { maximumFractionDigits: digits })} ${sizeUnits[unit]}`;
}

/** Дата и время по-русски в часовом поясе браузера. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  return new Date(iso).toLocaleString('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });
}

/** 754000 → «12:34», 3723000 → «1:02:03». */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) {
    return '—';
  }
  const total = Math.round(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const mm = hours > 0 ? String(minutes).padStart(2, '0') : String(minutes);
  return `${hours > 0 ? `${hours}:` : ''}${mm}:${String(seconds).padStart(2, '0')}`;
}

export const kindLabels: Record<MediaKind, string> = {
  IMAGE: 'Изображение',
  VIDEO: 'Видео',
  AUDIO: 'Звук',
};

export const statusLabels: Record<MediaStatus, { label: string; tone: Tone }> = {
  PENDING_UPLOAD: { label: 'Ждёт загрузки', tone: 'warning' },
  UPLOADED: { label: 'Обрабатывается', tone: 'info' },
  READY: { label: 'Готово', tone: 'success' },
  FAILED: { label: 'Ошибка обработки', tone: 'danger' },
};

export const stageLabels: Record<JobStage, string> = {
  DOWNLOADING: 'Получение файла',
  ANALYZING: 'Разбор файла',
  PREVIEW: 'Превью',
  TRANSCODING: 'Перекодирование',
  SAVING: 'Сохранение',
};
