import { Hono } from 'hono';
import { z } from 'zod';
import { isUniqueViolation } from '../db.js';
import { displayNameOf, partnerDTO } from '../dto.js';
import { HttpError, parseJson } from '../http.js';
import { PAIR_CODE_TTL_MS, activePairing, endPairings, generatePairCode, normalizePairCode } from '../pairing.js';
import { isoNoMillis } from '../time.js';
import type { AppDeps, AppEnv } from '../types.js';
import { getUser } from '../users.js';

const pairSchema = z.object({ code: z.string().min(1).max(16) });

export function pairRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const { sql, push, events } = deps;

  r.post('/pair/code', async (c) => {
    const userId = c.get('userId');
    const expiresAt = new Date(deps.now().getTime() + PAIR_CODE_TTL_MS);
    for (let attempt = 0; attempt < 8; attempt++) {
      const code = generatePairCode();
      try {
        await sql.begin(async (tx) => {
          // One active code per user: regenerating replaces it. Old used/expired codes are pruned lazily.
          await tx`delete from pair_codes where user_id = ${userId} and used_at is null`;
          await tx`delete from pair_codes where expires_at < now() - interval '7 days'`;
          await tx`insert into pair_codes (code, user_id, expires_at) values (${code}, ${userId}, ${expiresAt})`;
        });
        return c.json({ code, expiresAt: isoNoMillis(expiresAt) });
      } catch (err) {
        if (isUniqueViolation(err)) continue;
        throw err;
      }
    }
    throw new HttpError(503, 'pair_code_unavailable', 'Could not allocate a pair code; try again');
  });

  r.post('/pair', async (c) => {
    const userId = c.get('userId');
    const body = await parseJson(c, pairSchema);
    const code = normalizePairCode(body.code);
    if (!code) throw new HttpError(400, 'invalid_code', 'That does not look like a pair code');
    const now = deps.now();

    let result: { ownerId: string; pairedAt: Date };
    try {
      result = await sql.begin(async (tx) => {
        const rows = await tx<{ userId: string; expiresAt: Date; usedAt: Date | null }[]>`
          select user_id, expires_at, used_at from pair_codes where code = ${code} for update`;
        const row = rows[0];
        if (!row || row.usedAt || row.expiresAt.getTime() <= now.getTime()) {
          throw new HttpError(404, 'code_not_found', 'That code is invalid or has expired');
        }
        if (row.userId === userId) throw new HttpError(400, 'cannot_pair_with_self', 'You cannot pair with yourself');
        if (await activePairing(tx, userId)) throw new HttpError(409, 'already_paired', 'You already have a gym bro');
        if (await activePairing(tx, row.userId)) throw new HttpError(409, 'partner_already_paired', 'That person already has a gym bro');
        const inserted = await tx<{ createdAt: Date }[]>`
          insert into pairings (user_a, user_b) values (${row.userId}, ${userId}) returning created_at`;
        await tx`update pair_codes set used_at = now() where code = ${code}`;
        return { ownerId: row.userId, pairedAt: inserted[0]!.createdAt };
      });
    } catch (err) {
      if (isUniqueViolation(err)) throw new HttpError(409, 'already_paired', 'One of you already has a gym bro');
      throw err;
    }

    const [me, owner] = await Promise.all([getUser(sql, userId), getUser(sql, result.ownerId)]);
    if (!me || !owner) throw new HttpError(404, 'user_not_found', 'Account no longer exists');
    await push.send(owner.id, { kind: 'paired', name: displayNameOf(me) }, { partnerId: me.id });
    events.publish(owner.id, { type: 'pairing', action: 'started', partner: partnerDTO(me, result.pairedAt) });
    deps.log.info({ userId, partnerId: owner.id }, 'paired');

    const partner = partnerDTO(owner, result.pairedAt);
    // Top-level fields are what the iOS `Partner` decodes; `partner` is the spec's envelope.
    return c.json({ ...partner, partner });
  });

  r.delete('/pair', async (c) => {
    const userId = c.get('userId');
    const partners = await endPairings(sql, userId);
    if (partners.length > 0) {
      const me = await getUser(sql, userId);
      const name = me ? displayNameOf(me) : 'Your gym bro';
      for (const partnerId of partners) {
        await push.send(partnerId, { kind: 'unpaired', name }, { partnerId: userId });
        events.publish(partnerId, { type: 'pairing', action: 'ended', partnerId: userId });
      }
      deps.log.info({ userId, partners: partners.length }, 'unpaired');
    }
    return c.json({ ok: true, ended: partners.length });
  });

  return r;
}
