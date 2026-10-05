import { IconDownload, IconEdit, IconRefresh, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { filesApi, mediaApi, processingApi } from '@/api/endpoints';
import { ApiError, errorMessage } from '@/api/http';
import type { Media } from '@/api/types';
import { useSession } from '@/auth/session';
import { formatBytes, formatDate, formatDuration, kindLabels, statusLabels } from '@/lib/format';
import { Alert, Badge, Button, Card, confirm, Loader, notify, Page, Row, Stack, Text, Title } from '@/ui';
import { EditMediaModal } from './EditMediaModal';
import styles from './MediaPage.module.css';
import { ProcessingStatus } from './ProcessingStatus';
import { useProcessingJob } from './queries';
import { Viewer } from './Viewer';

export function MediaPage() {
  const { id = '' } = useParams();
  const media = useQuery({
    queryKey: ['media-item', id],
    queryFn: () => mediaApi.get(id),
    // Пока файл обрабатывается, статус записи меняется — поглядываем
    refetchInterval: (query) => (query.state.data?.status === 'UPLOADED' ? 5000 : false),
  });

  if (media.isPending) {
    return <Page><Loader /></Page>;
  }
  if (media.isError) {
    const missing = media.error instanceof ApiError && media.error.status === 404;
    return (
      <Page>
        <Stack>
          <Alert tone={missing ? 'warning' : 'danger'} title={missing ? 'Файл не найден' : 'Не удалось открыть файл'}>
            {missing ? 'Файла нет или он вам недоступен.' : errorMessage(media.error)}
          </Alert>
          <Link to="/">Вернуться в каталог</Link>
        </Stack>
      </Page>
    );
  }
  return <MediaDetails media={media.data} />;
}

function MediaDetails({ media }: { media: Media }) {
  const { user, isAdmin } = useSession();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [editing, setEditing] = useState(false);
  const owner = user?.id === media.ownerId;
  const canChange = owner || isAdmin;
  const uploaded = media.status !== 'PENDING_UPLOAD';
  const job = useProcessingJob(media.id, uploaded && canChange);
  const status = statusLabels[media.status];

  const remove = useMutation({
    mutationFn: () => mediaApi.remove(media.id),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['media'] });
      notify('Файл удалён');
      navigate('/');
    },
    onError: (error) => notify(errorMessage(error), 'danger', 'Не удалось удалить'),
  });

  const retry = useMutation({
    mutationFn: () => processingApi.retry(media.id),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['processing', media.id] });
      void queries.invalidateQueries({ queryKey: ['media-item', media.id] });
      notify('Файл поставлен на повторную обработку');
    },
    onError: (error) => notify(errorMessage(error), 'danger'),
  });

  async function download() {
    try {
      const link = await filesApi.link(media.id, 'original', true);
      window.location.assign(link.url);
    } catch (error) {
      notify(errorMessage(error), 'danger', 'Не удалось скачать');
    }
  }

  async function askRemove() {
    if (await confirm({ title: 'Удалить файл?', message: `«${media.title}» будет удалён без возможности восстановления.`,
      confirmLabel: 'Удалить' })) {
      remove.mutate();
    }
  }

  const facts: Array<[string, string]> = [
    ['Вид', kindLabels[media.mediaKind]],
    ['Файл', media.originalFilename],
    ['Тип', media.contentType],
    ['Размер', formatBytes(media.sizeBytes)],
    ['Добавлен', formatDate(media.createdAt)],
    ['Загружен', formatDate(media.uploadedAt)],
    ['Категория', media.category?.name ?? '—'],
    ['Доступ', media.visibility === 'PUBLIC' ? 'все вошедшие' : 'только владелец'],
  ];
  const meta = job.data;
  if (meta?.durationMs != null) {
    facts.push(['Длительность', formatDuration(meta.durationMs)]);
  }
  if (meta?.width != null && meta.height != null) {
    facts.push(['Кадр', `${meta.width} × ${meta.height}`]);
  }
  if (meta?.videoCodec || meta?.audioCodec) {
    facts.push(['Кодеки', [meta.videoCodec, meta.audioCodec].filter(Boolean).join(', ')]);
  }

  return (
    <Page>
      <Stack gap="lg">
        <Stack gap="xs">
          <Link to="/">← Каталог</Link>
          <Title level={1}>{media.title}</Title>
          <Row gap="xs">
            <Badge tone={status.tone}>{status.label}</Badge>
            {media.tags.map((tag) => <Link key={tag} to={`/?tag=${encodeURIComponent(tag)}`}><Badge tone="accent2">#{tag}</Badge></Link>)}
          </Row>
        </Stack>

        <div className={styles.layout}>
          <Stack>
            <Viewer media={media} />
            {media.status === 'FAILED' && media.processingError && (
              <Alert tone="warning" title="Обработка не удалась">
                Исходный файл доступен для скачивания и просмотра. Причина: {media.processingError}
              </Alert>
            )}
          </Stack>

          <Stack>
            <Card>
              <Stack gap="sm">
                <Row gap="xs">
                  {uploaded && <Button kind="primary" icon={<IconDownload size={16} />} onClick={() => void download()}>Скачать</Button>}
                  {canChange && <Button icon={<IconEdit size={16} />} onClick={() => setEditing(true)}>Изменить</Button>}
                  {canChange && (
                    <Button kind="danger" icon={<IconTrash size={16} />} loading={remove.isPending}
                      onClick={() => void askRemove()}>Удалить</Button>
                  )}
                </Row>
                <dl className={styles.facts}>
                  {facts.map(([label, value]) => (
                    <div key={label} style={{ display: 'contents' }}><dt>{label}</dt><dd>{value}</dd></div>
                  ))}
                </dl>
              </Stack>
            </Card>

            {uploaded && canChange && (
              <Card>
                <Stack gap="sm">
                  <Text style={{ fontWeight: 600 }}>Обработка</Text>
                  <ProcessingStatus mediaId={media.id} />
                  {isAdmin && (meta?.status === 'FAILED' || meta?.status === 'DONE') && (
                    <Button icon={<IconRefresh size={16} />} loading={retry.isPending} onClick={() => retry.mutate()}>
                      Обработать заново
                    </Button>
                  )}
                </Stack>
              </Card>
            )}
          </Stack>
        </div>
      </Stack>
      {editing && <EditMediaModal media={media} opened={editing} onClose={() => setEditing(false)} />}
    </Page>
  );
}
