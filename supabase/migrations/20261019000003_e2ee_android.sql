-- Mamoot (مموت) — end-to-end encrypted "secret chat" support for the Android app.
-- Server stores ONLY ciphertext envelopes for kind='e2ee' messages.
-- Protocol modeled on Telegram Secret Chats (see docs/SECRET_CHAT_PROTOCOL.md):
-- authenticated ephemeral ECDH key exchange, per-direction symmetric key
-- chains with in/out sequence numbers, and DH re-keying after 100 messages
-- or 7 days. Private keys never leave the device.

alter table public.profiles
  add column e2e_identity_key text
  check (
    e2e_identity_key is null
    or (char_length(e2e_identity_key) between 80 and 200
        and e2e_identity_key ~ '^[A-Za-z0-9_-]+$')
  );

alter table public.messages
  add column kind text not null default 'text'
  check (kind in ('text', 'e2ee'));

-- search_users now also returns the peer's E2EE identity public key (public data).
drop function if exists public.search_users(text, int);

create function public.search_users(p_query text, p_limit int default 20)
returns table (id uuid, username text, display_name text, e2e_identity_key text)
language plpgsql stable security definer set search_path = '' as $$
declare v_q text := trim(coalesce(p_query, '')); v_esc text; v_lim int;
begin
  if auth.uid() is null or char_length(v_q) < 2 then return; end if;
  v_esc := replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_');
  v_lim := least(greatest(coalesce(p_limit, 20), 1), 20);
  return query
    select p.id, p.username, p.display_name, p.e2e_identity_key
    from public.profiles p
    where p.id <> auth.uid()
      and (p.username ilike '%' || v_esc || '%' escape '\'
           or p.display_name ilike '%' || v_esc || '%' escape '\')
    order by (p.username ilike v_esc || '%' escape '\') desc, p.username asc
    limit v_lim;
end $$;

revoke all on function public.search_users(text, int) from public, anon;
grant execute on function public.search_users(text, int) to authenticated;

-- include message kind in conversation-list previews
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
    ),
    me.last_read_at
  from public.conversations c
  join public.conversation_members me
    on me.conversation_id = c.id and me.user_id = auth.uid()
  order by c.last_message_at desc
$$;

revoke all on function public.list_conversations() from public, anon;
grant execute on function public.list_conversations() to authenticated;
