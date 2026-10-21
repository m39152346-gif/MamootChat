-- MamootChat — initial schema
-- Security model: RLS everywhere, no anon access, writes only as yourself,
-- conversations are created exclusively through SECURITY DEFINER RPCs,
-- messages can be soft-deleted only (hard DELETE has no policy at all).

create extension if not exists pg_trgm with schema public;

-- ---------------------------------------------------------------- profiles
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  username text not null unique check (username ~ '^[a-z0-9_.]{3,24}$'),
  display_name text not null check (char_length(display_name) between 1 and 50),
  created_at timestamptz not null default now()
);

create index profiles_username_trgm_idx on public.profiles using gin (username gin_trgm_ops);
create index profiles_display_name_trgm_idx on public.profiles using gin (display_name gin_trgm_ops);

create or replace function public.normalize_profile()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.username := lower(trim(new.username));
  new.display_name := trim(new.display_name);
  return new;
end $$;

create trigger profiles_normalize
  before insert or update on public.profiles
  for each row execute function public.normalize_profile();

-- ---------------------------------------------------------------- conversations
create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('dm', 'group')),
  title text check (title is null or char_length(title) between 1 and 80),
  created_by uuid not null references public.profiles (id) on delete cascade,
  dm_key text unique,
  created_at timestamptz not null default now(),
  last_message_at timestamptz not null default now()
);

create index conversations_last_message_idx on public.conversations (last_message_at desc);

create table public.conversation_members (
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  last_read_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create index conversation_members_user_idx on public.conversation_members (user_id, conversation_id);

-- ---------------------------------------------------------------- messages (text only)
create table public.messages (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  sender_id uuid not null references public.profiles (id) on delete cascade,
  body text not null check (
    char_length(body) <= 4000
    and (deleted_at is not null or char_length(trim(body)) >= 1)
  ),
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_at timestamptz,
  unique (sender_id, client_id)
);

create index messages_conversation_timeline_idx
  on public.messages (conversation_id, created_at desc, id desc);
create index messages_sender_rate_idx
  on public.messages (sender_id, created_at desc);

-- ---------------------------------------------------------------- helpers (RLS, recursion-safe)
create or replace function public.is_conversation_member(p_conversation uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members cm
    where cm.conversation_id = p_conversation and cm.user_id = auth.uid()
  )
$$;

create or replace function public.shares_conversation_with(p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.conversation_members mine
    join public.conversation_members theirs
      on theirs.conversation_id = mine.conversation_id
    where mine.user_id = auth.uid() and theirs.user_id = p_user
  )
$$;

-- ---------------------------------------------------------------- message triggers
create or replace function public.guard_message_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_recent int;
begin
  new.body := trim(new.body);
  if char_length(new.body) = 0 then
    raise exception 'empty_message' using errcode = 'P0001';
  end if;
  -- Anti-spam: max 30 messages / 60s per sender (index-backed count).
  select count(*) into v_recent
  from public.messages m
  where m.sender_id = new.sender_id
    and m.created_at > now() - interval '60 seconds';
  if v_recent >= 30 then
    raise exception 'rate_limit_exceeded' using errcode = 'P0001';
  end if;
  return new;
end $$;

create trigger messages_guard_insert
  before insert on public.messages
  for each row execute function public.guard_message_insert();

create or replace function public.touch_conversation()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.conversations
  set last_message_at = new.created_at
  where id = new.conversation_id and last_message_at < new.created_at;
  return new;
end $$;

create trigger messages_touch_conversation
  after insert on public.messages
  for each row execute function public.touch_conversation();

create or replace function public.guard_message_update()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.deleted_at is not null then
    raise exception 'message_deleted' using errcode = 'P0001';
  end if;
  if new.id <> old.id
     or new.conversation_id <> old.conversation_id
     or new.sender_id <> old.sender_id
     or new.client_id <> old.client_id
     or new.created_at <> old.created_at then
    raise exception 'immutable_field' using errcode = 'P0001';
  end if;
  if new.deleted_at is not null then
    new.deleted_at := now();
    new.body := '';
    new.edited_at := old.edited_at;
  else
    new.body := trim(new.body);
    if char_length(new.body) = 0 then
      raise exception 'empty_message' using errcode = 'P0001';
    end if;
    new.edited_at := now();
  end if;
  return new;
end $$;

create trigger messages_guard_update
  before update on public.messages
  for each row execute function public.guard_message_update();

-- ---------------------------------------------------------------- RPCs
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
  v_key := least(v_me, p_other)::text || ':' || greatest(v_me, p_other)::text;
  insert into public.conversations (kind, created_by, dm_key)
  values ('dm', v_me, v_key)
  on conflict (dm_key) do nothing
  returning id into v_id;
  if v_id is null then
    select id into v_id from public.conversations where dm_key = v_key;
  end if;
  insert into public.conversation_members (conversation_id, user_id)
  values (v_id, v_me), (v_id, p_other)
  on conflict do nothing;
  return v_id;
end $$;

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
  insert into public.conversations (kind, title, created_by)
  values ('group', v_title, v_me) returning id into v_id;
  insert into public.conversation_members (conversation_id, user_id, role)
  values (v_id, v_me, 'owner');
  insert into public.conversation_members (conversation_id, user_id)
  select v_id, unnest(v_members) on conflict do nothing;
  return v_id;
end $$;

create or replace function public.add_group_members(p_conversation uuid, p_user_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := auth.uid(); v_kind text; v_total int; v_new uuid[];
begin
  select kind into v_kind from public.conversations where id = p_conversation;
  if v_kind is null or v_kind <> 'group' then
    raise exception 'not_a_group' using errcode = 'P0001';
  end if;
  if not exists (select 1 from public.conversation_members
                 where conversation_id = p_conversation and user_id = v_me) then
    raise exception 'not_a_member' using errcode = 'P0001';
  end if;
  select coalesce(array_agg(distinct u), '{}') into v_new
  from unnest(coalesce(p_user_ids, '{}')) as u;
  if (select count(*) from public.profiles where id = any(v_new)) <> coalesce(array_length(v_new, 1), 0) then
    raise exception 'user_not_found' using errcode = 'P0001';
  end if;
  select count(*) into v_total from public.conversation_members where conversation_id = p_conversation;
  if v_total + coalesce(array_length(v_new, 1), 0) > 100 then
    raise exception 'group_full' using errcode = 'P0001';
  end if;
  insert into public.conversation_members (conversation_id, user_id)
  select p_conversation, unnest(v_new) on conflict do nothing;
end $$;

create or replace function public.leave_conversation(p_conversation uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_kind text;
begin
  select kind into v_kind from public.conversations where id = p_conversation;
  if v_kind is distinct from 'group' then
    raise exception 'cannot_leave' using errcode = 'P0001';
  end if;
  delete from public.conversation_members
  where conversation_id = p_conversation and user_id = auth.uid();
end $$;

create or replace function public.mark_read(p_conversation uuid)
returns void language sql security definer set search_path = '' as $$
  update public.conversation_members
  set last_read_at = now()
  where conversation_id = p_conversation and user_id = auth.uid()
$$;

create or replace function public.search_users(p_query text, p_limit int default 20)
returns table (id uuid, username text, display_name text)
language plpgsql stable security definer set search_path = '' as $$
declare v_q text := trim(coalesce(p_query, '')); v_esc text; v_lim int;
begin
  if auth.uid() is null or char_length(v_q) < 2 then return; end if;
  v_esc := replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_');
  v_lim := least(greatest(coalesce(p_limit, 20), 1), 20);
  return query
    select p.id, p.username, p.display_name
    from public.profiles p
    where p.id <> auth.uid()
      and (p.username ilike '%' || v_esc || '%' escape '\'
           or p.display_name ilike '%' || v_esc || '%' escape '\')
    order by (p.username ilike v_esc || '%' escape '\') desc, p.username asc
    limit v_lim;
end $$;

create or replace function public.list_conversations()
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
        'role', m2.role
      ) order by p.display_name)
      from public.conversation_members m2
      join public.profiles p on p.id = m2.user_id
      where m2.conversation_id = c.id
    ), '[]'::jsonb),
    (
      select jsonb_build_object(
        'id', lm.id, 'body', lm.body, 'sender_id', lm.sender_id,
        'created_at', lm.created_at, 'deleted_at', lm.deleted_at,
        'edited_at', lm.edited_at
      )
      from public.messages lm
      where lm.conversation_id = c.id
      order by lm.created_at desc, lm.id desc limit 1
    ),
    (
      select count(*) from public.messages um
      where um.conversation_id = c.id
        and um.sender_id <> auth.uid()
        and um.created_at > me.last_read_at
        and um.deleted_at is null
    ),
    me.last_read_at
  from public.conversations c
  join public.conversation_members me
    on me.conversation_id = c.id and me.user_id = auth.uid()
  order by c.last_message_at desc
$$;

-- ---------------------------------------------------------------- grants + RLS
revoke all on public.profiles, public.conversations, public.conversation_members, public.messages from public, anon;
grant select, insert, update on public.profiles to authenticated;
grant select on public.conversations to authenticated;
grant select on public.conversation_members to authenticated;
grant select, insert, update on public.messages to authenticated;

alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages enable row level security;

create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or public.shares_conversation_with(id));
create policy profiles_insert on public.profiles for insert to authenticated
  with check (id = auth.uid());
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

create policy conversations_select on public.conversations for select to authenticated
  using (public.is_conversation_member(id));

create policy members_select on public.conversation_members for select to authenticated
  using (public.is_conversation_member(conversation_id));

create policy messages_select on public.messages for select to authenticated
  using (public.is_conversation_member(conversation_id));
create policy messages_insert on public.messages for insert to authenticated
  with check (sender_id = auth.uid() and public.is_conversation_member(conversation_id));
create policy messages_update on public.messages for update to authenticated
  using (sender_id = auth.uid() and public.is_conversation_member(conversation_id))
  with check (sender_id = auth.uid() and public.is_conversation_member(conversation_id));
-- intentionally NO delete policy anywhere: hard-deletes are impossible for clients.

revoke all on function public.normalize_profile() from public, anon, authenticated;
revoke all on function public.guard_message_insert() from public, anon, authenticated;
revoke all on function public.guard_message_update() from public, anon, authenticated;
revoke all on function public.touch_conversation() from public, anon, authenticated;

revoke all on function public.is_conversation_member(uuid) from public, anon;
revoke all on function public.shares_conversation_with(uuid) from public, anon;
revoke all on function public.get_or_create_dm(uuid) from public, anon;
revoke all on function public.create_group(text, uuid[]) from public, anon;
revoke all on function public.add_group_members(uuid, uuid[]) from public, anon;
revoke all on function public.leave_conversation(uuid) from public, anon;
revoke all on function public.mark_read(uuid) from public, anon;
revoke all on function public.search_users(text, int) from public, anon;
revoke all on function public.list_conversations() from public, anon;

grant execute on function public.is_conversation_member(uuid) to authenticated;
grant execute on function public.shares_conversation_with(uuid) to authenticated;
grant execute on function public.get_or_create_dm(uuid) to authenticated;
grant execute on function public.create_group(text, uuid[]) to authenticated;
grant execute on function public.add_group_members(uuid, uuid[]) to authenticated;
grant execute on function public.leave_conversation(uuid) to authenticated;
grant execute on function public.mark_read(uuid) to authenticated;
grant execute on function public.search_users(text, int) to authenticated;
grant execute on function public.list_conversations() to authenticated;

-- ---------------------------------------------------------------- realtime (RLS-filtered)
alter table public.messages replica identity full;
alter table public.conversation_members replica identity full;
alter publication supabase_realtime add table public.messages;
alter publication supabase_realtime add table public.conversation_members;
