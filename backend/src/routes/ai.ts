import type { Context } from 'hono';
import { Hono } from 'hono';
import { z } from 'zod';
import { AIParseError, GeminiError, MEAL_SLOTS, estimateFood, readLabel, type AIUsage } from '../ai.js';
import { loadSpec } from '../aiSpec.js';
import type { GeminiConfig } from '../env.js';
import { HttpError } from '../http.js';
import { normalizeLocale } from '../i18n.js';
import { localParts } from '../time.js';
import { getUser } from '../users.js';
import type { AppDeps, AppEnv } from '../types.js';

export const AI_IMAGE_MAX_BYTES = 4 * 1024 * 1024;
/** Multipart routes: `app.ts` gives these the larger body limit. */
export const AI_MULTIPART_PATHS = new Set(['/ai/estimate', '/ai/label']);

const estimateFields = z.object({
  meal: z.enum(MEAL_SLOTS),
  notes: z.string().trim().max(1500).default(''),
  locale: z.string().min(2).max(16).default('en'),
});

const labelFields = z.object({
  locale: z.string().min(2).max(16).default('en'),
});

function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

interface PreparedUpload {
  gemini: GeminiConfig;
  userId: string;
  form: Record<string, string | File>;
}

export function aiRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const { sql, log } = deps;
  if (deps.env.gemini) {
    // Fail loudly at boot if data/ai/estimate-spec.json is missing or invalid; the other routes keep working.
    try {
      loadSpec();
    } catch (err) {
      log.error({ err }, 'AI spec could not be loaded; /ai routes will fail');
    }
  }

  /** Reserves one unit of today's quota; false when the daily limit is reached. */
  async function reserveQuota(userId: string, day: string): Promise<boolean> {
    const rows = await sql<{ count: number }[]>`
      insert into ai_usage (user_id, day, count) values (${userId}, ${day}, 1)
      on conflict (user_id, day) do update set count = ai_usage.count + 1
      where ai_usage.count < ${deps.env.aiDailyLimit}
      returning count`;
    return rows.length > 0;
  }

  async function releaseQuota(userId: string, day: string): Promise<void> {
    await sql`update ai_usage set count = greatest(count - 1, 0) where user_id = ${userId} and day = ${day}`;
  }

  /**
   * Guards shared by every AI route, in this order: BYOK header refusal, provider configured,
   * multipart, allowlist (before the body is read, so a refused upload costs nothing), then the
   * multipart body. Each route validates its fields next, then the image (`readImage`), then quota.
   */
  async function prepare(c: Context<AppEnv>, what: string): Promise<PreparedUpload> {
    // Claude-with-your-own-key never goes through this server; the app must call Anthropic directly.
    if (c.req.header('x-anthropic-key')) throw new HttpError(400, 'byok_is_device_direct', 'Bring-your-own-key requests go device → Anthropic');
    const gemini = deps.env.gemini;
    if (!gemini) throw new HttpError(503, 'ai_unavailable', `AI ${what} is not configured on this server`);
    if (!(c.req.header('content-type') ?? '').toLowerCase().includes('multipart/form-data')) {
      throw new HttpError(400, 'multipart_required', 'Send multipart/form-data with the image and its fields');
    }

    const userId = c.get('userId');
    // The server's own Gemini key is for the people on AI_ALLOWED_USERS. Everyone else is told how to
    // get on the list or to bring their own key (which never touches this server: the app calls
    // Google or Anthropic directly).
    if (deps.env.aiAllowedUsers.length > 0) {
      const user = await getUser(sql, userId);
      const username = user?.username?.toLowerCase() ?? '';
      if (!username || !deps.env.aiAllowedUsers.includes(username)) {
        throw new HttpError(403, 'ai_not_allowed', 'AI estimates on this server are limited to whitelisted users. Ask the owner to whitelist you, or add your own Gemini or Claude API key in Settings.');
      }
    }
    let form: Record<string, string | File>;
    try {
      form = await c.req.parseBody({ all: false });
    } catch {
      throw new HttpError(400, 'invalid_multipart', 'Could not parse the multipart body');
    }
    return { gemini, userId, form };
  }

  async function readImage(form: Record<string, string | File>): Promise<string> {
    const image = form.image;
    if (!(image instanceof File)) throw new HttpError(400, 'image_required', 'Attach the photo as the "image" part');
    if (image.size > AI_IMAGE_MAX_BYTES) throw new HttpError(413, 'image_too_large', 'Image must be at most 4 MB');
    const bytes = new Uint8Array(await image.arrayBuffer());
    if (!isJpeg(bytes)) throw new HttpError(400, 'image_not_jpeg', 'Image must be a JPEG');
    return Buffer.from(bytes).toString('base64');
  }

  /**
   * Reserves a quota unit, runs the provider call and maps failures. The unit is refunded only when
   * the provider was certainly not billed (rate-limited, overloaded or unreachable before answering);
   * an answer we could not use, or a timeout after the request left, keeps it consumed.
   */
  async function withQuota<T>(userId: string, kind: 'estimate' | 'label', run: () => Promise<T>): Promise<T> {
    const day = localParts(deps.now(), 'UTC').day;
    if (deps.env.aiDailyLimit <= 0 || !(await reserveQuota(userId, day))) {
      throw new HttpError(429, 'ai_daily_limit', `Daily limit of ${deps.env.aiDailyLimit} AI requests reached`, undefined, {
        'Retry-After': '3600',
      });
    }
    try {
      return await run();
    } catch (err) {
      const billed = (err as { billed?: unknown } | null)?.billed;
      if (billed === false) await releaseQuota(userId, day).catch((e) => log.warn({ err: e }, 'ai quota release failed'));
      if (err instanceof GeminiError) {
        log.warn({ ai: kind, status: err.status, kind: err.kind, billed: err.billed, message: err.message }, 'gemini request failed');
        if (err.kind === 'timeout') throw new HttpError(504, 'ai_timeout', 'The AI took too long to answer; try again');
        if (err.kind === 'busy' || err.kind === 'network') throw new HttpError(503, 'ai_busy', 'The AI is busy right now; try again in a minute');
        throw new HttpError(502, 'ai_upstream_error', 'The AI provider did not answer; try again');
      }
      if (err instanceof AIParseError) {
        log.warn({ ai: kind, code: err.code, message: err.message }, 'gemini answer unparseable');
        throw new HttpError(502, 'ai_unparseable', 'The AI provider returned something unexpected; try again');
      }
      throw err;
    }
  }

  function logUsage(kind: 'estimate' | 'label', usage: AIUsage, warnings: string[], extra: Record<string, unknown>): void {
    log.info({ ai: kind, ...usage, ...extra }, 'ai usage');
    for (const warning of warnings) log.warn({ ai: kind, warning }, 'gemini fallback');
  }

  r.post('/ai/estimate', async (c) => {
    const prep = await prepare(c, 'estimates');
    const fields = estimateFields.safeParse({ meal: prep.form.meal, notes: prep.form.notes, locale: prep.form.locale ?? 'en' });
    if (!fields.success) throw new HttpError(400, 'invalid_body', 'meal must be breakfast|lunch|snack|dinner, locale en|pl, notes ≤ 1500 characters');
    const imageBase64 = await readImage(prep.form);

    const { estimate, usage, warnings } = await withQuota(prep.userId, 'estimate', () => estimateFood(
      prep.gemini,
      { imageBase64, meal: fields.data.meal, locale: normalizeLocale(fields.data.locale), notes: fields.data.notes },
      deps.fetchImpl,
    ));
    logUsage('estimate', usage, warnings, { foods: estimate.foods.length, skipped: estimate.skipped.length });
    return c.json(estimate);
  });

  r.post('/ai/label', async (c) => {
    const prep = await prepare(c, 'label reads');
    const fields = labelFields.safeParse({ locale: prep.form.locale ?? 'en' });
    if (!fields.success) throw new HttpError(400, 'invalid_body', 'locale must be en|pl');
    const imageBase64 = await readImage(prep.form);

    const { reading, usage, warnings } = await withQuota(prep.userId, 'label', () => readLabel(
      prep.gemini,
      { imageBase64, locale: normalizeLocale(fields.data.locale) },
      deps.fetchImpl,
    ));
    logUsage('label', usage, warnings, { legible: reading.legible, needsReview: reading.needsReview });
    return c.json(reading);
  });

  return r;
}
