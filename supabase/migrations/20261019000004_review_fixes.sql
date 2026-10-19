-- Mamoot 0.3.0 — review fixes:
--  1) Deleted E2EE messages keep their ciphertext body server-side, so the
--     receiver can still advance the secret-chat ratchet past the tombstone
--     (previously body='' broke the sequence chain; 100+ such deletes locked
--     the chat forever). Plaintext kinds are still wiped. kind is now
--     immutable so plaintext can't be re-labelled to dodge the wipe.
--  2) Unread counts ignore invisible key-exchange/control envelopes
--     (phantom badges + notifications for handshakes).
--  3) User blocks (DM enforcement in insert trigger + get_or_create_dm).
--  4) delete_my_account() self-service account deletion.

-- ------------------------------------------------------------------ (1)
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
     or new.kind <> old.kind
     or new.created_at <> old.created_at then
    raise exception 'immutable_field' using errcode = 'P0001';
  end if;
  if new.deleted_at is not null then
    new.deleted_at := clock_timestamp();
    -- E2EE tombstones keep their (encrypted) envelope so receivers can
    -- consume the sequence number; plaintext bodies are wiped as before.
    if old.kind <> 'e2ee' then
      new.body := '';
    end if;
    new.edited_at := old.edited_at;
  else
    new.body := trim(new.body);
    if char_length(new.body) = 0 then
      raise exception 'empty_message' using errcode = 'P0001';
    end if;
    new.edited_at := clock_timestamp();
  end if;
  return new;
end $$;

revoke all on function public.guard_message_update() from public, anon, authenticated;

-- ------------------------------------------------------------------ (2)
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
        'role', m2.role, 'e2e_identity_key', p.e2e_identity_key
      ) order by p.display_name)
      from public.conversation_members m2
      join public.profiles p on p.id = m2.user_id
      where m2.conversation_id = c.id
    ), '[]'::jsonb),
    (
      select jsonb_build_object(
        'id', lm.id, 'body', lm.body, 'sender_id', lm.sender_id,
        'created_at', lm.created_at, 'deleted_at', lm.deleted_at,
        'edited_at', lm.edited_at, 'kind', lm.kind
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
        and not (um.kind = 'e2ee' and (um.body like 'MAM1.k%' or um.body like 'MAM1.c%'))
    ),
    me.last_read_at
  from public.conversations c
  join public.conversation_members me
    on me.conversation_id = c.id and me.user_id = auth.uid()
  order by c.last_message_at desc
$$;

revoke all on function public.list_conversations() from public, anon;
grant execute on function public.list_conversations() to authenticated;

-- ------------------------------------------------------------------ (3)
create table public.blocks (
  blocker uuid not null references public.profiles (id) on delete cascade,
  blocked uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default clock_timestamp(),
  primary key (blocker, blocked),
  check (blocker <> blocked)
);

alter table public.blocks enable row level security;

create policy blocks_select on public.blocks for select to authenticated
  using (blocker = auth.uid());
create policy blocks_insert on public.blocks for insert to authenticated
  with check (blocker = auth.uid());
create policy blocks_delete on public.blocks for delete to authenticated
  using (blocker = auth.uid());

revoke all on public.blocks from public, anon, authenticated;
grant select, insert, delete on public.blocks to authenticated;

create or replace function public.guard_message_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_recent int;
begin
  new.body := trim(new.body);
  if char_length(new.body) = 0 then
    raise exception 'empty_message' using errcode = 'P0001';
  end if;
  select count(*) into v_recent
  from public.messages m
  where m.sender_id = new.sender_id
    and m.created_at > clock_timestamp() - interval '60 seconds';
  if v_recent >= 30 then
    raise exception 'rate_limit_exceeded' using errcode = 'P0001';
  end if;
  -- A blocked user cannot deliver new DM messages to the blocker.
  if exists (
    select 1
    from public.conversations c
    join public.conversation_members cm
      on cm.conversation_id = c.id and cm.user_id <> new.sender_id
    join public.blocks b
      on b.blocker = cm.user_id and b.blocked = new.sender_id
    where c.id = new.conversation_id and c.kind = 'dm'
  ) then
    raise exception 'blocked' using errcode = 'P0001';
  end if;
  return new;
end $$;

revoke all on function public.guard_message_insert() from public, anon, authenticated;

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

revoke all on function public.get_or_create_dm(uuid) from public, anon;
grant execute on function public.get_or_create_dm(uuid) to authenticated;

-- ------------------------------------------------------------------ (4)
create or replace function public.delete_my_account()
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := auth.uid();
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = 'P0001'; end if;
  -- Keep shared conversations alive for the other side: hand over ownership
  -- to the earliest remaining member (created_by cascades otherwise).
  update public.conversations c
  set created_by = (
    select cm.user_id
    from public.conversation_members cm
    where cm.conversation_id = c.id and cm.user_id <> v_me
    order by cm.joined_at
    limit 1
  )
  where c.created_by = v_me
    and exists (
      select 1 from public.conversation_members cm2
      where cm2.conversation_id = c.id and cm2.user_id <> v_me
    );
  -- Cascades remove my messages, memberships and blocks.
  delete from public.profiles where id = v_me;
  delete from auth.users where id = v_me;
end $$;

revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
