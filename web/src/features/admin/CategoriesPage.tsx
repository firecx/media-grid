import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { mediaApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { Category } from '@/api/types';
import { useCategories } from '@/features/media/queries';
import {
  Alert, Button, type Column, confirm, IconButton, Loader, Modal, notify, Page, Row, Stack, Table, TextAreaField,
  TextField,
} from '@/ui';
import { AdminNav } from './AdminNav';

/** Справочник категорий; при удалении категории файлы остаются, но без неё. */
export function CategoriesPage() {
  const categories = useCategories();
  const queries = useQueryClient();
  const [editing, setEditing] = useState<Category | 'new' | null>(null);

  const remove = useMutation({
    mutationFn: (id: string) => mediaApi.removeCategory(id),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['categories'] });
      notify('Категория удалена');
    },
    onError: (error) => notify(errorMessage(error), 'danger'),
  });

  async function askRemove(category: Category) {
    if (await confirm({ title: 'Удалить категорию?', confirmLabel: 'Удалить',
      message: `Файлы из «${category.name}» останутся в каталоге без категории.` })) {
      remove.mutate(category.id);
    }
  }

  const columns: Column<Category>[] = [
    { key: 'name', title: 'Название', render: (c) => c.name },
    { key: 'description', title: 'Описание', render: (c) => c.description || '—' },
    {
      key: 'actions',
      title: '',
      width: 100,
      render: (c) => (
        <Row gap="xs">
          <IconButton label="Изменить" onClick={() => setEditing(c)}><IconPencil size={18} /></IconButton>
          <IconButton label="Удалить" kind="danger" onClick={() => void askRemove(c)}><IconTrash size={18} /></IconButton>
        </Row>
      ),
    },
  ];

  return (
    <Page>
      <Stack gap="lg">
        <AdminNav title="Администрирование" />
        <Row justify="flex-end">
          <Button kind="primary" icon={<IconPlus size={16} />} onClick={() => setEditing('new')}>Новая категория</Button>
        </Row>
        {categories.isPending && <Loader />}
        {categories.isError && <Alert tone="danger">{errorMessage(categories.error)}</Alert>}
        {categories.data && <Table columns={columns} rows={categories.data} rowKey={(c) => c.id} empty="Категорий пока нет" />}
      </Stack>
      {editing && <CategoryModal category={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Page>
  );
}

function CategoryModal({ category, onClose }: { category: Category | null; onClose: () => void }) {
  const queries = useQueryClient();
  const [name, setName] = useState(category?.name ?? '');
  const [description, setDescription] = useState(category?.description ?? '');
  const save = useMutation({
    mutationFn: () => (category
      ? mediaApi.updateCategory(category.id, name.trim(), description.trim())
      : mediaApi.createCategory(name.trim(), description.trim())),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['categories'] });
      notify(category ? 'Категория изменена' : 'Категория создана');
      onClose();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };
  return (
    <Modal opened onClose={onClose} title={category ? 'Изменить категорию' : 'Новая категория'}>
      <form onSubmit={submit}>
        <Stack gap="md">
          {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
          <TextField label="Название" value={name} onChange={setName} required />
          <TextAreaField label="Описание" value={description} onChange={setDescription} />
          <Row justify="flex-end">
            <Button onClick={onClose}>Отмена</Button>
            <Button kind="primary" type="submit" loading={save.isPending} disabled={!name.trim()}>Сохранить</Button>
          </Row>
        </Stack>
      </form>
    </Modal>
  );
}
