import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import postgres from 'postgres';

/**
 * postgres.js (porsager) client. Every query goes through the `sql` tagged template, which
 * always uses prepared statements with bound parameters — never string interpolation.
 * Column names come back camelCased (`user_id` → `userId`).
 */
export type Sql = ReturnType<typeof postgres>;
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export type TransactionSql = postgres.TransactionSql<{}>;
export type Queryable = Sql | TransactionSql;

export const MIGRATIONS_DIR = fileURLToPath(new URL('../migrations/', import.meta.url));

export function createDb(url: string, opts: { max?: number } = {}): Sql {
  return postgres(url, {
    max: opts.max ?? 10,
    idle_timeout: 30,
    connect_timeout: 10,
    prepare: true,
    transform: postgres.camel,
    onnotice: () => {},
  });
}

export function isUniqueViolation(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { code?: string }).code === '23505';
}

const MIGRATION_LOCK_KEY = 724_001;

/**
 * Applies `*.sql` files from `dir` in lexical order, once each, tracked in `schema_migrations`.
 * Runs under an advisory lock so two booting machines cannot race. Returns the names applied.
 */
export async function runMigrations(sql: Sql, dir: string = MIGRATIONS_DIR): Promise<string[]> {
  await sql`
    create table if not exists schema_migrations (
      name text primary key,
      applied_at timestamptz not null default now()
    )`;
  const files = (await readdir(dir)).filter((f) => f.endsWith('.sql')).sort();
  const applied: string[] = [];
  await sql.begin(async (tx) => {
    await tx`select pg_advisory_xact_lock(${MIGRATION_LOCK_KEY})`;
    const done = new Set((await tx<{ name: string }[]>`select name from schema_migrations`).map((r) => r.name));
    for (const file of files) {
      if (done.has(file)) continue;
      const body = await readFile(join(dir, file), 'utf8');
      await tx.unsafe(body);
      await tx`insert into schema_migrations (name) values (${file})`;
      applied.push(file);
    }
  });
  return applied;
}
