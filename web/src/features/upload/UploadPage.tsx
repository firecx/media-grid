import { IconCloudUpload, IconPlayerStop, IconRefresh } from '@tabler/icons-react';
import { useState, useSyncExternalStore } from 'react';
import { Link } from 'react-router';
import { useCategories } from '@/features/media/queries';
import { ProcessingStatus } from '@/features/media/ProcessingStatus';
import { formatBytes } from '@/lib/format';
import {
  Badge, Button, Card, FileDrop, Grid, IconButton, Page, Progress, Row, SelectField, Stack, SwitchField, TagsField,
  Text, Title, type Tone,
} from '@/ui';
import { uploader } from './transport';
import type { UploadItem, UploadPhase } from './uploader';

const phaseLabels: Record<UploadPhase, { label: string; tone: Tone }> = {
  queued: { label: 'В очереди', tone: 'neutral' },
  creating: { label: 'Подготовка', tone: 'info' },
  uploading: { label: 'Загружается', tone: 'info' },
  retrying: { label: 'Связь прервалась, повтор', tone: 'warning' },
  done: { label: 'Загружен', tone: 'success' },
  error: { label: 'Ошибка', tone: 'danger' },
  cancelled: { label: 'Отменён', tone: 'neutral' },
};

/** Загрузка файлов: несколько сразу, частями, с продолжением после обрыва связи. */
export function UploadPage() {
  const items = useSyncExternalStore(uploader.subscribe, uploader.items);
  const categories = useCategories();
  const [publicAccess, setPublicAccess] = useState(false);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [tags, setTags] = useState<string[]>([]);

  const add = (files: File[]) =>
    uploader.add(files, { visibility: publicAccess ? 'PUBLIC' : 'PRIVATE', categoryId, tags });
  const finished = items.some((i) => i.phase === 'done' || i.phase === 'cancelled' || i.phase === 'error');

  return (
    <Page>
      <Stack gap="lg">
        <Title level={1}>Загрузка</Title>
        <Card>
          <Stack gap="sm">
            <Text muted small>Применяется к файлам, которые добавите дальше. Название берётся из имени файла,
              его и остальное можно изменить потом на странице файла.</Text>
            <Grid minWidth={240} gap="sm">
              <SelectField label="Категория" placeholder="Без категории" clearable value={categoryId}
                onChange={setCategoryId}
                options={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
              <TagsField label="Теги" placeholder="Enter или запятая" value={tags} maxTags={20}
                onChange={(t) => setTags(t.map((tag) => tag.toLowerCase()))} />
            </Grid>
            <SwitchField label="Виден всем вошедшим пользователям" value={publicAccess} onChange={setPublicAccess} />
          </Stack>
        </Card>

        <FileDrop onFiles={add}>
          <Stack gap="xs" style={{ alignItems: 'center', padding: 'var(--mg-space-xl) 0', textAlign: 'center' }}>
            <IconCloudUpload size={48} stroke={1.3} aria-hidden />
            <Text>Перетащите файлы сюда или нажмите, чтобы выбрать</Text>
            <Text muted small>Изображения, видео и звук; большие файлы передаются частями и продолжаются после
              обрыва связи. Не закрывайте вкладку, пока идёт загрузка.</Text>
          </Stack>
        </FileDrop>

        {items.length > 0 && (
          <Stack gap="sm">
            <Row justify="space-between">
              <Title level={2}>Файлы</Title>
              {finished && <Button kind="subtle" onClick={() => uploader.clearFinished()}>Убрать завершённые</Button>}
            </Row>
            {items.map((item) => <UploadRow key={item.key} item={item} />)}
          </Stack>
        )}
      </Stack>
    </Page>
  );
}

function UploadRow({ item }: { item: UploadItem }) {
  const phase = phaseLabels[item.phase];
  const percent = item.size > 0 ? Math.floor((item.sentBytes / item.size) * 100) : 0;
  const active = ['queued', 'creating', 'uploading', 'retrying'].includes(item.phase);

  return (
    <Card>
      <Stack gap="sm">
        <Row justify="space-between" gap="sm">
          <Stack gap="xs" style={{ minWidth: 0, flex: 1 }}>
            <Text style={{ fontWeight: 600, overflowWrap: 'anywhere' }}>
              {item.phase === 'done' && item.mediaId ? <Link to={`/media/${item.mediaId}`}>{item.name}</Link> : item.name}
            </Text>
            <Row gap="xs">
              <Badge tone={phase.tone}>{phase.label}</Badge>
              <Text muted small>{formatBytes(item.sentBytes)} из {formatBytes(item.size)}</Text>
              {item.phase === 'retrying' && <Text muted small>попытка {item.attempt}</Text>}
            </Row>
          </Stack>
          <Row gap="xs">
            {item.phase === 'error' && item.contentType && (
              <IconButton label="Продолжить" onClick={() => uploader.retry(item.key)}><IconRefresh size={18} /></IconButton>
            )}
            {active && (
              <IconButton label="Отменить" kind="danger" onClick={() => uploader.cancel(item.key)}>
                <IconPlayerStop size={18} />
              </IconButton>
            )}
          </Row>
        </Row>
        {active && <Progress value={percent} animated={item.phase !== 'queued'} label={`Загрузка ${item.name}`} />}
        {item.error && item.phase !== 'cancelled' && <Text muted small>{item.error}</Text>}
        {item.phase === 'done' && item.mediaId && <ProcessingStatus mediaId={item.mediaId} compact />}
      </Stack>
    </Card>
  );
}
