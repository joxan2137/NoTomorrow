import { Hono, type Context } from 'hono';
import { z } from 'zod';
import { USERNAME_RE, hashPassword, normalizeUsername, validatePassword, validateUsername, verifyPassword } from '../auth/password.js';
import { issueSession } from '../auth/session.js';
import { appleSignInSchema, googleSignInSchema, resolveAppleIdentity, resolveGoogleIdentity } from '../auth/social.js';
import { pgRefreshTokenStore, revokeRefreshToken, rotateRefreshToken } from '../auth/tokens.js';
import { isUniqueViolation } from '../db.js';
import { HttpError, ok, parseJson } from '../http.js';
import type { AppDeps, AppEnv } from '../types.js';
import { findOrCreateIdentityUser } from '../users.js';

const registerSchema = z.object({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(1024),
  email: z.email().max(254).nullish(),
});

const loginSchema = z.object({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(1024),
});

const refreshSchema = z.object({ refreshToken: z.string().min(1).max(512) });

export function authRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const store = pgRefreshTokenStore(deps.sql);

  r.post('/auth/register', async (c) => {
    const body = await parseJson(c, registerSchema);
    const u = validateUsername(body.username);
    if (!u.ok) throw new HttpError(400, u.error, u.message);
    const p = validatePassword(body.password, u.username);
    if (!p.ok) throw new HttpError(400, p.error, p.message);
    const email = body.email?.trim().toLowerCase() || null;
    const passwordHash = await hashPassword(body.password);

    let userId: string;
    try {
      userId = await deps.sql.begin(async (tx) => {
        const rows = await tx<{ id: string }[]>`
          insert into users (username, password_hash, email, display_name)
          values (${u.username}, ${passwordHash}, ${email}, ${u.username})
          returning id`;
        const id = rows[0]!.id;
        await tx`insert into identities (user_id, provider, subject, email_at_link) values (${id}, 'password', ${u.username}, ${email})`;
        return id;
      });
    } catch (err) {
      if (isUniqueViolation(err)) throw new HttpError(409, 'username_taken', 'That username is already taken');
      throw err;
    }
    deps.log.info({ userId }, 'user registered');
    return c.json(await issueSession(deps, userId), 201);
  });

  const login = async (c: Context<AppEnv>) => {
    const body = await parseJson(c, loginSchema);
    const username = normalizeUsername(body.username);
    const nowMs = deps.now().getTime();
    const gate = deps.throttle.check(username, nowMs);
    if (!gate.allowed) {
      const retryAfter = String(Math.max(1, Math.ceil(gate.retryAfterMs / 1000)));
      throw new HttpError(
        429,
        gate.locked ? 'account_locked' : 'too_many_attempts',
        'Too many failed sign-in attempts; try again later',
        undefined,
        { 'Retry-After': retryAfter },
      );
    }
    // Always run argon2 (against a dummy hash for unknown users) so timing does not leak existence.
    const rows = USERNAME_RE.test(username)
      ? await deps.sql<{ id: string; passwordHash: string | null }[]>`select id, password_hash from users where username = ${username}`
      : [];
    const user = rows[0];
    const valid = await verifyPassword(user?.passwordHash ?? null, body.password);
    if (!valid || !user) {
      deps.throttle.recordFailure(username, nowMs);
      throw new HttpError(401, 'invalid_credentials', 'Wrong username or password');
    }
    deps.throttle.recordSuccess(username);
    return c.json(await issueSession(deps, user.id));
  };
  r.post('/auth/login', login);
  // The iOS client posts to /auth/password.
  r.post('/auth/password', login);

  r.post('/auth/apple', async (c) => {
    const body = await parseJson(c, appleSignInSchema);
    const ident = await resolveAppleIdentity(deps, body);
    const { user, created } = await findOrCreateIdentityUser(deps.sql, ident);
    if (created) deps.log.info({ userId: user.id, provider: 'apple' }, 'user created');
    return c.json(await issueSession(deps, user.id));
  });

  r.post('/auth/google', async (c) => {
    const body = await parseJson(c, googleSignInSchema);
    const ident = await resolveGoogleIdentity(deps, body);
    const { user, created } = await findOrCreateIdentityUser(deps.sql, ident);
    if (created) deps.log.info({ userId: user.id, provider: 'google' }, 'user created');
    return c.json(await issueSession(deps, user.id));
  });

  r.post('/auth/refresh', async (c) => {
    const body = await parseJson(c, refreshSchema);
    const result = await rotateRefreshToken(store, deps.env.refreshPepper, body.refreshToken, deps.now());
    if (!result.ok) {
      if (result.reason === 'reused') deps.log.warn('refresh token reuse detected; token family revoked');
      throw new HttpError(401, 'invalid_refresh_token', 'Refresh token is invalid or expired');
    }
    return c.json(await issueSession(deps, result.userId, { refreshToken: result.token }));
  });

  r.post('/auth/logout', async (c) => {
    const body = await parseJson(c, refreshSchema);
    await revokeRefreshToken(store, deps.env.refreshPepper, body.refreshToken, deps.now());
    return ok(c);
  });

  return r;
}
