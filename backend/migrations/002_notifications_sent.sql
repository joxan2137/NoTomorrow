-- 002_notifications_sent: dedupes the 5-minute job scan so each reminder / 21:00 check
-- goes out at most once per user per local day.

create table if not exists notifications_sent (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  kind text not null check (kind in ('reminder','skipCheck')),
  sent_at timestamptz not null default now(),
  primary key (user_id, day, kind)
);
