/** Ошибка в едином формате служб: {code, message, traceId, timestamp}. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** Номер трассы запроса: по нему администратор находит запрос в журналах всех служб. */
  readonly traceId: string | null;

  constructor(status: number, code: string, message: string, traceId: string | null = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.traceId = traceId;
  }
}

/**
 * Откуда клиент берёт токен доступа. Токен живёт только в памяти страницы (не в localStorage):
 * его нельзя украсть из хранилища браузера, а после перезагрузки страницы сеанс восстанавливается
 * по куки обновления (HttpOnly, скриптам недоступна).
 */
export interface TokenSource {
  token(): string | null;
  /** Получить новый токен по куки обновления; false — сеанс закончился. */
  refresh(): Promise<boolean>;
}

let tokens: TokenSource = { token: () => null, refresh: async () => false };

export function setTokenSource(source: TokenSource): void {
  tokens = source;
}

export function currentToken(): string | null {
  return tokens.token();
}

export function refreshToken(): Promise<boolean> {
  return tokens.refresh();
}

export type QueryValue = string | number | boolean | string[] | null | undefined;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  json?: unknown;
  query?: Record<string, QueryValue>;
  /** Без токена: вход, обновление, выход. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '' || value === false) {
      continue;
    }
    for (const item of Array.isArray(value) ? value : [value]) {
      params.append(key, String(item));
    }
  }
  const search = params.toString();
  return search ? `${path}?${search}` : path;
}

/**
 * Запрос к /api с токеном доступа. Если токен истёк (401), он один раз обновляется по куки
 * и запрос повторяется. Ответ 204 — undefined.
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options);
  if (response.status === 401 && !options.anonymous && (await tokens.refresh())) {
    response = await send(path, options);
  }
  if (!response.ok) {
    throw await toError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function send(path: string, { method = 'GET', json, query, anonymous, signal }: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = anonymous ? null : tokens.token();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return fetch(buildUrl(path, query), {
    method,
    headers,
    body: json === undefined ? undefined : JSON.stringify(json),
    credentials: 'same-origin',
    signal,
  }).catch((error: unknown) => {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Нет связи с сервером');
  });
}

export async function toError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as { code?: string; message?: string; traceId?: string | null };
    return new ApiError(response.status, body.code ?? `HTTP_${response.status}`,
      body.message ?? defaultMessage(response.status), body.traceId ?? null);
  } catch {
    return new ApiError(response.status, `HTTP_${response.status}`, defaultMessage(response.status));
  }
}

function defaultMessage(status: number): string {
  if (status === 502 || status === 503 || status === 504) {
    return 'Служба временно недоступна, повторите позже';
  }
  return `Ошибка ${status}`;
}

/**
 * Текст ошибки для человека. При сбое на стороне сервера (5xx) — с кодом обращения: назвав его
 * администратору, пользователь даёт найти этот запрос в журналах всех служб.
 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.traceId && error.status >= 500
      ? `${error.message} (код обращения: ${error.traceId})`
      : error.message;
  }
  return error instanceof Error ? error.message : 'Неизвестная ошибка';
}
