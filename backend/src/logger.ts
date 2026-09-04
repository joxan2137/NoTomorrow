import pino, { type Logger } from 'pino';

export type { Logger };

/**
 * Error serializer that keeps only name/message/code/stack. pino's default copies every
 * enumerable property, and postgres.js errors carry `parameters` (bound values such as
 * password hashes or token hashes) — those must never reach the logs.
 */
export function safeErrorSerializer(err: unknown): Record<string, unknown> {
  if (!(err instanceof Error)) return { message: String(err) };
  const code = (err as { code?: unknown }).code;
  return {
    type: err.name,
    message: err.message,
    ...(typeof code === 'string' || typeof code === 'number' ? { code } : {}),
    stack: err.stack,
  };
}

export function createLogger(level: string): Logger {
  return pino({
    level,
    base: { service: 'notomorrow-api' },
    timestamp: pino.stdTimeFunctions.isoTime,
    serializers: { err: safeErrorSerializer, error: safeErrorSerializer },
    redact: {
      paths: [
        'password',
        '*.password',
        'token',
        '*.token',
        'refreshToken',
        '*.refreshToken',
        'accessToken',
        '*.accessToken',
        'authorization',
        '*.authorization',
        'apiKey',
        '*.apiKey',
      ],
      censor: '[redacted]',
    },
  });
}
