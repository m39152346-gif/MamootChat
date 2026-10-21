-- MamootChat — hardening pass (findings from transactional RLS verification)
-- 1) clock_timestamp() instead of now(): now() is fixed at transaction start,
--    which can make a first message share the exact timestamp of a member's
--    last_read_at. clock_timestamp() always advances, so ordering/unread math
--    stays exact.
-- 2) Explicitly revoke ALL table privileges from `authenticated` (Supabase
--    default privileges are generous) and re-grant only the minimum.
--    RLS already blocked DELETE (no policy); now the privilege itself is gone.

alter table public.profiles alter column created_at set default clock_timestamp();
alter table public.conversations alter column created_at set default clock_timestamp();
alter table public.conversations alter column last_message_at set default clock_timestamp();
alter table public.conversation_members alter column joined_at set default clock_timestamp();
alter table public.conversation_members alter column last_read_at set default clock_timestamp();
alter table public.messages alter column created_at set default clock_timestamp();

create or replace function public.mark_read(p_conversation uuid)
returns void language sql security definer set search_path = '' as $$
  update public.conversation_members
  set last_read_at = clock_timestamp()
  where conversation_id = p_conversation and user_id = auth.uid()
$$;

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
  return new;
end $$;

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
    new.deleted_at := clock_timestamp();
    new.body := '';
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

revoke all on function public.mark_read(uuid) from public, anon;
grant execute on function public.mark_read(uuid) to authenticated;
revoke all on function public.guard_message_insert() from public, anon, authenticated;
revoke all on function public.guard_message_update() from public, anon, authenticated;

revoke all on public.profiles, public.conversations, public.conversation_members, public.messages
  from public, anon, authenticated;
grant select, insert, update on public.profiles to authenticated;
grant select on public.conversations to authenticated;
grant select on public.conversation_members to authenticated;
grant select, insert, update on public.messages to authenticated;
