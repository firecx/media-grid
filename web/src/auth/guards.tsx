import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Alert, Page } from '@/ui';
import { useSession } from './session';

/** Только для вошедших; иначе — на страницу входа с возвратом туда, куда шли. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useSession();
  const location = useLocation();
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return status === 'authenticated' ? <>{children}</> : null;
}

/** Только для администратора (пути /admin/**). Сервер проверяет права сам, это лишь удобство. */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAdmin } = useSession();
  if (!isAdmin) {
    return (
      <Page>
        <Alert tone="warning" title="Недостаточно прав">Раздел доступен только администратору.</Alert>
      </Page>
    );
  }
  return <>{children}</>;
}
