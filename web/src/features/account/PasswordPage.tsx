import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { useNavigate } from 'react-router';
import { authApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import { session } from '@/auth/session';
import { Alert, Button, Card, notify, Page, PasswordField, Stack, Text, Title } from '@/ui';

/** Смена своего пароля. Сервер завершает все сеансы, поэтому после смены нужно войти заново. */
export function PasswordPage() {
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const mismatch = repeat.length > 0 && next !== repeat;

  const change = useMutation({
    mutationFn: () => authApi.changePassword(current, next),
    onSuccess: async () => {
      notify('Пароль изменён. Войдите с новым паролем.');
      await session.logout();
      navigate('/login', { replace: true });
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!mismatch) {
      change.mutate();
    }
  };

  return (
    <Page>
      <Stack gap="lg" style={{ maxWidth: 440 }}>
        <Title level={1}>Смена пароля</Title>
        <Card>
          <form onSubmit={submit}>
            <Stack gap="md">
              <Text muted small>После смены пароля все ваши сеансы, в том числе на других устройствах, завершатся.</Text>
              {change.isError && <Alert tone="danger">{errorMessage(change.error)}</Alert>}
              <PasswordField label="Текущий пароль" value={current} onChange={setCurrent} required autoComplete="current-password" />
              <PasswordField label="Новый пароль" description="От 8 до 64 символов" value={next} onChange={setNext} required
                autoComplete="new-password" />
              <PasswordField label="Новый пароль ещё раз" value={repeat} onChange={setRepeat} required autoComplete="new-password"
                error={mismatch ? 'Пароли не совпадают' : null} />
              <Button kind="primary" type="submit" loading={change.isPending} disabled={mismatch}>Сменить пароль</Button>
            </Stack>
          </form>
        </Card>
      </Stack>
    </Page>
  );
}
