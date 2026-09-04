import type { MeDTO } from '../dto.js';
import { HttpError } from '../http.js';
import type { AppDeps } from '../types.js';
import { loadMe } from '../users.js';
import { signAccessToken } from './jwt.js';
import { issueRefreshToken, pgRefreshTokenStore } from './tokens.js';

/** What every sign-in/refresh returns. `userId` is what the iOS `Session` decodes; `user` saves a round-trip. */
export interface SessionDTO {
  accessToken: string;
  refreshToken: string;
  userId: string;
  user: MeDTO;
}

export async function issueSession(deps: AppDeps, userId: string, opts: { refreshToken?: string } = {}): Promise<SessionDTO> {
  const user = await loadMe(deps.sql, userId);
  if (!user) throw new HttpError(401, 'unauthorized', 'Account no longer exists');
  const now = deps.now();
  const accessToken = await signAccessToken(deps.env.jwtSecret, userId, { now });
  const refreshToken =
    opts.refreshToken ?? (await issueRefreshToken(pgRefreshTokenStore(deps.sql), deps.env.refreshPepper, userId, { now })).token;
  return { accessToken, refreshToken, userId, user };
}
