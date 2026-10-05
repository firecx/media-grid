import { IconMusic } from '@tabler/icons-react';
import type { Media } from '@/api/types';
import { Alert, Loader } from '@/ui';
import { useFileLink } from './queries';
import styles from './MediaPage.module.css';

/**
 * Просмотр файла. Видео и звук — версия для воспроизведения (если обработка её не делала, сервер
 * выдаёт исходный файл); перемотка работает за счёт частичной загрузки (Range). Изображение —
 * превью, пока исходник не нужен целиком.
 */
export function Viewer({ media }: { media: Media }) {
  const uploaded = media.status !== 'PENDING_UPLOAD';
  const kind = media.mediaKind;
  const preview = useFileLink(media.id, 'preview', uploaded && media.hasPreview);
  const original = useFileLink(media.id, 'original', uploaded && kind === 'IMAGE' && !media.hasPreview);
  const playback = useFileLink(media.id, 'playback', uploaded && kind !== 'IMAGE');

  if (!uploaded) {
    return <Alert tone="warning" title="Файл ещё не загружен">Запись создана, но файл в хранилище не передан.</Alert>;
  }
  if (kind === 'IMAGE') {
    const link = media.hasPreview ? preview : original;
    if (link.isError) {
      return <Alert tone="danger">Не удалось получить изображение</Alert>;
    }
    return (
      <div className={styles.stage}>
        {link.data ? <img src={link.data.url} alt={media.title} className={styles.image} /> : <Loader />}
      </div>
    );
  }
  if (playback.isError) {
    return <Alert tone="danger">Не удалось получить файл для воспроизведения</Alert>;
  }
  if (!playback.data) {
    return <div className={styles.stage}><Loader /></div>;
  }
  if (kind === 'VIDEO') {
    return (
      <div className={styles.stage}>
        <video key={playback.data.url} src={playback.data.url} poster={preview.data?.url} controls preload="metadata"
          playsInline className={styles.video} />
      </div>
    );
  }
  return (
    <div className={styles.audio}>
      <div className={styles.cover}>
        {preview.data ? <img src={preview.data.url} alt="" /> : <IconMusic size={72} stroke={1.2} aria-hidden />}
      </div>
      <audio key={playback.data.url} src={playback.data.url} controls preload="metadata" className={styles.player} />
    </div>
  );
}
