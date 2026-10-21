-- Mamoot v0.4.1 — audit-driven hardening (see AUDIT.md):
--   F2  server-enforced last-seen privacy (column grant + get_presence RPC)
--   F3  retention_purge(): hard-delete old tombstones / fully-read E2EE rows
--   F9  sync_conversation() + unread_summary() RPCs, slimmer list option,
--       drop REPLICA IDENTITY FULL
--   F11 rate limits (DM/group creation, user search), owner-only group
--       add/remove, block-aware group membership
--   F13 messages.is_control (+trigger, backfill, partial index),
--       (select auth.uid()) in RLS helper functions
-- Idempotent: safe to re-run.

-- ============================================================ F13 helpers
create or replace function public.is_conversation_member(p_conversation uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members cm
    where cm.conversation_id = p_conversation and cm.user_id = (select auth.uid())
  )
$$;

create or replace function public.shares_conversation_with(p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.conversation_members mine
    join public.conversation_members theirs
      on theirs.conversation_id = mine.conversation_id
    where mine.user_id = (select auth.uid()) and theirs.user_id = p_user
  )
$$;

create or replace function public.is_message_member(p_message uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.messages m
    join public.conversation_members cm
      on cm.conversation_id = m.conversation_id and cm.user_id = (select auth.uid())
    where m.id = p_message
  )
$$;

-- ============================================================ F13 is_control
alter table public.messages add column if not exists is_control boolean not null default false;

create or replace function public.mark_message_control()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.is_control := new.kind = 'e2ee'
    and (new.body like 'MAM1.k%' or new.body like 'MAM1.c%');
  return new;
end $$;

revoke all on function public.mark_message_control() from public, anon, authenticated;

drop trigger if exists messages_mark_control on public.messages;
create trigger messages_mark_control
  before insert or update of body, kind on public.messages
  for each row execute function public.mark_message_control();

-- Backfill without tripping guard_message_update (it would stamp edited_at).
alter table public.messages disable trigger messages_guard_update;
update public.messages
set is_control = (kind = 'e2ee' and (body like 'MAM1.k%' or body like 'MAM1.c%'))
where is_control is distinct from (kind = 'e2ee' and (body like 'MAM1.k%' or body like 'MAM1.c%'));
alter table public.messages enable trigger messages_guard_update;

create index if not exists messages_unread_idx
  on public.messages (conversation_id, created_at)
  where deleted_at is null and not is_control;

-- ============================================================ F2 presence
-- Table-level SELECT currently exposes last_seen_at to any co-member; RLS
-- cannot mask columns. Replace it with a column-scoped grant that omits
-- last_seen_at (writes via UPDATE are unaffected; clients must name the
-- columns they return on INSERT/UPDATE ... RETURNING).
revoke select on public.profiles from authenticated;
grant select (id, username, display_name, created_at, e2e_identity_key, last_seen_public)
  on public.profiles to authenticated;

create or replace function public.get_presence(p_user uuid)
returns table (last_seen_at timestamptz, last_seen_public boolean)
language sql stable security definer set search_path = '' as $$
  select
    case when p.last_seen_public then p.last_seen_at else null end,
    p.last_seen_public
  from public.profiles p
  where p.id = p_user
    and ((select auth.uid()) = p_user or public.shares_conversation_with(p_user))
$$;

revoke all on function public.get_presence(uuid) from public, anon;
grant execute on function public.get_presence(uuid) to authenticated;

-- ============================================================ F11 rate limits
create table if not exists public.api_rate_limits (
  user_id uuid not null references public.profiles (id) on delete cascade,
  action text not null,
  window_start timestamptz not null,
  hits int not null default 0,
  primary key (user_id, action, window_start)
);

alter table public.api_rate_limits enable row level security;
revoke all on public.api_rate_limits from public, anon, authenticated;

-- Internal limiter: only SECURITY DEFINER functions (running as owner) may
-- call it; clients have no EXECUTE grant and no table access.
create or replace function public.check_rate_limit(p_action text, p_max int, p_window interval)
returns boolean language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := auth.uid();
  v_ws timestamptz;
  v_hits int;
begin
  if v_me is null then return false; end if;
  v_ws := date_bin(p_window, clock_timestamp(), '2000-01-01'::timestamptz);
  insert into public.api_rate_limits (user_id, action, window_start, hits)
  values (v_me, p_action, v_ws, 1)
  on conflict (user_id, action, window_start)
  do update set hits = public.api_rate_limits.hits + 1
  returning hits into v_hits;
  delete from public.api_rate_limits
  where user_id = v_me and action = p_action and window_start < v_ws;
  return v_hits <= p_max;
end $$;

revoke all on function public.check_rate_limit(text, int, interval) from public, anon, authenticated;

-- DM creation: daily cap on NEW conversations (existing DMs never limited).
create or replace function public.get_or_create_dm(p_other uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_me uuid := auth.uid(); v_key text; v_id uuid;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = 'P0001'; end if;
  if p_other = v_me then raise exception 'cannot_dm_self' using errcode = 'P0001'; end if;
  if not exists (select 1 from public.profiles where id = v_me) then
    raise exception 'profile_required' using errcode = 'P0001';
  end if;
  if not exists (select 1 from public.profiles where id = p_other) then
    raise exception 'user_not_found' using errcode = 'P0001';
  end if;
  if exists (
    select 1 from public.blocks
    where (blocker = v_me and blocked = p_other)
       or (blocker = p_other and blocked = v_me)
  ) then
    raise exception 'blocked' using errcode = 'P0001';
  end if;
  v_key := least(v_me, p_other)::text || ':' || greatest(v_me, p_other)::text;
  select id into v_id from public.conversations where dm_key = v_key;
  if v_id is null then
    if not public.check_rate_limit('dm_create', 20, interval '1 day') then
      raise exception 'dm_rate_limited' using errcode = 'P0001';
    end if;
    insert into public.conversations (kind, created_by, dm_key)
    values ('dm', v_me, v_key)
    on conflict (dm_key) do nothing
    returning id into v_id;
    if v_id is null then
      select id into v_id from public.conversations where dm_key = v_key;
    end if;
  end if;
  insert into public.conversation_members (conversation_id, user_id)
  values (v_id, v_me), (v_id, p_other)
  on conflict do nothing;
  return v_id;
end $$;

-- Group creation: daily cap + block-aware member filter.
create or replace function public.create_group(p_title text, p_member_ids uuid[])
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := auth.uid(); v_title text := trim(p_title); v_id uuid; v_members uuid[];
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = 'P0001'; end if;
  if char_length(v_title) < 1 or char_length(v_title) > 80 then
    raise exception 'invalid_title' using errcode = 'P0001';
  end if;
  select coalesce(array_agg(distinct m), '{}') into v_members
  from unnest(coalesce(p_member_ids, '{}')) as m where m <> v_me;
  if coalesce(array_length(v_members, 1), 0) < 1 or array_length(v_members, 1) > 99 then
    raise exception 'invalid_member_count' using errcode = 'P0001';
  end if;
  if (select count(*) from public.profiles where id = any(v_members)) <> array_length(v_members, 1) then
    raise exception 'user_not_found' using errcode = 'P0001';
  end if;
  -- Block-aware: silently skip users in a block relationship with the creator.
  select coalesce(array_agg(m), '{}') into v_members
  from unnest(v_members) as m
  where not exists (
    select 1 from public.blocks b
    where (b.blocker = v_me and b.blocked = m) or (b.blocker = m and b.blocked = v_me)
  );
  if coalesce(array_length(v_members, 1), 0) < 1 then
    raise exception 'invalid_member_count' using errcode = 'P0001';
  end if;
  if not public.check_rate_limit('group_create', 5, interval '1 day') then
    raise exception 'group_rate_limited' using errcode = 'P0001';
  end if;
  insert into public.conversations (kind, title, created_by)
  values ('group', v_title, v_me) returning id into v_id;
  insert into public.conversation_members (conversation_id, user_id, role)
  values (v_id, v_me, 'owner');
  insert into public.conversation_members (conversation_id, user_id)
  select v_id, unnest(v_members) on conflict do nothing;
  return v_id;
end $$;

-- Group add: owner-only + block-aware.
create or replace function public.add_group_members(p_conversation uuid, p_user_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := auth.uid(); v_kind text; v_total int; v_new uuid[];
begin
  select kind into v_kind from public.conversations where id = p_conversation;
  if v_kind is null or v_kind <> 'group' then
    raise exception 'not_a_group' using errcode = 'P0001';
  end if;
  if not exists (select 1 from public.conversation_members
                 where conversation_id = p_conversation and user_id = v_me and role = 'owner') then
    raise exception 'not_group_owner' using errcode = 'P0001';
  end if;
  select coalesce(array_agg(distinct u), '{}') into v_new
  from unnest(coalesce(p_user_ids, '{}')) as u;
  if (select count(*) from public.profiles where id = any(v_new)) <> coalesce(array_length(v_new, 1), 0) then
    raise exception 'user_not_found' using errcode = 'P0001';
  end if;
  select coalesce(array_agg(u), '{}') into v_new
  from unnest(v_new) as u
  where not exists (
    select 1 from public.blocks b
    where (b.blocker = v_me and b.blocked = u) or (b.blocker = u and b.blocked = v_me)
  );
  select count(*) into v_total from public.conversation_members where conversation_id = p_conversation;
  if v_total + coalesce(array_length(v_new, 1), 0) > 100 then
    raise exception 'group_full' using errcode = 'P0001';
  end if;
  insert into public.conversation_members (conversation_id, user_id)
  select p_conversation, unnest(v_new) on conflict do nothing;
end $$;

-- Group remove: owner-only; the owner cannot be removed.
create or replace function public.remove_group_member(p_conversation uuid, p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := auth.uid(); v_kind text; v_role text;
begin
  select kind into v_kind from public.conversations where id = p_conversation;
  if v_kind is null or v_kind <> 'group' then
    raise exception 'not_a_group' using errcode = 'P0001';
  end if;
  if not exists (select 1 from public.conversation_members
                 where conversation_id = p_conversation and user_id = v_me and role = 'owner') then
    raise exception 'not_group_owner' using errcode = 'P0001';
  end if;
  select role into v_role from public.conversation_members
  where conversation_id = p_conversation and user_id = p_user;
  if v_role is null then
    raise exception 'not_a_member' using errcode = 'P0001';
  end if;
  if v_role = 'owner' then
    raise exception 'cannot_remove_owner' using errcode = 'P0001';
  end if;
  delete from public.conversation_members
  where conversation_id = p_conversation and user_id = p_user;
end $$;

revoke all on function public.remove_group_member(uuid, uuid) from public, anon;
grant execute on function public.remove_group_member(uuid, uuid) to authenticated;

-- User search: 30/min per user (enumeration brake). Now VOLATILE (limiter writes).
drop function if exists public.search_users(text, int);
create function public.search_users(p_query text, p_limit int default 20)
returns table (id uuid, username text, display_name text, e2e_identity_key text)
language plpgsql security definer set search_path = '' as $$
declare v_q text := trim(coalesce(p_query, '')); v_esc text; v_lim int;
begin
  if auth.uid() is null or char_length(v_q) < 2 then return; end if;
  if not public.check_rate_limit('search', 30, interval '1 minute') then
    raise exception 'search_rate_limited' using errcode = 'P0001';
  end if;
  v_esc := replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_');
  v_lim := least(greatest(coalesce(p_limit, 20), 1), 20);
  return query
    select p.id, p.username, p.display_name, p.e2e_identity_key
    from public.profiles p
    where p.id <> (select auth.uid())
      and (p.username ilike '%' || v_esc || '%' escape '\'
           or p.display_name ilike '%' || v_esc || '%' escape '\')
    order by (p.username ilike v_esc || '%' escape '\') desc, p.username asc
    limit v_lim;
end $$;

revoke all on function public.search_users(text, int) from public, anon;
grant execute on function public.search_users(text, int) to authenticated;

-- ============================================================ F9 sync RPCs
create or replace function public.unread_summary()
returns table (total_unread bigint, newest_unread_at timestamptz, top_conversation uuid)
language sql stable security definer set search_path = '' as $$
  with unread as (
    select m.conversation_id, m.created_at
    from public.messages m
    join public.conversation_members me
      on me.conversation_id = m.conversation_id and me.user_id = (select auth.uid())
    where m.sender_id <> (select auth.uid())
      and m.created_at > me.last_read_at
      and m.deleted_at is null
      and not m.is_control
  )
  select
    (select count(*) from unread),
    (select max(created_at) from unread),
    (select conversation_id from unread group by conversation_id order by count(*) desc limit 1)
$$;

revoke all on function public.unread_summary() from public, anon;
grant execute on function public.unread_summary() to authenticated;

create or replace function public.sync_conversation(p_conv uuid, p_after timestamptz, p_edit_after timestamptz)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  v_me uuid := auth.uid();
  v_msgs jsonb;
  v_changes jsonb;
  v_reads jsonb;
  v_typing timestamptz;
  v_presence jsonb;
begin
  if v_me is null or not exists (
    select 1 from public.conversation_members
    where conversation_id = p_conv and user_id = v_me
  ) then
    raise exception 'not_a_member' using errcode = 'P0001';
  end if;

  select coalesce(jsonb_agg(obj order by created_at, id), '[]'::jsonb) into v_msgs
  from (
    select m.id, m.created_at, jsonb_build_object(
      'id', m.id, 'client_id', m.client_id, 'conversation_id', m.conversation_id,
      'sender_id', m.sender_id, 'body', m.body, 'kind', m.kind,
      'created_at', m.created_at, 'edited_at', m.edited_at, 'deleted_at', m.deleted_at,
      'reply_to_id', m.reply_to_id, 'forwarded', m.forwarded
    ) as obj
    from public.messages m
    where m.conversation_id = p_conv and m.created_at >= p_after
    order by m.created_at asc, m.id asc
    limit 200
  ) s;

  select coalesce(jsonb_agg(obj order by changed_at, id), '[]'::jsonb) into v_changes
  from (
    select m.id, greatest(m.edited_at, m.deleted_at) as changed_at, jsonb_build_object(
      'id', m.id, 'client_id', m.client_id, 'conversation_id', m.conversation_id,
      'sender_id', m.sender_id, 'body', m.body, 'kind', m.kind,
      'created_at', m.created_at, 'edited_at', m.edited_at, 'deleted_at', m.deleted_at,
      'reply_to_id', m.reply_to_id, 'forwarded', m.forwarded
    ) as obj
    from public.messages m
    where m.conversation_id = p_conv
      and (m.edited_at >= p_edit_after or m.deleted_at >= p_edit_after)
    order by greatest(m.edited_at, m.deleted_at) asc, m.id asc
    limit 200
  ) s;

  select coalesce(jsonb_object_agg(cm.user_id, cm.last_read_at), '{}'::jsonb) into v_reads
  from public.conversation_members cm
  where cm.conversation_id = p_conv;

  select max(t.typing_until) into v_typing
  from public.typing_status t
  where t.conversation_id = p_conv and t.user_id <> v_me;

  select coalesce(jsonb_object_agg(p.id, jsonb_build_object(
      'last_seen_at', case when p.last_seen_public then p.last_seen_at else null end,
      'last_seen_public', p.last_seen_public
    )), '{}'::jsonb) into v_presence
  from public.conversation_members cm
  join public.profiles p on p.id = cm.user_id
  where cm.conversation_id = p_conv and cm.user_id <> v_me;

  return jsonb_build_object(
    'messages', v_msgs,
    'changes', v_changes,
    'reads', v_reads,
    'typing_until', v_typing,
    'presence', v_presence
  );
end $$;

revoke all on function public.sync_conversation(uuid, timestamptz, timestamptz) from public, anon;
grant execute on function public.sync_conversation(uuid, timestamptz, timestamptz) to authenticated;

-- list_conversations: optional slim mode (no identity keys) for new clients;
-- default keeps the exact old shape so shipped clients are unaffected.
drop function if exists public.list_conversations();
create function public.list_conversations(p_include_keys boolean default true)
returns table (
  id uuid, kind text, title text, created_at timestamptz,
  last_message_at timestamptz, members jsonb, last_message jsonb,
  unread_count bigint, my_last_read_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select
    c.id, c.kind, c.title, c.created_at, c.last_message_at,
    coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', p.id, 'username', p.username,
        'display_name', p.display_name, 'last_read_at', m2.last_read_at,
        'role', m2.role,
        'e2e_identity_key', case when p_include_keys then p.e2e_identity_key else null end,
        'last_seen_at', case when p.last_seen_public then p.last_seen_at else null end,
        'last_seen_public', p.last_seen_public
      ) order by p.display_name)
      from public.conversation_members m2
      join public.profiles p on p.id = m2.user_id
      where m2.conversation_id = c.id
    ), '[]'::jsonb),
    (
      select jsonb_build_object(
        'id', lm.id, 'body', lm.body, 'sender_id', lm.sender_id,
        'created_at', lm.created_at, 'deleted_at', lm.deleted_at,
        'edited_at', lm.edited_at, 'kind', lm.kind,
        'reply_to_id', lm.reply_to_id, 'forwarded', lm.forwarded
      )
      from public.messages lm
      where lm.conversation_id = c.id
      order by lm.created_at desc, lm.id desc limit 1
    ),
    (
      select count(*) from public.messages um
      where um.conversation_id = c.id
        and um.sender_id <> (select auth.uid())
        and um.created_at > me.last_read_at
        and um.deleted_at is null
        and not um.is_control
    ),
    me.last_read_at
  from public.conversations c
  join public.conversation_members me
    on me.conversation_id = c.id and me.user_id = (select auth.uid())
  order by c.last_message_at desc
$$;

revoke all on function public.list_conversations(boolean) from public, anon;
grant execute on function public.list_conversations(boolean) to authenticated;

-- ============================================================ F9.7 WAL size
alter table public.messages replica identity default;
alter table public.conversation_members replica identity default;

-- ============================================================ F3 retention
-- Hard-deletes (in batches) rows older than p_days that are deleted OR have
-- been read by every other current member. By default only E2EE rows and
-- tombstones are eligible; plain-chat history sync is a product promise, so
-- p_include_plain stays false unless an operator opts in explicitly.
create or replace function public.retention_purge(p_days int default 30, p_include_plain boolean default false)
returns bigint language plpgsql security definer set search_path = '' as $$
declare
  v_cutoff timestamptz := clock_timestamp() - make_interval(days => greatest(coalesce(p_days, 30), 1));
  v_count bigint;
begin
  with doomed as (
    select m.id
    from public.messages m
    where m.created_at < v_cutoff
      and (coalesce(p_include_plain, false) or m.kind = 'e2ee' or m.deleted_at is not null)
      and (
        m.deleted_at is not null
        or not exists (
          select 1 from public.conversation_members cm
          where cm.conversation_id = m.conversation_id
            and cm.user_id <> m.sender_id
            and cm.last_read_at < m.created_at
        )
      )
    limit 5000
  ), del as (
    delete from public.messages where id in (select id from doomed) returning 1
  )
  select count(*) into v_count from del;

  delete from public.typing_status where typing_until < clock_timestamp() - interval '1 day';
  delete from public.api_rate_limits where window_start < clock_timestamp() - interval '7 days';
  return v_count;
end $$;

revoke all on function public.retention_purge(int, boolean) from public, anon, authenticated;

-- Schedule nightly retention when pg_cron is available; never fail the
-- migration if the extension cannot be created in this environment.
do $$
begin
  begin
    create extension if not exists pg_cron;
  exception when others then
    raise notice 'pg_cron unavailable (%); retention must be triggered externally', sqlerrm;
  end;
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    begin
      if exists (select 1 from cron.job where jobname = 'mamoot-retention') then
        perform cron.unschedule('mamoot-retention');
      end if;
      perform cron.schedule('mamoot-retention', '17 3 * * *', $job$select public.retention_purge(30, false)$job$);
    exception when others then
      raise notice 'pg_cron scheduling failed (%); retention must be triggered externally', sqlerrm;
    end;
  end if;
end $$;
