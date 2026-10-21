-- Mamoot RLS / RPC regression tests (audit F2/F9/F11/F13, F16).
-- Runs entirely inside one transaction that is ROLLED BACK at the end:
-- safe against any project (staging preferred; production-safe because
-- nothing is committed). Execute as postgres (SQL editor / connector).
-- Expected final output: one row, jsonb object with every check = true.
begin;

create temp table test_results(name text primary key, ok boolean);
grant all on test_results to authenticated;

-- ---------------------------------------------------------------- fixtures
insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
select id, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
       'rls-' || short || '@example.com', '', now(), '{"provider":"email"}', '{}', now(), now()
from (values
  ('10000000-0000-4000-8000-00000000000a'::uuid, 'a'),
  ('10000000-0000-4000-8000-00000000000b'::uuid, 'b'),
  ('10000000-0000-4000-8000-00000000000c'::uuid, 'c')
) v(id, short);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
select ('30000000-0000-4000-8000-0000000000' || lpad(g::text, 2, '0'))::uuid,
       '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
       'rls-d' || g || '@example.com', '', now(), '{"provider":"email"}', '{}', now(), now()
from generate_series(0, 20) g;

insert into public.profiles (id, username, display_name, e2e_identity_key, last_seen_at, last_seen_public) values
  ('10000000-0000-4000-8000-00000000000a', 'rls_a', 'RLS A', repeat('A', 120), now(), true),
  ('10000000-0000-4000-8000-00000000000b', 'rls_b', 'RLS B', repeat('B', 120), now(), false),
  ('10000000-0000-4000-8000-00000000000c', 'rls_c', 'RLS C', repeat('C', 120), now(), true);

insert into public.profiles (id, username, display_name)
select ('30000000-0000-4000-8000-0000000000' || lpad(g::text, 2, '0'))::uuid, 'rls_d' || g, 'RLS D' || g
from generate_series(0, 20) g;

-- DM A-B, DM A-C, group G (owner A, member B)
insert into public.conversations (id, kind, created_by, dm_key) values
  ('40000000-0000-4000-8000-000000000001', 'dm', '10000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-00000000000a:10000000-0000-4000-8000-00000000000b'),
  ('40000000-0000-4000-8000-000000000002', 'dm', '10000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-00000000000a:10000000-0000-4000-8000-00000000000c');
insert into public.conversations (id, kind, title, created_by) values
  ('40000000-0000-4000-8000-000000000003', 'group', 'RLS Group', '10000000-0000-4000-8000-00000000000a');

insert into public.conversation_members (conversation_id, user_id, role, last_read_at) values
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000a', 'member', now()),
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', 'member', now()),
  ('40000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-00000000000a', 'member', '2000-01-01'),
  ('40000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-00000000000c', 'member', now()),
  ('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-00000000000a', 'owner', now()),
  ('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-00000000000b', 'member', now());

-- messages: 2 unread text from B (A-B), 1 kx control, plus retention fixtures
insert into public.messages (conversation_id, sender_id, client_id, kind, body, created_at) values
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'text', 'hello one', now() - interval '2 minutes'),
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'text', 'hello two', now() - interval '1 minute'),
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'e2ee', 'MAM1.k1.' || repeat('z', 100), now() - interval '30 seconds'),
  -- retention: old fully-read e2ee (doomed), old plain read (kept), old deleted text (doomed)
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'e2ee', 'MAM1.m.old1', now() - interval '40 days'),
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'text', 'old plain read', now() - interval '40 days'),
  -- retention: old unread e2ee in A-C (A never read) -> kept
  ('40000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-00000000000c', gen_random_uuid(), 'e2ee', 'MAM1.m.old2', now() - interval '40 days');
insert into public.messages (conversation_id, sender_id, client_id, kind, body, created_at, deleted_at) values
  ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-00000000000b', gen_random_uuid(), 'text', 'old deleted', now() - interval '40 days', now() - interval '39 days');

-- A has NOT read the two fresh B messages:
update public.conversation_members set last_read_at = now() - interval '10 minutes'
where conversation_id = '40000000-0000-4000-8000-000000000001' and user_id = '10000000-0000-4000-8000-00000000000a';

insert into test_results values ('T16_is_control_flag',
  (select bool_and(is_control) from public.messages where body like 'MAM1.k1.%')
  and not (select is_control from public.messages where body = 'hello one'));

-- ---------------------------------------------------------------- as A
select set_config('request.jwt.claims', '{"sub":"10000000-0000-4000-8000-00000000000a","role":"authenticated"}', true);
set local role authenticated;

do $$ begin
  begin
    perform (select last_seen_at from public.profiles limit 1);
    insert into test_results values ('T01_last_seen_column_denied', false);
  exception when insufficient_privilege then
    insert into test_results values ('T01_last_seen_column_denied', true);
  end;
end $$;

insert into test_results values ('T02_profile_still_readable',
  (select username from public.profiles where id = '10000000-0000-4000-8000-00000000000b') = 'rls_b');

insert into test_results values ('T03_presence_hidden_when_private',
  (select last_seen_at is null and last_seen_public = false
   from public.get_presence('10000000-0000-4000-8000-00000000000b')));

insert into test_results values ('T04_presence_visible_when_public',
  (select last_seen_at is not null from public.get_presence('10000000-0000-4000-8000-00000000000c')));

do $$ declare v bigint; begin
  perform public.add_group_members('40000000-0000-4000-8000-000000000003', array['10000000-0000-4000-8000-00000000000c']::uuid[]);
  insert into test_results values ('T08_owner_can_add', true);
exception when others then
  insert into test_results values ('T08_owner_can_add', false);
end $$;

do $$ begin
  perform public.remove_group_member('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-00000000000c');
  insert into test_results values ('T09a_owner_can_remove', true);
exception when others then
  insert into test_results values ('T09a_owner_can_remove', false);
end $$;

do $$ begin
  perform public.remove_group_member('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-00000000000a');
  insert into test_results values ('T09b_cannot_remove_owner', false);
exception when others then
  insert into test_results values ('T09b_cannot_remove_owner', sqlerrm = 'cannot_remove_owner');
end $$;

insert into test_results values ('T13_sync_conversation_shape',
  (select (sync->'messages' is not null)
       and jsonb_array_length(sync->'messages') >= 3
       and (sync->'reads') ? '10000000-0000-4000-8000-00000000000b'
       and (sync->'presence'->'10000000-0000-4000-8000-00000000000b'->'last_seen_at') = 'null'::jsonb
   from (select public.sync_conversation(
     '40000000-0000-4000-8000-000000000001', '2000-01-01', '2000-01-01') as sync) s));

-- 2 fresh texts + 1 old unread e2ee message; the MAM1.k1 control envelope
-- must NOT be counted (that would make 4).
insert into test_results values ('T14_unread_summary_excludes_control',
  (select total_unread = 3 and newest_unread_at is not null
   from public.unread_summary()));

insert into test_results values ('T18_list_slim_hides_keys',
  (select (members->0->'e2e_identity_key') = 'null'::jsonb
   from public.list_conversations(false) limit 1)
  and (select (members->0->'e2e_identity_key') <> 'null'::jsonb
   from public.list_conversations() limit 1));

do $$ begin
  perform (select public.retention_purge(30, false));
  insert into test_results values ('T17_client_cannot_purge', false);
exception when insufficient_privilege then
  insert into test_results values ('T17_client_cannot_purge', true);
end $$;

do $$ begin
  perform (select count(*) from public.api_rate_limits);
  insert into test_results values ('T17b_rate_table_invisible', false);
exception when insufficient_privilege then
  insert into test_results values ('T17b_rate_table_invisible', true);
end $$;

reset role;

-- ---------------------------------------------------------------- as B (non-owner member)
select set_config('request.jwt.claims', '{"sub":"10000000-0000-4000-8000-00000000000b","role":"authenticated"}', true);
set local role authenticated;

do $$ begin
  perform public.add_group_members('40000000-0000-4000-8000-000000000003', array['10000000-0000-4000-8000-00000000000c']::uuid[]);
  insert into test_results values ('T07_non_owner_add_rejected', false);
exception when others then
  insert into test_results values ('T07_non_owner_add_rejected', sqlerrm = 'not_group_owner');
end $$;

-- B blocks A (for the block-aware group test below, executed as A).
insert into public.blocks (blocker, blocked) values
  ('10000000-0000-4000-8000-00000000000b', '10000000-0000-4000-8000-00000000000a');

reset role;

-- ---------------------------------------------------------------- as A again (block-aware group create; search cap)
select set_config('request.jwt.claims', '{"sub":"10000000-0000-4000-8000-00000000000a","role":"authenticated"}', true);
set local role authenticated;

do $$ declare v_id uuid; begin
  v_id := public.create_group('RLS Block Group', array[
    '10000000-0000-4000-8000-00000000000b',
    '10000000-0000-4000-8000-00000000000c']::uuid[]);
  insert into test_results
  select 'T10_group_create_skips_blocked',
         count(*) = 2 and bool_and(user_id <> '10000000-0000-4000-8000-00000000000b')
  from public.conversation_members where conversation_id = v_id;
exception when others then
  insert into test_results values ('T10_group_create_skips_blocked', false);
end $$;

do $$ declare i int; limited boolean := false; begin
  for i in 1..30 loop
    perform (select count(*) from public.search_users('rls', 5));
  end loop;
  begin
    perform (select count(*) from public.search_users('rls', 5));
  exception when others then
    limited := sqlerrm = 'search_rate_limited';
  end;
  insert into test_results values ('T12_search_rate_limit', limited);
end $$;

reset role;

-- ---------------------------------------------------------------- as C (DM cap; presence isolation)
select set_config('request.jwt.claims', '{"sub":"10000000-0000-4000-8000-00000000000c","role":"authenticated"}', true);
set local role authenticated;

insert into test_results values ('T05_presence_requires_shared_conv',
  not exists (select 1 from public.get_presence('10000000-0000-4000-8000-00000000000b')));

do $$ declare i int; limited boolean := false; begin
  for i in 0..19 loop
    perform public.get_or_create_dm(('30000000-0000-4000-8000-0000000000' || lpad(i::text, 2, '0'))::uuid);
  end loop;
  begin
    perform public.get_or_create_dm('30000000-0000-4000-8000-000000000020');
  exception when others then
    limited := sqlerrm = 'dm_rate_limited';
  end;
  insert into test_results values ('T11_dm_daily_cap', limited);
end $$;

reset role;

-- ---------------------------------------------------------------- retention (as postgres)
-- Two steps on purpose: a single SELECT mixing the purge call with exists()
-- probes is unreliable because argument/subquery evaluation order is not
-- guaranteed (observed live: probes ran before the purge).
create temp table purge_n(n bigint);
insert into purge_n select public.retention_purge(30, false);
insert into test_results
select 'T15_retention_purge_selective',
       (select n from purge_n) = 2
       and exists (select 1 from public.messages where body = 'old plain read')
       and exists (select 1 from public.messages where body = 'MAM1.m.old2')
       and not exists (select 1 from public.messages where body in ('MAM1.m.old1', 'old deleted'));

select jsonb_object_agg(name, ok order by name) as rls_test_results,
       bool_and(ok) as all_passed
from test_results;

rollback;
