import { Hono } from 'hono';
import { z } from 'zod';
import { AIParseError, GeminiError, MEAL_SLOTS, estimateFood } from '../ai.js';
import { HttpError } from '../http.js';
import { normalizeLocale } from '../i18n.js';
import { localParts } from '../time.js';
import { getUser } from '../users.js';
import type { AppDeps, AppEnv } from '../types.js';

export const AI_IMAGE_MAX_BYTES = 4 * 1024 * 1024;

const fieldsSchema = z.object({
  meal: z.enum(MEAL_SLOTS),
  notes: z.string().trim().max(1500).default(''),
  locale: z.string().min(2).max(16).default('en'),
});

function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

export function aiRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const { sql, log } = deps;

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

  r.post('/ai/estimate', async (c) => {
    // Claude-with-your-own-key never goes through this server; the app must call Anthropic directly.
    if (c.req.header('x-anthropic-key')) throw new HttpError(400, 'byok_is_device_direct', 'Bring-your-own-key requests go device → Anthropic');
    if (!deps.env.gemini) throw new HttpError(503, 'ai_unavailable', 'AI estimates are not configured on this server');
    if (!(c.req.header('content-type') ?? '').toLowerCase().includes('multipart/form-data')) {
      throw new HttpError(400, 'multipart_required', 'Send multipart/form-data with image, meal and locale');
    }

    const userId = c.get('userId');
    // The server's own Gemini key is for the people on AI_ALLOWED_USERS. Everyone else is told how to
    // get on the list or to bring their own key (which never touches this server: the app calls
    // Google or Anthropic directly). Checked before the body is read, so a refused upload costs nothing.
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
    const fields = fieldsSchema.safeParse({ meal: form.meal, notes: form.notes, locale: form.locale ?? 'en' });
    if (!fields.success) throw new HttpError(400, 'invalid_body', 'meal must be breakfast|lunch|snack|dinner, locale en|pl');
    const image = form.image;
    if (!(image instanceof File)) throw new HttpError(400, 'image_required', 'Attach the photo as the "image" part');
    if (image.size > AI_IMAGE_MAX_BYTES) throw new HttpError(413, 'image_too_large', 'Image must be at most 4 MB');
    const bytes = new Uint8Array(await image.arrayBuffer());
    if (!isJpeg(bytes)) throw new HttpError(400, 'image_not_jpeg', 'Image must be a JPEG');

    const day = localParts(deps.now(), 'UTC').day;
    if (deps.env.aiDailyLimit <= 0 || !(await reserveQuota(userId, day))) {
      throw new HttpError(429, 'ai_daily_limit', `Daily limit of ${deps.env.aiDailyLimit} AI estimates reached`, undefined, {
        'Retry-After': '3600',
      });
    }

    try {
      const estimate = await estimateFood(
        deps.env.gemini,
        { imageBase64: Buffer.from(bytes).toString('base64'), meal: fields.data.meal, locale: normalizeLocale(fields.data.locale), notes: fields.data.notes },
        deps.fetchImpl,
      );
      return c.json(estimate);
    } catch (err) {
      await releaseQuota(userId, day).catch((e) => log.warn({ err: e }, 'ai quota release failed'));
      if (err instanceof GeminiError) {
        log.warn({ status: err.status, message: err.message }, 'gemini request failed');
        if (err.status === 429 || (err.status !== undefined && err.status >= 500)) {
          throw new HttpError(503, 'ai_busy', 'The AI is busy right now; try again in a minute');
        }
        throw new HttpError(502, 'ai_upstream_error', 'The AI provider did not answer; try again');
      }
      if (err instanceof AIParseError) {
        log.warn({ message: err.message }, 'gemini answer unparseable');
        throw new HttpError(502, 'ai_unparseable', 'The AI provider returned something unexpected; try again');
      }
      throw err;
    }
  });

  return r;
}
