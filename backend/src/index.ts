import { serve } from '@hono/node-server';
import { createApp } from './app.js';
import { appleJwks } from './auth/apple.js';
import { googleJwks } from './auth/google.js';
import { LoginThrottle } from './auth/password.js';
import { createDb, runMigrations } from './db.js';
import { EnvError, loadEnv } from './env.js';
import { EventBus } from './events.js';
import { createJobs } from './jobs.js';
import { createLogger } from './logger.js';
import { createPushService } from './push.js';
import type { AppDeps } from './types.js';

const SHUTDOWN_TIMEOUT_MS = 10_000;

async function main(): Promise<void> {
  const env = loadEnv();
  const log = createLogger(env.logLevel);
  for (const warning of env.warnings) log.warn(warning);

  const sql = createDb(env.databaseUrl);
  const applied = await runMigrations(sql);
  log.info({ applied }, applied.length > 0 ? 'migrations applied' : 'migrations up to date');

  const push = createPushService({ sql, log, config: env.apns });
  const deps: AppDeps = {
    env,
    sql,
    log,
    push,
    events: new EventBus(),
    throttle: new LoginThrottle(),
    fetchImpl: fetch,
    jwks: { apple: appleJwks(), google: googleJwks() },
    now: () => new Date(),
  };

  const jobs = env.jobsEnabled ? createJobs({ sql, log, push, databaseUrl: env.databaseUrl }) : null;
  if (jobs) await jobs.start();
  else log.warn('jobs disabled (JOBS_ENABLED=false)');

  const app = createApp(deps);
  const server = serve({ fetch: app.fetch, port: env.port, hostname: '0.0.0.0' }, (info) => {
    log.info({ port: info.port, env: env.nodeEnv }, 'listening');
  });

  let shuttingDown = false;
  const shutdown = async (signal: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info({ signal }, 'shutting down');
    const deadline = setTimeout(() => {
      log.error('shutdown timed out; exiting');
      process.exit(1);
    }, SHUTDOWN_TIMEOUT_MS);
    deadline.unref();

    // Stop taking new connections; SSE clients hold theirs open, so cut them after a short grace.
    await new Promise<void>((resolve) => {
      server.close(() => resolve());
      const s = server as unknown as { closeIdleConnections?: () => void; closeAllConnections?: () => void };
      s.closeIdleConnections?.();
      setTimeout(() => s.closeAllConnections?.(), 2_000).unref();
    });
    await jobs?.stop().catch((err: unknown) => log.error({ err }, 'jobs stop failed'));
    await push.shutdown().catch((err: unknown) => log.error({ err }, 'apns shutdown failed'));
    await sql.end({ timeout: 5 }).catch((err: unknown) => log.error({ err }, 'db close failed'));
    log.info('shutdown complete');
    process.exit(0);
  };

  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('unhandledRejection', (err) => log.error({ err }, 'unhandled promise rejection'));
  process.on('uncaughtException', (err) => {
    log.fatal({ err }, 'uncaught exception');
    void shutdown('uncaughtException');
  });
}

main().catch((err: unknown) => {
  // Boot failures go to stderr in plain text; EnvError names variables but never values.
  console.error(err instanceof EnvError ? err.message : err);
  process.exit(1);
});
