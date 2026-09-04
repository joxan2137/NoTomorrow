-- 001_init: core schema (Postgres 16). Every statement is idempotent so a partially
-- applied migration can be re-run safely.

create extension if not exists citext;

create table if not exists users (
  id uuid primary key default gen_random_uuid(),
  username citext unique,
  password_hash text,
  email citext,
  email_verified boolean not null default false,
  display_name text not null default '',
  locale text not null default 'en',
  tz text not null default 'Europe/Warsaw',
  created_at timestamptz not null default now()
);

create table if not exists identities (
  user_id uuid not null references users(id) on delete cascade,
  provider text not null check (provider in ('apple','google','password')),
  subject text not null,
  email_at_link citext,
  apple_refresh_token_enc bytea,
  linked_at timestamptz not null default now(),
  primary key (provider, subject)
);
create index if not exists identities_user_id on identities(user_id);

create table if not exists refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,          -- sha256(token + pepper)
  family uuid not null,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  replaced_by uuid,
  created_at timestamptz not null default now()
);
create index if not exists refresh_tokens_user_id on refresh_tokens(user_id);
create index if not exists refresh_tokens_family on refresh_tokens(family);

create table if not exists push_tokens (
  token text primary key,
  user_id uuid not null references users(id) on delete cascade,
  environment text not null check (environment in ('sandbox','production')),
  updated_at timestamptz not null default now()
);
create index if not exists push_tokens_user_id on push_tokens(user_id);

create table if not exists pair_codes (
  code text primary key,                     -- "NT-7K4Q"
  user_id uuid not null references users(id) on delete cascade,
  expires_at timestamptz not null,
  used_at timestamptz
);
create index if not exists pair_codes_user_id on pair_codes(user_id);

create table if not exists pairings (
  id uuid primary key default gen_random_uuid(),
  user_a uuid not null references users(id) on delete cascade,
  user_b uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  ended_at timestamptz,
  check (user_a <> user_b)
);
create unique index if not exists pairings_active_a on pairings(user_a) where ended_at is null;
create unique index if not exists pairings_active_b on pairings(user_b) where ended_at is null;

create table if not exists schedules (
  user_id uuid primary key references users(id) on delete cascade,
  weekdays int[] not null default '{1,3,5}',
  default_minute int not null default 1080,
  overrides jsonb not null default '{}',
  remind_hour_before boolean not null default true,
  ask_if_skipped_at_21 boolean not null default true,
  updated_at timestamptz not null default now()
);

create table if not exists attendance (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  status text not null check (status in ('planned','confirmed','attended','missed','cancelled')),
  scheduled_minute int not null,
  reason text,
  note text,
  make_up_day date,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table if not exists heads_ups (
  id uuid primary key default gen_random_uuid(),
  from_user uuid not null references users(id) on delete cascade,
  to_user uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('cantMakeIt','runningLate','letsGo','custom','makeUpProposal')),
  text text not null default '' check (char_length(text) <= 80),
  session_day date not null,
  sent_at timestamptz not null default now(),
  read_at timestamptz
);
create index if not exists heads_ups_to_user_sent on heads_ups(to_user, sent_at desc);
create index if not exists heads_ups_from_user_sent on heads_ups(from_user, sent_at desc);

create table if not exists ai_usage (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  count int not null default 0,
  primary key (user_id, day)
);
