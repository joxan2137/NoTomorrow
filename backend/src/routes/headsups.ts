import { Hono, type Context } from 'hono';
import { z } from 'zod';
import { HEADS_UP_KINDS, displayNameOf, headsUpDTO, type HeadsUpRow } from '../dto.js';
import { HttpError, ok, parseJson } from '../http.js';
import { defaultHeadsUpText, normalizeLocale } from '../i18n.js';
import { activePairing } from '../pairing.js';
import { toDayKey } from '../time.js';
import type { AppDeps, AppEnv } from '../types.js';
import { getUser } from '../users.js';

export const HEADS_UP_MAX_CHARS = 80;

const headsUpSchema = z.object({
  kind: z.enum(HEADS_UP_KINDS),
  text: z.string().max(1000).default(''),
  sessionDay: z.string().min(8).max(40),
});

export function headsUpRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const { sql, push, events } = deps;

  const create = async (c: Context<AppEnv>) => {
    const userId = c.get('userId');
    const body = await parseJson(c, headsUpSchema);
    const text = body.text.trim();
    if (Array.from(text).length > HEADS_UP_MAX_CHARS) {
      throw new HttpError(400, 'text_too_long', `Heads-up text must be at most ${HEADS_UP_MAX_CHARS} characters`);
    }
    const me = await getUser(sql, userId);
    if (!me) throw new HttpError(404, 'user_not_found', 'Account no longer exists');
    const pairing = await activePairing(sql, userId);
    if (!pairing) throw new HttpError(404, 'not_paired', 'You are not paired with anyone');
    const partner = await getUser(sql, pairing.partnerId);
    if (!partner) throw new HttpError(404, 'not_paired', 'Your gym bro no longer exists');
    const sessionDay = toDayKey(body.sessionDay, me.tz);
    if (!sessionDay) throw new HttpError(400, 'invalid_session_day', 'sessionDay must be YYYY-MM-DD or an ISO-8601 date-time');

    const rows = await sql<HeadsUpRow[]>`
      insert into heads_ups (from_user, to_user, kind, text, session_day)
      values (${userId}, ${partner.id}, ${body.kind}, ${text}, ${sessionDay})
      returning id, from_user, to_user, kind, text, session_day::text as session_day, sent_at, read_at`;
    const row = rows[0]!;

    const bodyText = text || defaultHeadsUpText(normalizeLocale(partner.locale), body.kind);
    await push.send(
      partner.id,
      { kind: 'headsUp', name: displayNameOf(me), text: bodyText },
      { headsUpId: row.id, headsUpKind: body.kind, sessionDay, partnerId: userId },
    );
    events.publish(partner.id, { type: 'headsUp', ...headsUpDTO(row, partner.id, partner.tz) });
    return c.json(headsUpDTO(row, userId, me.tz), 201);
  };
  r.post('/headsups', create);
  // The iOS client posts to /headsup.
  r.post('/headsup', create);

  r.post('/headsups/:id/read', async (c) => {
    const id = z.uuid().safeParse(c.req.param('id'));
    if (!id.success) throw new HttpError(400, 'invalid_id', 'Heads-up id must be a UUID');
    const rows = await sql<{ id: string }[]>`
      update heads_ups set read_at = coalesce(read_at, now())
      where id = ${id.data} and to_user = ${c.get('userId')}
      returning id`;
    if (rows.length === 0) throw new HttpError(404, 'heads_up_not_found', 'No such heads-up for you');
    return ok(c);
  });

  return r;
}
