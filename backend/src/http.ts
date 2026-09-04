import type { Context } from 'hono';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import type { ZodError, ZodType } from 'zod';

/** Thrown from routes; `app.onError` turns it into `{error, message, details?}` with the status. */
export class HttpError extends Error {
  constructor(
    readonly status: ContentfulStatusCode,
    readonly code: string,
    message?: string,
    readonly details?: unknown,
    readonly headers?: Record<string, string>,
  ) {
    super(message ?? code);
    this.name = 'HttpError';
  }

  body(): { error: string; message: string; details?: unknown } {
    return { error: this.code, message: this.message, ...(this.details !== undefined ? { details: this.details } : {}) };
  }
}

export function zodDetails(error: ZodError): Array<{ path: string; message: string }> {
  return error.issues.map((issue) => ({ path: issue.path.map(String).join('.'), message: issue.message }));
}

/** Parses and validates a JSON body; any failure becomes a 400 with `{error}` and field details. */
export async function parseJson<T>(c: Context, schema: ZodType<T>): Promise<T> {
  let raw: unknown;
  try {
    raw = await c.req.json();
  } catch {
    throw new HttpError(400, 'invalid_json', 'Request body must be valid JSON');
  }
  const result = schema.safeParse(raw);
  if (!result.success) throw new HttpError(400, 'invalid_body', 'Request body failed validation', zodDetails(result.error));
  return result.data;
}

export function ok(c: Context) {
  return c.json({ ok: true });
}
