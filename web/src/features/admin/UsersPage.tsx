import { IconKey, IconPencil, IconUserPlus } from '@tabler/icons-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { authApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { Role, User } from '@/api/types';
import { useSession } from '@/auth/session';
import { formatDate } from '@/lib/format';
import {
  Alert, Badge, Button, type Column, IconButton, Loader, Modal, Page, Pagination, PasswordField, Row, SelectField,
  Stack, SwitchField, Table, TextField, notify,
} from '@/ui';
import { AdminNav } from './AdminNav';

const PAGE_SIZE = 20;
const roleOptions = [
  { value: 'USER', label: 'Пользователь' },
  { value: 'ADMIN', label: 'Администратор' },
];

type Dialog = { kind: 'create' } | { kind: 'edit'; user: User } | { kind: 'password'; user: User } | null;

/** Учётные записи: открытой регистрации нет, пользователей заводит администратор. */
export function UsersPage() {
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<Dialog>(null);
  const { user: me } = useSession();
  const users = useQuery({
    queryKey: ['users', page],
    queryFn: () => authApi.users(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  });

  const columns: Column<User>[] = [
    { key: 'email', title: 'Почта', render: (u) => u.email },
    { key: 'name', title: 'Имя', render: (u) => u.displayName },
    { key: 'role', title: 'Роль', render: (u) => (u.role === 'ADMIN' ? <Badge tone="info">Администратор</Badge> : 'Пользователь') },
    { key: 'enabled', title: 'Вход', render: (u) => (u.enabled ? 'разрешён' : <Badge tone="danger">отключён</Badge>) },
    { key: 'created', title: 'Создан', render: (u) => formatDate(u.createdAt) },
    {
      key: 'actions',
      title: '',
      width: 100,
      render: (u) => (
        <Row gap="xs">
          <IconButton label="Изменить" onClick={() => setDialog({ kind: 'edit', user: u })}><IconPencil size={18} /></IconButton>
          <IconButton label="Задать пароль" onClick={() => setDialog({ kind: 'password', user: u })}><IconKey size={18} /></IconButton>
        </Row>
      ),
    },
  ];

  return (
    <Page>
      <Stack gap="lg">
        <AdminNav title="Администрирование" />
        <Row justify="flex-end">
          <Button kind="primary" icon={<IconUserPlus size={16} />} onClick={() => setDialog({ kind: 'create' })}>
            Новый пользователь
          </Button>
        </Row>
        {users.isPending && <Loader />}
        {users.isError && <Alert tone="danger">{errorMessage(users.error)}</Alert>}
        {users.data && (
          <>
            <Table columns={columns} rows={users.data.items} rowKey={(u) => u.id} />
            <Row justify="center">
              <Pagination page={page + 1} total={Math.ceil(users.data.total / PAGE_SIZE)} onChange={(p) => setPage(p - 1)} />
            </Row>
          </>
        )}
      </Stack>
      {dialog?.kind === 'create' && <CreateUserModal onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && <EditUserModal user={dialog.user} self={dialog.user.id === me?.id} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'password' && <PasswordModal user={dialog.user} onClose={() => setDialog(null)} />}
    </Page>
  );
}

function CreateUserModal({ onClose }: { onClose: () => void }) {
  const queries = useQueryClient();
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('USER');
  const create = useMutation({
    mutationFn: () => authApi.createUser({ email: email.trim(), displayName: displayName.trim(), password, role }),
    onSuccess: (user) => {
      void queries.invalidateQueries({ queryKey: ['users'] });
      notify(`Пользователь ${user.email} создан`);
      onClose();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  return (
    <Modal opened onClose={onClose} title="Новый пользователь">
      <form onSubmit={submit}>
        <Stack gap="md">
          {create.isError && <Alert tone="danger">{errorMessage(create.error)}</Alert>}
          <TextField label="Почта" type="email" value={email} onChange={setEmail} required autoComplete="off" />
          <TextField label="Имя" value={displayName} onChange={setDisplayName} required />
          <PasswordField label="Пароль" description="От 8 до 64 символов; сообщите его пользователю"
            value={password} onChange={setPassword} required autoComplete="new-password" />
          <SelectField label="Роль" value={role} onChange={(v) => setRole((v ?? 'USER') as Role)} options={roleOptions} />
          <Row justify="flex-end">
            <Button onClick={onClose}>Отмена</Button>
            <Button kind="primary" type="submit" loading={create.isPending}>Создать</Button>
          </Row>
        </Stack>
      </form>
    </Modal>
  );
}

function EditUserModal({ user, self, onClose }: { user: User; self: boolean; onClose: () => void }) {
  const queries = useQueryClient();
  const [displayName, setDisplayName] = useState(user.displayName);
  const [role, setRole] = useState<Role>(user.role);
  const [enabled, setEnabled] = useState(user.enabled);
  const update = useMutation({
    mutationFn: () => authApi.updateUser(user.id, {
      displayName: displayName.trim() !== user.displayName ? displayName.trim() : undefined,
      role: role !== user.role ? role : undefined,
      enabled: enabled !== user.enabled ? enabled : undefined,
    }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ['users'] });
      notify('Изменения сохранены');
      onClose();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    update.mutate();
  };
  return (
    <Modal opened onClose={onClose} title={user.email}>
      <form onSubmit={submit}>
        <Stack gap="md">
          {update.isError && <Alert tone="danger">{errorMessage(update.error)}</Alert>}
          {self && <Alert tone="warning">Это ваша учётная запись: понизив роль или отключив вход, вы потеряете доступ к администрированию.</Alert>}
          <TextField label="Имя" value={displayName} onChange={setDisplayName} required />
          <SelectField label="Роль" value={role} onChange={(v) => setRole((v ?? 'USER') as Role)} options={roleOptions} />
          <SwitchField label="Вход разрешён (отключение завершает все сеансы)" value={enabled} onChange={setEnabled} />
          <Row justify="flex-end">
            <Button onClick={onClose}>Отмена</Button>
            <Button kind="primary" type="submit" loading={update.isPending}>Сохранить</Button>
          </Row>
        </Stack>
      </form>
    </Modal>
  );
}

function PasswordModal({ user, onClose }: { user: User; onClose: () => void }) {
  const [password, setPassword] = useState('');
  const reset = useMutation({
    mutationFn: () => authApi.resetPassword(user.id, password),
    onSuccess: () => {
      notify(`Пароль для ${user.email} изменён, все его сеансы завершены`);
      onClose();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    reset.mutate();
  };
  return (
    <Modal opened onClose={onClose} title={`Новый пароль: ${user.email}`}>
      <form onSubmit={submit}>
        <Stack gap="md">
          {reset.isError && <Alert tone="danger">{errorMessage(reset.error)}</Alert>}
          <PasswordField label="Новый пароль" description="От 8 до 64 символов" value={password} onChange={setPassword}
            required autoComplete="new-password" />
          <Row justify="flex-end">
            <Button onClick={onClose}>Отмена</Button>
            <Button kind="primary" type="submit" loading={reset.isPending}>Задать пароль</Button>
          </Row>
        </Stack>
      </form>
    </Modal>
  );
}
