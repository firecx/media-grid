import { IconUpload } from '@tabler/icons-react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { type MediaFilter, mediaApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { MediaKind, MediaStatus } from '@/api/types';
import { useCategories } from '@/features/media/queries';
import { kindLabels, statusLabels } from '@/lib/format';
import {
  Alert, Button, CheckboxField, Grid, Loader, Page, Pagination, Row, SelectField, Stack, TagsField, Text,
  TextField, Title,
} from '@/ui';
import { MediaCard } from './MediaCard';

const PAGE_SIZE = 24;

const sortOptions = [
  { value: 'createdAt:desc', label: 'Сначала новые' },
  { value: 'createdAt:asc', label: 'Сначала старые' },
  { value: 'title:asc', label: 'По названию' },
  { value: 'size:desc', label: 'Сначала большие' },
];

/** Каталог с поиском и фильтрами. Условия хранятся в адресе: им можно поделиться, «назад» их возвращает. */
export function CatalogPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const categories = useCategories();

  const filter = readFilter(params);
  const page = filter.page ?? 0;

  // Строка поиска применяется с задержкой, чтобы не искать на каждую букву
  const [query, setQuery] = useState(filter.q ?? '');
  useEffect(() => {
    const timer = setTimeout(() => {
      if (query !== (params.get('q') ?? '')) {
        change({ q: query || null });
      }
    }, 400);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  const result = useQuery({
    queryKey: ['media', filter],
    queryFn: () => mediaApi.search({ ...filter, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  /** Новые условия; страница при этом сбрасывается на первую. */
  function change(values: Record<string, string | string[] | null>, keepPage = false) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) {
      next.delete(key);
      for (const item of Array.isArray(value) ? value : value === null ? [] : [value]) {
        next.append(key, item);
      }
    }
    if (!keepPage) {
      next.delete('page');
    }
    setParams(next);
  }

  const totalPages = result.data ? Math.ceil(result.data.total / PAGE_SIZE) : 0;
  const filtered = params.size > 0;

  return (
    <Page>
      <Stack gap="lg">
        <Row justify="space-between">
          <Title level={1}>Каталог</Title>
          <Button kind="primary" icon={<IconUpload size={16} />} onClick={() => navigate('/upload')}>Загрузить</Button>
        </Row>

        <Grid minWidth={200} gap="sm">
          <TextField label="Поиск" type="search" placeholder="Название или имя файла" value={query} onChange={setQuery} />
          <SelectField label="Вид" placeholder="Любой" clearable value={filter.type ?? null}
            onChange={(v) => change({ type: v })}
            options={Object.entries(kindLabels).map(([value, label]) => ({ value, label }))} />
          <SelectField label="Категория" placeholder="Любая" clearable value={filter.categoryId ?? null}
            onChange={(v) => change({ categoryId: v })}
            options={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          <SelectField label="Состояние" placeholder="Любое" clearable value={filter.status ?? null}
            onChange={(v) => change({ status: v })}
            options={Object.entries(statusLabels).map(([value, { label }]) => ({ value, label }))} />
          <TagsField label="Теги" placeholder="Все из списка" value={filter.tag ?? []}
            onChange={(tags) => change({ tag: tags.map((t) => t.toLowerCase()) })} />
          <SelectField label="Порядок" value={`${filter.sort ?? 'createdAt'}:${filter.direction ?? 'desc'}`}
            onChange={(v) => {
              const [sort, direction] = (v ?? 'createdAt:desc').split(':');
              change({ sort: sort ?? null, direction: direction ?? null });
            }}
            options={sortOptions} />
        </Grid>
        <Row justify="space-between">
          <CheckboxField label="Только мои" value={filter.mine ?? false} onChange={(v) => change({ mine: v ? 'true' : null })} />
          {filtered && <Button kind="subtle" onClick={() => { setQuery(''); setParams(new URLSearchParams()); }}>Сбросить условия</Button>}
        </Row>

        {result.isPending && <Loader />}
        {result.isError && <Alert tone="danger" title="Не удалось загрузить каталог">{errorMessage(result.error)}</Alert>}
        {result.data && result.data.items.length === 0 && (
          <Text muted>{filtered ? 'Ничего не найдено. Попробуйте изменить условия.' : 'Каталог пуст. Загрузите первый файл.'}</Text>
        )}
        {result.data && result.data.items.length > 0 && (
          <>
            <Text muted small>Найдено: {result.data.total}</Text>
            <Grid minWidth={220}>
              {result.data.items.map((media) => <MediaCard key={media.id} media={media} />)}
            </Grid>
            <Row justify="center">
              <Pagination page={page + 1} total={totalPages} onChange={(p) => change({ page: String(p - 1) }, true)} />
            </Row>
          </>
        )}
      </Stack>
    </Page>
  );
}

function readFilter(params: URLSearchParams): MediaFilter {
  const sort = params.get('sort');
  const direction = params.get('direction');
  return {
    q: params.get('q') ?? undefined,
    type: (params.get('type') as MediaKind | null) ?? undefined,
    status: (params.get('status') as MediaStatus | null) ?? undefined,
    categoryId: params.get('categoryId') ?? undefined,
    tag: params.getAll('tag'),
    mine: params.get('mine') === 'true',
    sort: sort === 'title' || sort === 'size' || sort === 'createdAt' ? sort : undefined,
    direction: direction === 'asc' || direction === 'desc' ? direction : undefined,
    page: Number(params.get('page') ?? 0) || 0,
  };
}
