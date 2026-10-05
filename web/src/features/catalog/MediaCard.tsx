import { IconMusic, IconPhoto, IconVideo } from '@tabler/icons-react';
import { Link } from 'react-router';
import type { Media } from '@/api/types';
import { formatBytes, formatDate, kindLabels, statusLabels } from '@/lib/format';
import { useFileLink } from '@/features/media/queries';
import { Badge, Row, Text } from '@/ui';
import styles from './MediaCard.module.css';

const kindIcons = { IMAGE: IconPhoto, VIDEO: IconVideo, AUDIO: IconMusic };

/** Карточка файла в каталоге: миниатюра (если обработка её сделала), название, вид, состояние. */
export function MediaCard({ media }: { media: Media }) {
  const thumbnail = useFileLink(media.id, 'thumbnail', media.hasPreview);
  const KindIcon = kindIcons[media.mediaKind];
  const status = statusLabels[media.status];

  return (
    <Link to={`/media/${media.id}`} className={styles.card}>
      <div className={styles.thumb}>
        {thumbnail.data
          ? <img src={thumbnail.data.url} alt="" loading="lazy" />
          : <KindIcon size={48} stroke={1.2} aria-hidden />}
      </div>
      <div className={styles.body}>
        <Text as="h3" style={{ fontWeight: 600, overflowWrap: 'anywhere' }}>{media.title}</Text>
        <Row gap="xs">
          <Badge>{kindLabels[media.mediaKind]}</Badge>
          {media.status !== 'READY' && <Badge tone={status.tone}>{status.label}</Badge>}
          {media.visibility === 'PUBLIC' && <Badge tone="accent3">Общий</Badge>}
        </Row>
        <Text muted small>{formatBytes(media.sizeBytes)} · {formatDate(media.createdAt)}</Text>
      </div>
    </Link>
  );
}
