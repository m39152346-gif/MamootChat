# Mamoot Operations Runbook

Free-tier operations for the Supabase backend (project ref
`eslatwgowmovfkkddcnp`, region eu-central-1 / Frankfurt). Written for the
v0.4.1 audit (F3 retention, F19 pause behaviour). No secrets in this file.

## Free-plan limits that bite first (checked 2026-10-21)

| Resource | Free limit | Watch |
|---|---|---|
| Database size | 500 MB | `pg_database_size` — query below |
| Egress (bandwidth out) | 5 GB / month | Supabase dashboard → Reports |
| Auth users (MAU) | 50,000 | — |
| Realtime | 200 concurrent connections, 2M messages/mo | only relevant if enabled (currently not used by the app) |
| Idle pause | project pauses after ~7 days without activity | a paused project must be manually restored; data is kept |

## Database size check (run in the SQL editor)

```sql
select pg_size_pretty(pg_database_size(current_database())) as total,
       pg_database_size(current_database()) as bytes,
       round(100.0 * pg_database_size(current_database()) / (500*1024*1024), 1) as pct_of_free_tier;

select relname, pg_size_pretty(pg_total_relation_size(schemaname || '.' || relname)) as size
from pg_stat_user_tables
where schemaname = 'public'
order by pg_total_relation_size(schemaname || '.' || relname) desc;

select (select count(*) from public.messages) as message_rows,
       (select count(*) from public.messages where deleted_at is not null) as tombstones,
       (select count(*) from public.messages where kind = 'e2ee') as e2ee_rows,
       (select count(*) from public.profiles) as users;
```

Thresholds (of the 500 MB free tier):

- **> 60%** — review: is retention running? Check pg_cron results (below).
  Consider lowering the retention age (e.g. `retention_purge(14)`) or
  enabling the plain-message clause deliberately.
- **> 80%** — act now: run a manual purge, export a backup, and plan the
  Pro upgrade ($25/mo) — on the free tier the database becomes read-only
  when full, which takes the service down.

## Retention (audit F3)

- Nightly job `mamoot-retention` (pg_cron, 03:17 UTC) runs
  `select public.retention_purge(30, false)`:
  - hard-deletes up to 5,000 rows per run that are older than 30 days AND
    (deleted tombstones **or** end-to-end-encrypted rows already read by
    every other conversation member);
  - never touches unread secret-chat ciphertext;
  - never touches plain (non-secret) messages unless called with
    `p_include_plain = true` by an operator;
  - also clears stale typing indicators (> 1 day) and rate-limit buckets
    (> 7 days).
- Verify the job:

```sql
select jobname, schedule, active from cron.job where jobname = 'mamoot-retention';
select start_time, status, return_message
from cron.job_run_details
where jobid = (select jobid from cron.job where jobname = 'mamoot-retention')
order by start_time desc limit 10;
```

- The privacy policy promises exactly this behaviour. If the days or the
  plain-message setting ever change, update `docs/PRIVACY.md` first.

## Idle pause (audit F19)

Free projects pause after ~1 week of inactivity; while paused, the API is
down until someone restores the project in the dashboard. The realistic
mitigation on the free plan is the GitHub Actions keep-alive
(`.github/workflows/keepalive.yml`), which makes one tiny authenticated
REST request every 3 days. This is a workaround, **not** a fix:

- it needs the `MAMOOT_URL` / `MAMOOT_ANON_KEY` repo variables (see
  MANUAL STEPS in `AUDIT.md`);
- scheduled workflows in forks/inactive repos can be disabled by GitHub
  after 60 days without repository activity;
- the real fix is the Pro plan (no pausing). See the capacity numbers in
  `AUDIT.md`.

## Health checks worth doing monthly

```sql
-- Advisors catch most RLS mistakes; also re-run the regression suite:
--   supabase/tests/rls_tests.sql  (self-contained, rolls back, safe anywhere)
select public.retention_purge(30, false);  -- expected: small number, fast
select now();                              -- API/database reachable
```

- Supabase dashboard → Database → Advisors (Security + Performance).
- GitHub → Actions: Android CI green, keep-alive green.
