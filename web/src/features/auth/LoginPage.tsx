import { type FormEvent, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { errorMessage } from '@/api/http';
import { session, useSession } from '@/auth/session';
import { Alert, Button, Card, PasswordField, Stack, Text, TextField, Title } from '@/ui';
import styles from './LoginPage.module.css';

/** Вход. Регистрации нет: учётные записи заводит администратор. */
export function LoginPage() {
  const { status } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to={from} replace />;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await session.login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.screen}>
      <Card className={styles.card}>
        <form onSubmit={submit}>
          <Stack gap="md">
            <Stack gap="xs">
              <Title level={1}>MediaGrid</Title>
              <Text muted>Войдите, чтобы открыть каталог</Text>
            </Stack>
            {error && <Alert tone="danger">{error}</Alert>}
            <TextField label="Почта" type="email" value={email} onChange={setEmail} autoComplete="username"
              required autoFocus />
            <PasswordField label="Пароль" value={password} onChange={setPassword}
              autoComplete="current-password" required />
            <Button kind="primary" type="submit" loading={busy || status === 'loading'} fullWidth>Войти</Button>
          </Stack>
        </form>
      </Card>
    </div>
  );
}
