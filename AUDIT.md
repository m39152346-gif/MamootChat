# MamootChat — Audit & Hardening Log (v0.4.0 → v0.4.1)

Date: 2026-10-21 · Branch: `arena/c9437d98-mamootchat` (PR #1) · Base: v0.4.0 (`f4fc192`)

Method: every finding from the audit brief was re-verified line-by-line in the
real source before any change. Verdicts below distinguish **Confirmed**,
**Partially wrong**, and **Already fixed**. Nothing is marked fixed without a
unit test, a transactional SQL test, or a reproduced measurement.

Deviations from the brief's workflow (documented, not silent):

- The session is bound to a single branch/PR, so "one PR per area" is
  implemented as small per-area commits on PR #1.
- F7 suggests `SecretKeyFactory("PBKDF2WithHmacSHA256")`. Android's Conscrypt
  PBKDF2 historically truncates password chars to 8 bits, while the JVM test
  provider encodes differently — for Persian (non-ASCII) passphrases that
  risks create/restore mismatches and unverifiable test vectors. We instead
  keep the byte-exact manual PBKDF2 (UTF-8), fix the actual perf bug (a new
  `Mac` per iteration → one `Mac` per derivation), and raise iterations.
  Same security goal, deterministic across platforms, RFC vector still valid.

## Free-plan facts (re-checked 2026-10-21)

Supabase pricing page (2026-10-21): Free = 500 MB DB, 5 GB egress, 50k MAU,
"Free projects are paused after 1 week of inactivity. Limit of 2 active
projects." Realtime free limits (docs, checked 2026-10-20): 200 concurrent
connections, 2M messages/month. Vercel pricing page (2026-10-21): Hobby =
100 GB Fast Data Transfer/mo, 1M function invocations/mo; Hobby is for
non-commercial use (Fair Use policy). Current production DB size measured
2026-10-21: 12 MB total, 2 profiles, 13 messages (pre-launch).

## Findings

| # | Sev | Verdict | Evidence (file:line) | Fix | Status |
|---|-----|---------|----------------------|-----|--------|
| F1 | HIGH | **Confirmed** | `sync/ChatRepository.kt:882-885` revise branch passes attacker-chosen `plain.reviseTargetId`; `storage/LocalDb.kt:215-221` `applyReviseByServerId` updates `WHERE server_id = ?` only; `applyReactionLocal` `ChatRepository.kt:1196-1207` + `setReactionsJsonByServerId` likewise unscoped (any conversation). Own-edit path `ChatRepository.kt:370` legitimately targets own message. | DB-level scoping (`server_id AND sender_id AND conv_id` for revise; `conv_id` for reactions) + repo-level checks + pure guard predicate with malicious-peer unit tests. | Planned |
| F2 | HIGH | **Confirmed** | Policy `20261019000001_init.sql:334-335` returns all columns to co-members; grants are table-level (`0001:324`, `0002:76`, verified live via `information_schema` 2026-10-21); `net/SupabaseApi.kt:211` selects `last_seen_at`, `parseProfile` hides client-side only; `docs/PRIVACY.md` says hidden "completely". | Column-scoped SELECT grant (revoke table SELECT, grant cols minus `last_seen_at`), `get_presence(uuid)` SECURITY DEFINER honoring `last_seen_public` + shared-conversation, client switched to RPC, transactional RLS test. Note: POST/PATCH `profiles` with `return=representation` (`SupabaseApi.kt:222,230`) must gain explicit `select=` or the revoke breaks them. **0.4.0 clients must upgrade** (their `fetchProfile` selects the revoked column). | Planned |
| F3 | HIGH | **Confirmed** | Trigger `20261019000004_review_fixes.sql:27-33` keeps e2ee ciphertext on delete (by design, ratchet); `ChatRepository.kt:775-786` `purgeExpired` only soft-deletes; nothing hard-deletes; PRIVACY.md "the body is removed" is wrong for secret chats. `pg_cron 1.6.4` is available but not installed (verified live). | (a) Correct PRIVACY/TERMS now. (b) `retention_purge(days, include_plain=false)` SECURITY DEFINER (execute revoked from clients) hard-deleting >30-day rows where `deleted_at` set OR all other members read; plain (`kind='text'`) excluded by default; pg_cron schedule guarded so migration can't fail if extension creation is denied; GH-Action fallback documented (needs a service-role secret → MANUAL STEPS). (c) DB-size queries + thresholds in `docs/OPERATIONS.md`. | Planned |
| F4 | MED | **Confirmed** | `security/AppLock.kt:44-55` 5000×SHA-256; `ui/LockActivity.kt:26-27` counters in Activity, `:70` min PIN 4, `:116-125` `BiometricPrompt.authenticate(CancellationSignal, …)` — no CryptoObject. | Persist attempts+lockout (exponential) in SecureStore; PBKDF2-HMAC-SHA256 PIN hash (versioned, migrates v1 hash on first successful verify); min 6 for new/changed PINs (existing PINs keep working); document UI-gate nature in settings text. CryptoObject-bound biometrics: evaluated, deferred (requires Keystore auth-bound key redesign; risk > benefit for a UI gate — see Remaining). | Planned |
| F5 | MED | **Confirmed** | `storage/SecureStore.kt:28-36` decrypt failure → null; `crypto/IdentityManager.kt:32-40` comment "fall through and regenerate" then persists new key; `ensureIdentityUploaded` publishes it. `docs/APP_REVIEW.md` already flagged it. | `SecureStore.secretState()` distinguishes missing/unreadable; `IdentityManager.identityStatus()`; `ensureIdentity()` throws `IdentityCorruptedException` instead of regenerating when stored-but-unreadable; repo refuses to publish in that state; blocking recovery dialog in MainActivity (restore from backup / consciously create new identity via explicit `forceRegenerate()`). | Planned |
| F6 | MED | **Confirmed** | `ChatRepository.kt:1351` backup prefixes include `session:` + `kxaccept:`; restore applies them (`:579`). | Restore allow-list narrows to `identity_*`, `pin:*`, `ttl:*`; when a restore touches identity, existing `session:`/`kxaccept:` are wiped → fresh signed KX. Backup *creation* keeps V1-compatible content, restore ignores session keys (documented). Unit test on the filter predicate. | Planned |
| F7 | MED | **Confirmed** (with deviation) | `crypto/SecureBackup.kt:18,59` 210k hard-coded; `Crypto.pbkdf2Sha256` calls `hmacSha256` per iteration which does `Mac.getInstance` per call (`Crypto.kt:85`); min passphrase 8 (`SecureBackup.encrypt`, `SettingsActivity.kt:385`, repo check). | Single-`Mac` PBKDF2; **MAMOOT-BACKUP-V2** embeds iteration count (600k), V1 decrypt retained; new backups require ≥12 chars + strength hint; create/restore show progress (already on IO thread). RFC vector kept + V2 tests. Native `SecretKeyFactory` rejected — see deviation note above. | Planned |
| F8 | MED | **Confirmed, mechanism corrected** | Brief says `created_at = clock_timestamp()`; actually `created_at timestamptz not null default now()` (`0001` messages table) — `now()` is *transaction start*, but PostgREST inserts are single-statement so skew is small; the race is still real across concurrent writers/RPCs. Cursor: `net/SupabaseApi.kt:420-424` `created_at=gte.<wm>`; `processRow` idempotent via `getByServerId` (`ChatRepository.kt:917`). | Rewind watermark by 30 s before fetch (`rewindWatermark` pure fn + unit test); edits watermark rewinds likewise (reconcile is idempotent). Long-term monotonic sequence documented as future option. | Planned |
| F9 | MED | **Confirmed** | `syncInternal` REST calls (steady state, DM): messages-after (`:696`), edits/deletes (`:803`), reactions (`:1180`), typing (`:719`), presence = `fetchPresence`→un-cached `fetchProfile` (`:725`), member reads (`:745`), + throttled touch (`:1117`) + conditional markRead (`:735`) ≈ 6–8/poll; chat poll 2.5 s→10 s idle; `MainActivity` 12 s `list_conversations` incl. ~120-char keys/member; `sync/BackgroundCheckJob.kt:32` full list every 15 min; typing upsert ≤4 s; `replica identity full` `0001:382-383`. | (1) `sync_conversation()` RPC (messages+changes+reads+typing+presence in 1 call) with legacy fallback when RPC absent; (2) presence from RPC (legacy path keeps profile cache); (3) `unread_summary()` for bg job; (4) `list_conversations(p_include_keys boolean default true)` — new client passes false, old clients unchanged (`startSecretChat` already force-fetches the profile, `ChatRepository.kt` startSecretChat); (7) drop `replica identity full`. (5)(6) Realtime/Broadcast: **evaluated, deferred to 0.5** — see Realtime evaluation below. | Planned (partial) |
| F10 | MED | **Confirmed** | `sync/BackgroundCheckJob.kt:35-43` compares unread *counts*. | Compare newest unread `created_at`/id from `unread_summary()` (stored as string); count kept only as display fallback. Legacy fallback to count logic if RPC missing. | Planned |
| F11 | MED | **Confirmed, one sub-point outdated** | `create_group` (`0001:191-215`) adds ≤99 with no consent/block check; `add_group_members` (`0001:217-240`) any member, no role/block check; no remove RPC; `search_users` (`0003:23`) unlimited. **Correction:** `get_or_create_dm` already rejects blocked pairs since migration 4 (`0004:148-183`) — but still no consent/rate cap. Blocks not enforced for group adds. | `api_rate_limits` table + definer-internal limiter; daily caps (new DMs 20/day, groups 5/day), block-aware group create/add (blocked pairs silently skipped), owner-only `add_group_members`, new `remove_group_member()` (owner-only) + minimal UI, `search_users` 30/min cap. Full message-request/accept state deferred (documented). Transactional RLS tests. | Planned (partial) |
| F12 | MED | **Confirmed** | `crypto/SecretSession.kt:338-353` falls back to `legacyAad` (`:404`) for every envelope, forever. | **Removed in 0.4.1.** Already-read history is local plaintext (unaffected); *unread* secret envelopes sent by pre-0.4.0 builds will no longer decrypt. Justified: pre-launch, tiny base; documented in changelog. | Planned |
| F13 | MED | **Confirmed** | Helpers `0001:77-94` call `auth.uid()` unwrapped (re-evaluated per row); unread filter `body like 'MAM1.k%'/'MAM1.c%'` `0004:83`, `0005:93`. | `messages.is_control` set by trigger + backfill + partial index; `list_conversations`/`sync_conversation` use it; helpers rewritten with `(select auth.uid())`; EXPLAIN ANALYZE before/after in this file. | Planned |
| F14 | LOW | **Confirmed** | `net/SupabaseApi.kt:590` `mamoot-android/0.3.2`; `:640` `m.trim() == "blocked" \|\| "blocked" in m` (the substring arm makes the equality arm dead and over-matches). BuildConfig already generated (`buildConfig = true`). | `BuildConfig.VERSION_NAME`; token matching only for server-raised tokens (equality/prefix on trimmed message), keep substring only for GoTrue prose. | Planned |
| F15 | LOW | **Mostly already fixed** | Permanent keystore has been in GitHub secrets since v0.3.1; every shipped APK since then verifies signer `ca7ce0ee…cfa113` (`apk-latest/VERIFICATION.txt`). Ephemeral fallback in `.github/workflows/android.yml` only triggers if the secret is *absent*. **Still valid:** logs step uses `if: always()` and writes log files to a public branch (disk files bypass Actions masking). | No keystore work needed (offline backup = owner's downloaded setup file — restate in MANUAL STEPS). Change log publishing to `if: failure()`; verified no secret values are echoed by the logged steps (keystore handled via env, never printed). | Planned (CI part) |
| F16 | LOW | **Confirmed** | `scripts/verify.mjs` header: runs on production, creates throwaway users. | `supabase/tests/rls_tests.sql` (transactional, rollback) added and executed via connector; staging project (2nd free slot) + CI wiring listed under MANUAL STEPS (requires owner to add GitHub secrets; agent token cannot manage Actions secrets — verified 403 in v0.3.1 work). pgTAP 1.3.3 is available on the project (verified live) for future `supabase test db`. | Partial |
| F17 | LOW | **Confirmed** | `src/App.tsx:9,266` renders `ChatWindow`; components incl. `ConversationList`, `NewChatModal`, `SettingsModal`; secret chats show ciphertext on web. | Reduce web to auth + password-reset + legal links; remove chat components; keep strict CSP (verify `vercel.json`); note Cloudflare Pages fallback if commercial. | Planned |
| F18 | LOW | **Confirmed (dashboard)** | Live auth config (2026-10-21): `password_min_length=6`, `mailer_autoconfirm=true` (confirmation effectively off), `smtp_*` all null (built-in SMTP, `rate_limit_email_sent=2`/hr), `password_hibp_enabled=false`, redirect allowlist includes `http://localhost:5173/**`. | MANUAL STEPS only (connector's auth-config tool cannot set these fields). | Manual |
| F19 | LOW | **Confirmed** | Pricing page: free projects pause after 1 week idle. | Scheduled keep-alive workflow (public publishable key only); docs state plainly that a paid plan is the real pre-launch fix. | Planned |
| F20 | LOW | **Confirmed** | PRIVACY.md claims vs F2/F3 above; in-app legal text mirrors them (`SettingsActivity` legal dialog). | Re-audit every claim after F2/F3 land; onboarding line added (one phone per account for secret chats; backup = only recovery). | Planned |

### Also-review items (from brief)

- `fetchReactions` URL length: confirmed unbounded `in.(...)` — chunked to 40 ids/request in 0.4.1.
- `applyReactionLocal` validation: covered by F1.
- Android 15 edge-to-edge/insets, RTL/string-resource polish, TalkBack/font
  scaling, very-large-list memory: **not verified** in this pass (needs a
  device/emulator). Code observations only: chat list is paginated
  (`PAGE_SIZE=100`, prepend paging exists); crypto/backup/PIN work runs on
  the IO executor; layouts use start/end attributes. Left in Remaining.

### Realtime evaluation (F9.5/F9.6) — deferred to 0.5, decision recorded

Options: (a) hand-written RFC 6455 + Phoenix channels client (~600+ lines of
new network parser = new attack surface, must handle masking, fragmentation,
ping/pong, TLS via `SSLSocket`, heartbeat/rejoin) vs (b) OkHttp dependency
(~300 KB+, pulls Okio; mature WS + TLS; breaks the zero-dependency rule but
removes hand-rolled socket risk). Either way Realtime would be a *hint* only
(polling remains source of truth), foreground-only, ≤200 concurrent
connections on Free. Blocking unknowns that cannot be resolved from this
sandbox: `wss://` reachability/stability on Iranian mobile networks.
With the RPC consolidation (F9.1) the foreground cost drops ~6× (see
Measurements), so Realtime is an optimization, not a correctness need.
Decision: implement in 0.5 after on-device network tests in Iran, preferring
OkHttp (justification: audited TLS/WS stack vs hand-rolled parser) if the
zero-dependency rule is relaxed by the owner, else the minimal client behind
the same "hint" interface. `typing_status` table is kept until then (writes
already throttled to ≤1/4 s per composing user).

## Measurements (before/after)

_To be filled with measured payloads from transactional test data._

## EXPLAIN ANALYZE (F13)

_To be filled._

## MANUAL STEPS (dashboards — cannot be done from the repo)

_To be filled at delivery._

## Status summary

_To be updated at delivery: fixed / partially fixed / remaining + biggest risk._
