import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { mediaApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { Media } from '@/api/types';
import { Alert, Button, Modal, Row, SelectField, Stack, SwitchField, TagsField, TextField, notify } from '@/ui';
import { useCategories } from './queries';

/** Изменение названия, категории, тегов и видимости; отправляются только изменённые поля. */
export function EditMediaModal({ media, opened, onClose }: { media: Media; opened: boolean; onClose: () => void }) {
  const categories = useCategories();
  const queries = useQueryClient();
  const [title, setTitle] = useState(media.title);
  const [categoryId, setCategoryId] = useState<string | null>(media.category?.id ?? null);
  const [tags, setTags] = useState(media.tags);
  const [publicAccess, setPublicAccess] = useState(media.visibility === 'PUBLIC');

  const save = useMutation({
    mutationFn: () => mediaApi.update(media.id, {
      title: title.trim() !== media.title ? title.trim() : undefined,
      categoryId: categoryId && categoryId !== media.category?.id ? categoryId : undefined,
      removeCategory: !categoryId && media.category ? true : undefined,
      tags: tags.join('\n') !== media.tags.join('\n') ? tags : undefined,
      visibility: publicAccess !== (media.visibility === 'PUBLIC') ? (publicAccess ? 'PUBLIC' : 'PRIVATE') : undefined,
    }),
    onSuccess: (updated) => {
      queries.setQueryData(['media-item', media.id], updated);
      void queries.invalidateQueries({ queryKey: ['media'] });
      notify('Изменения сохранены');
      onClose();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }

  return (
    <Modal opened={opened} onClose={onClose} title="Изменить файл">
      <form onSubmit={submit}>
        <Stack gap="md">
          {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
          <TextField label="Название" value={title} onChange={setTitle} required />
          <SelectField label="Категория" placeholder="Без категории" clearable value={categoryId} onChange={setCategoryId}
            options={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          <TagsField label="Теги" value={tags} maxTags={20} onChange={(t) => setTags(t.map((tag) => tag.toLowerCase()))} />
          <SwitchField label="Виден всем вошедшим пользователям" value={publicAccess} onChange={setPublicAccess} />
          <Row justify="flex-end">
            <Button onClick={onClose}>Отмена</Button>
            <Button kind="primary" type="submit" loading={save.isPending} disabled={!title.trim()}>Сохранить</Button>
          </Row>
        </Stack>
      </form>
    </Modal>
  );
}
