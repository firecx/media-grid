import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import { ApiError } from '@/api/http';
import { router } from '@/router';
import { session } from '@/auth/session';
import { UiProvider } from '@/ui';

const queries = new QueryClient({
  defaultOptions: {
    queries: {
      // Ошибки «нет прав» и «не найдено» повторять бессмысленно; сбои связи и 5xx — до двух раз
      retry: (failures, error) =>
        failures < 2 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
      refetchOnWindowFocus: false,
    },
  },
});

// Вход восстанавливается по куки обновления ещё до первой отрисовки страниц
void session.restore();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <UiProvider>
      <QueryClientProvider client={queries}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </UiProvider>
  </StrictMode>,
);
