import { IconRefresh } from '@tabler/icons-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { processingApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { JobStatus, ProcessingJob } from '@/api/types';
import { formatDate, stageLabels } from '@/lib/format';
import {
  Alert, Badge, type Column, IconButton, Loader, notify, Page, Pagination, Row, SelectField, Stack, Table, Text,
  type Tone,
} from '@/ui';
import { AdminNav } from './AdminNav';

const PAGE_SIZE = 20;
const statuses: Record<JobStatus, { label: string; tone: Tone }> = {
  QUEUED: { label: 'В очереди', tone: 'neutral' },
  RUNNING: { label: 'Выполняется', tone: 'info' },
  DONE: { label: 'Готово', tone: 'success' },
  FAILED: { label: 'Ошибка', tone: 'danger' },
};

/** Очередь обработки: что не удалось обработать и повтор после исправления причины. */
export function ProcessingQueuePage() {
  const [status, setStatus] = useState<JobStatus | null>('FAILED');
  const [page, setPage] = useState(0);
  const queries = useQueryClient();
  const jobs = useQuery({
    queryKey: ['processing-jobs', status, page],
    queryFn: () => processingApi.jobs(status, page, PAGE_SIZE),
    placeholderData: keepPreviousData,
    refetchInterval: 5000,
  });
  const retry = useMutation({
    mutationFn: (id: string) => processingApi.retry(id),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['processing-jobs'] });
      notify('Задача поставлена в очередь');
    },
    onError: (error) => notify(errorMessage(error), 'danger'),
  });

  const columns: Column<ProcessingJob>[] = [
    { key: 'file', title: 'Файл', render: (j) => <Link to={`/media/${j.mediaId}`}>{j.mediaId.slice(0, 8)}…</Link> },
    { key: 'status', title: 'Состояние', render: (j) => <Badge tone={statuses[j.status].tone}>{statuses[j.status].label}</Badge> },
    {
      key: 'stage',
      title: 'Ход',
      render: (j) => (j.status === 'RUNNING' && j.stage ? `${stageLabels[j.stage]}, ${j.progress}%` : '—'),
    },
    { key: 'attempts', title: 'Попыток', render: (j) => j.attempts },
    {
      key: 'error',
      title: 'Причина',
      render: (j) => (j.error ? <Text small style={{ maxWidth: 420, overflowWrap: 'anywhere' }}>{j.error}</Text> : '—'),
    },
    { key: 'created', title: 'Поставлена', render: (j) => formatDate(j.createdAt) },
    {
      key: 'actions',
      title: '',
      width: 60,
      render: (j) => (j.status === 'DONE' || j.status === 'FAILED'
        ? <IconButton label="Обработать заново" onClick={() => retry.mutate(j.mediaId)}><IconRefresh size={18} /></IconButton>
        : null),
    },
  ];

  return (
    <Page>
      <Stack gap="lg">
        <AdminNav title="Администрирование" />
        <Row>
          <SelectField label="Состояние" placeholder="Все" clearable value={status}
            onChange={(v) => { setStatus(v as JobStatus | null); setPage(0); }}
            options={Object.entries(statuses).map(([value, { label }]) => ({ value, label }))} />
        </Row>
        {jobs.isPending && <Loader />}
        {jobs.isError && <Alert tone="danger">{errorMessage(jobs.error)}</Alert>}
        {jobs.data && (
          <>
            <Table columns={columns} rows={jobs.data.items} rowKey={(j) => j.mediaId} empty="Задач нет" />
            <Row justify="center">
              <Pagination page={page + 1} total={Math.ceil(jobs.data.total / PAGE_SIZE)} onChange={(p) => setPage(p - 1)} />
            </Row>
          </>
        )}
      </Stack>
    </Page>
  );
}
