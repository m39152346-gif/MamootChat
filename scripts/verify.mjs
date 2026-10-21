/**
 * End-to-end + security verification against a Supabase project.
 * Uses ONLY the publishable (anon) key, exactly like an app client.
 *
 * Usage: npm run verify
 * Creates 3 throwaway users (verify-*@example.com). Clean them up afterwards
 * in SQL: delete from auth.users where email like 'verify-%@example.com';
 *
 * Audit F16: prefer a separate STAGING project. Set
 *   VERIFY_SUPABASE_URL / VERIFY_SUPABASE_ANON_KEY
 * to point the script at staging; VITE_SUPABASE_* (the app defaults) are
 * only used as a fallback, and hitting the production project requires
 * the explicit opt-in ALLOW_PROD_VERIFY=1. Creating a staging project is
 * a MANUAL STEP (see AUDIT.md): free plan allows 2 active projects.
 */
import { readFileSync } from 'node:fs'
import { createClient } from '@supabase/supabase-js'

function loadEnv() {
  const out = {}
  try {
    for (const line of readFileSync(new URL('../.env.local', import.meta.url), 'utf8').split('\n')) {
      const m = line.match(/^([A-Z_]+)=(.*)$/)
      if (m) out[m[1]] = m[2].trim()
    }
  } catch { /* fall back to process.env */ }
  return { ...out, ...process.env }
}

const env = loadEnv()
const SUPABASE_URL = env.VERIFY_SUPABASE_URL || env.VITE_SUPABASE_URL
const KEY = env.VERIFY_SUPABASE_ANON_KEY || env.VITE_SUPABASE_ANON_KEY
if (!SUPABASE_URL || !KEY) { console.error('Missing VERIFY_SUPABASE_URL / VERIFY_SUPABASE_ANON_KEY (or VITE_ fallbacks)'); process.exit(2) }
const PROD_REF = 'eslatwgowmovfkkddcnp'
if (SUPABASE_URL.includes(PROD_REF) && env.ALLOW_PROD_VERIFY !== '1') {
  console.error(`Refusing to run against the production project (${PROD_REF}).`)
  console.error('Point VERIFY_SUPABASE_* at a staging project, or set ALLOW_PROD_VERIFY=1 to override.')
  process.exit(2)
}

const rand = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`
const password = 'Verify!2345abcd'
let passed = 0
let failed = 0

function check(name, cond, extra = '') {
  if (cond) { passed++; console.log(`  ✓ ${name}`) }
  else { failed++; console.log(`  ✗ ${name} ${extra}`) }
}

function client() {
  return createClient(SUPABASE_URL, KEY, { auth: { persistSession: false, autoRefreshToken: false } })
}

async function makeUser(tag) {
  const c = client()
  const email = `verify-${rand}-${tag}@example.com`
  const { data, error } = await c.auth.signUp({ email, password })
  if (error || !data.user) throw new Error(`signup ${tag}: ${error?.message}`)
  const username = `v${rand}${tag}`.slice(0, 24)
  const { error: pErr } = await c.from('profiles').insert({
    id: data.user.id, username, display_name: `Verify ${tag}`,
  })
  if (pErr) throw new Error(`profile ${tag}: ${pErr.message}`)
  return { client: c, id: data.user.id, email, username }
}

console.log(`\nMamootChat verification (run ${rand})\n`)
const A = await makeUser('a')
const B = await makeUser('b')
const C = await makeUser('c')
console.log(`users: ${A.email}, ${B.email}, ${C.email}\n`)

console.log('— happy path —')
const { data: found } = await A.client.rpc('search_users', { p_query: B.username, p_limit: 20 })
check('A can search B by username', found?.some((u) => u.id === B.id))

const { data: convId, error: dmErr } = await A.client.rpc('get_or_create_dm', { p_other: B.id })
check('A creates DM with B', !dmErr && typeof convId === 'string', dmErr?.message ?? '')

const { data: convIdAgain } = await B.client.rpc('get_or_create_dm', { p_other: A.id })
check('DM is deduplicated per pair', convIdAgain === convId)

const { data: msg1, error: sendErr } = await A.client.from('messages').insert({
  conversation_id: convId, sender_id: A.id, body: 'salam from A', client_id: crypto.randomUUID(),
}).select().single()
check('A sends a message', !sendErr && msg1?.body === 'salam from A', sendErr?.message ?? '')

const { data: bMsgs } = await B.client.from('messages').select('*').eq('conversation_id', convId)
check('B reads the message', bMsgs?.length === 1 && bMsgs[0].body === 'salam from A')

const { data: bList } = await B.client.rpc('list_conversations')
check('B conversation list shows DM with unread=1',
  bList?.some((c) => c.id === convId && Number(c.unread_count) === 1 && c.last_message?.body === 'salam from A'))

await B.client.rpc('mark_read', { p_conversation: convId })
const { data: bList2 } = await B.client.rpc('list_conversations')
check('mark_read clears unread', bList2?.some((c) => c.id === convId && Number(c.unread_count) === 0))

console.log('\n— security: outsider C —')
const { data: cMsgs } = await C.client.from('messages').select('*').eq('conversation_id', convId)
check('C cannot read A-B messages (RLS)', (cMsgs ?? []).length === 0)

const { error: cInsertErr } = await C.client.from('messages').insert({
  conversation_id: convId, sender_id: C.id, body: 'intrusion', client_id: crypto.randomUUID(),
})
check('C cannot post into A-B conversation (RLS)', Boolean(cInsertErr))

const { data: cProfilePeek } = await C.client.from('profiles')
  .select('id, username, display_name, created_at, e2e_identity_key, last_seen_public').eq('id', A.id)
check('C cannot read A profile without shared conversation', (cProfilePeek ?? []).length === 0)

// Audit F2: last_seen_at must not be client-readable at the column level,
// and get_presence must stay empty for users sharing no conversation.
const { error: cLastSeenErr } = await C.client.from('profiles').select('last_seen_at').eq('id', A.id)
check('C cannot select last_seen_at (column grant removed)', Boolean(cLastSeenErr))
const { data: cPresence } = await C.client.rpc('get_presence', { p_user: A.id })
check('get_presence empty without shared conversation', (cPresence ?? []).length === 0)

const { data: cList } = await C.client.rpc('list_conversations')
check('C conversation list does not include A-B DM', !(cList ?? []).some((c) => c.id === convId))

console.log('\n— security: impersonation & tampering —')
const { error: impErr } = await A.client.from('messages').insert({
  conversation_id: convId, sender_id: B.id, body: 'fake B', client_id: crypto.randomUUID(),
})
check('A cannot send as B (sender_id = auth.uid())', Boolean(impErr))

await B.client.from('messages').update({ body: 'hacked by B' }).eq('id', msg1.id)
const { data: afterTamper } = await A.client.from('messages').select('body').eq('id', msg1.id).single()
check('B cannot edit A message', afterTamper?.body === 'salam from A')

const { error: immutableErr } = await A.client.from('messages')
  .update({ created_at: '2020-01-01T00:00:00Z' }).eq('id', msg1.id)
check('A cannot rewrite created_at (trigger)', Boolean(immutableErr))

const { data: edited, error: editErr } = await A.client.from('messages')
  .update({ body: 'salam edited' }).eq('id', msg1.id).select().single()
check('A edits own message + edited_at set', !editErr && edited?.body === 'salam edited' && Boolean(edited?.edited_at))

const { data: deleted, error: delErr } = await A.client.from('messages')
  .update({ deleted_at: new Date().toISOString() }).eq('id', msg1.id).select().single()
check('soft delete wipes body', !delErr && deleted?.body === '' && Boolean(deleted?.deleted_at))

const { error: hardDelErr, count: hardDelCount } = await A.client.from('messages')
  .delete({ count: 'exact' }).eq('id', msg1.id)
check('hard DELETE is impossible (no policy)', Boolean(hardDelErr) || hardDelCount === 0)

console.log('\n— security: anonymous —')
const anon = client()
const { data: anonMsgs, error: anonErr } = await anon.from('messages').select('*')
check('anon cannot read messages', Boolean(anonErr) || (anonMsgs ?? []).length === 0)
const { data: anonSearch, error: anonSearchErr } = await anon.rpc('search_users', { p_query: B.username })
check('anon cannot search users', Boolean(anonSearchErr) || (anonSearch ?? []).length === 0)

console.log('\n— groups —')
const { data: groupId, error: gErr } = await A.client.rpc('create_group', {
  p_title: 'Verify Group', p_member_ids: [B.id, C.id],
})
check('A creates group with B+C', !gErr && typeof groupId === 'string', gErr?.message ?? '')
const { error: cGroupSendErr } = await C.client.from('messages').insert({
  conversation_id: groupId, sender_id: C.id, body: 'hello group', client_id: crypto.randomUUID(),
})
check('C (member) can post in group', !cGroupSendErr, cGroupSendErr?.message ?? '')
await C.client.rpc('leave_conversation', { p_conversation: groupId })
const { data: cAfterLeave } = await C.client.from('messages').select('*').eq('conversation_id', groupId)
check('after leaving, C loses group access', (cAfterLeave ?? []).length === 0)

console.log('\n— anti-spam rate limit (30 msgs / 60s) —')
let rateLimited = false
for (let i = 0; i < 35; i++) {
  const { error } = await B.client.from('messages').insert({
    conversation_id: convId, sender_id: B.id, body: `spam ${i}`, client_id: crypto.randomUUID(),
  })
  if (error && error.message.includes('rate_limit_exceeded')) { rateLimited = true; break }
  if (error) { console.log(`   (other error at ${i}: ${error.message})`); break }
}
check('DB trigger blocks >30 msgs/60s', rateLimited)

console.log(`\nResult: ${passed} passed, ${failed} failed`)
console.log(`Cleanup SQL: delete from auth.users where email like 'verify-${rand}-%@example.com';`)
process.exit(failed ? 1 : 0)
