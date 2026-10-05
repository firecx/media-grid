import { stageLabels } from '@/lib/format';
import { Badge, Progress, Row, Stack, Text } from '@/ui';
import { useProcessingJob } from './queries';

/** Ход обработки файла (ТЗ, п. 4.1.6). */
export function ProcessingStatus({ mediaId, compact }: { mediaId: string; compact?: boolean }) {
  const job = useProcessingJob(mediaId);

  if (job.isError) {
    return <Text muted small>Ход обработки сейчас недоступен</Text>;
  }
  const data = job.data;
  if (!data || data.status === 'QUEUED') {
    return (
      <Stack gap="xs">
        <Row gap="xs"><Badge tone="info">В очереди на обработку</Badge>
          {data?.attempts ? <Text muted small>повтор после сбоя, попытка {data.attempts + 1}</Text> : null}
        </Row>
        {!compact && data?.error && <Text muted small>Последний сбой: {data.error}</Text>}
      </Stack>
    );
  }
  if (data.status === 'DONE') {
    return <Badge tone="success">Обработано</Badge>;
  }
  if (data.status === 'FAILED') {
    return (
      <Stack gap="xs">
        <Badge tone="danger">Обработать не удалось</Badge>
        {!compact && data.error && <Text muted small>{data.error}</Text>}
      </Stack>
    );
  }
  return (
    <Stack gap="xs">
      <Text small>{data.stage ? stageLabels[data.stage] : 'Обработка'} — {data.progress}%</Text>
      <Progress value={data.progress} animated label="Ход обработки" />
    </Stack>
  );
}
