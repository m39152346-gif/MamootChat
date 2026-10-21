-- Mamoot v0.4.0: presence (last seen), typing indicators, message replies,
-- forwarded flag, reactions (plain/group messages), and user reports.
-- Security model unchanged: RLS everywhere, authenticated-only, membership
-- checks through the existing SECURITY DEFINER helpers.

-- ---------------------------------------------------------------- presence
alter table public.profiles add column if not exists last_seen_at timestamptz;
alter table public.profiles add column if not exists last_seen_public boolean not null default true;

-- ---------------------------------------------------------------- replies / forwards
alter table public.messages
  add column if not exists reply_to_id uuid references public.messages (id) on delete set null;
alter table public.messages
  add column if not exists forwarded boolean not null default false;
create index if not exists messages_reply_idx on public.messages (reply_to_id);

-- reply_to_id / forwarded are set at insert and immutable afterwards.
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
     or new.reply_to_id is distinct from old.reply_to_id
     or new.forwarded <> old.forwarded
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

-- list_conversations: expose presence + reply/forward on the last message.
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
        'role', m2.role, 'e2e_identity_key', p.e2e_identity_key,
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

-- ---------------------------------------------------------------- typing
create table public.typing_status (
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  typing_until timestamptz not null,
  updated_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create index typing_status_conv_idx on public.typing_status (conversation_id, typing_until desc);

alter table public.typing_status enable row level security;

create policy typing_select on public.typing_status for select to authenticated
  using (public.is_conversation_member(conversation_id));
create policy typing_insert on public.typing_status for insert to authenticated
  with check (user_id = auth.uid() and public.is_conversation_member(conversation_id));
create policy typing_update on public.typing_status for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid() and public.is_conversation_member(conversation_id));
create policy typing_delete on public.typing_status for delete to authenticated
  using (user_id = auth.uid());

revoke all on public.typing_status from public, anon, authenticated;
grant select, insert, update, delete on public.typing_status to authenticated;

-- ---------------------------------------------------------------- reactions (plain / group)
create or replace function public.is_message_member(p_message uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.messages m
    join public.conversation_members cm
      on cm.conversation_id = m.conversation_id and cm.user_id = auth.uid()
    where m.id = p_message
  )
$$;

revoke all on function public.is_message_member(uuid) from public, anon;
grant execute on function public.is_message_member(uuid) to authenticated;

create table public.message_reactions (
  message_id uuid not null references public.messages (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  reaction text not null check (char_length(reaction) between 1 and 16),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (message_id, user_id)
);

create index message_reactions_message_idx on public.message_reactions (message_id);

alter table public.message_reactions enable row level security;

create policy reactions_select on public.message_reactions for select to authenticated
  using (public.is_message_member(message_id));
create policy reactions_insert on public.message_reactions for insert to authenticated
  with check (user_id = auth.uid() and public.is_message_member(message_id));
create policy reactions_update on public.message_reactions for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid() and public.is_message_member(message_id));
create policy reactions_delete on public.message_reactions for delete to authenticated
  using (user_id = auth.uid());

revoke all on public.message_reactions from public, anon, authenticated;
grant select, insert, update, delete on public.message_reactions to authenticated;

-- ---------------------------------------------------------------- reports
create table public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter uuid not null references public.profiles (id) on delete cascade,
  reported uuid not null references public.profiles (id) on delete cascade,
  conversation_id uuid references public.conversations (id) on delete set null,
  message_id uuid references public.messages (id) on delete set null,
  reason text not null check (char_length(reason) between 3 and 500),
  status text not null default 'open' check (status in ('open', 'reviewing', 'closed')),
  created_at timestamptz not null default now(),
  check (reporter <> reported)
);

create index reports_reporter_idx on public.reports (reporter, created_at desc);
create index reports_status_idx on public.reports (status, created_at desc);

alter table public.reports enable row level security;

create policy reports_insert on public.reports for insert to authenticated
  with check (reporter = auth.uid());
create policy reports_select on public.reports for select to authenticated
  using (reporter = auth.uid());

revoke all on public.reports from public, anon, authenticated;
grant select, insert on public.reports to authenticated;

create or replace function public.guard_report_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_recent int;
begin
  select count(*) into v_recent
  from public.reports r
  where r.reporter = new.reporter
    and r.created_at > now() - interval '1 hour';
  if v_recent >= 10 then
    raise exception 'report_rate_limited' using errcode = 'P0001';
  end if;
  return new;
end $$;

create trigger reports_guard_insert
  before insert on public.reports
  for each row execute function public.guard_report_insert();

revoke all on function public.guard_report_insert() from public, anon, authenticated;
