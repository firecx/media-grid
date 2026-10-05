import { useSyncExternalStore } from 'react';
import { authApi } from '@/api/endpoints';
import { setTokenSource } from '@/api/http';
import type { TokenResponse, User } from '@/api/types';

export type SessionStatus = 'loading' | 'anonymous' | 'authenticated';

export interface SessionState {
  status: SessionStatus;
  user: User | null;
}

let state: SessionState = { status: 'loading', user: null };
let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<() => void>();

function update(next: SessionState): void {
  state = next;
  listeners.forEach((listener) => listener());
}

function accept(response: TokenResponse): void {
  accessToken = response.accessToken;
  update({ status: 'authenticated', user: response.user });
}

function clear(): void {
  accessToken = null;
  update({ status: 'anonymous', user: null });
}

/**
 * Сеанс пользователя. Токен доступа — только в памяти; обновляемый токен — в куки HttpOnly,
 * которую браузер сам отправляет на /api/auth. Несколько одновременных 401 приводят к одному
 * обновлению: остальные запросы ждут его результата.
 */
export const session = {
  token: (): string | null => accessToken,

  refresh(): Promise<boolean> {
    refreshing ??= authApi.refresh()
      .then((response) => {
        accept(response);
        return true;
      })
      .catch(() => {
        clear();
        return false;
      })
      .finally(() => {
        refreshing = null;
      });
    return refreshing;
  },

  /** При открытии страницы: есть ли действующий вход (куки обновления). */
  restore(): Promise<boolean> {
    return session.refresh();
  },

  async login(email: string, password: string): Promise<void> {
    accept(await authApi.login(email, password));
  },

  async logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      clear();
    }
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  snapshot: (): SessionState => state,
};

setTokenSource(session);

export function useSession(): SessionState & { isAdmin: boolean } {
  const current = useSyncExternalStore(session.subscribe, session.snapshot);
  return { ...current, isAdmin: current.user?.role === 'ADMIN' };
}
