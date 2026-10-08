import { ApiError, errorMessage, toError } from './http';

describe('ошибки служб', () => {
  it('читает код обращения из ответа', async () => {
    const response = new Response(JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: 'Служба недоступна',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736', timestamp: '2026-10-06T10:00:00Z' }), { status: 503 });

    const error = await toError(response);

    expect(error).toMatchObject({ status: 503, code: 'SERVICE_UNAVAILABLE',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736' });
  });

  it('показывает код обращения только при сбое сервера', () => {
    const failure = new ApiError(503, 'SERVICE_UNAVAILABLE', 'Служба недоступна', 'abc123');
    const refusal = new ApiError(401, 'INVALID_CREDENTIALS', 'Неверная почта или пароль', 'abc123');

    expect(errorMessage(failure)).toBe('Служба недоступна (код обращения: abc123)');
    expect(errorMessage(refusal)).toBe('Неверная почта или пароль');
    expect(errorMessage(new ApiError(500, 'X', 'Ошибка'))).toBe('Ошибка');
  });

  it('понимает ответ не в формате служб', async () => {
    const error = await toError(new Response('<html>Bad Gateway</html>', { status: 502 }));

    expect(error).toMatchObject({ status: 502, code: 'HTTP_502', traceId: null });
  });
});
