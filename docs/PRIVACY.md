# Mamoot Privacy Policy

_Last updated: 2026-10-21 (v0.4.0)_

Mamoot (مموت) is a small, open-source text messenger. This document explains
exactly what data exists, where it lives, and what control you have over it.
The service has no advertising, no analytics SDKs, and never sells data.

## What the server stores

- **Account**: your email address and a password hash (managed by the auth
  provider), plus your chosen username and display name.
- **Public identity key**: the public half of your secret-chat identity
  keypair. The private half never leaves your phone.
- **Messages**:
  - *Secret chats* 🔒 are end-to-end encrypted on the devices. The server
    stores only ciphertext (`MAM1…` envelopes) and the minimal routing
    metadata needed for delivery (sender, conversation, timestamp, kind).
    Replies, forwards and reactions inside secret chats are part of the
    encrypted payload, so the server does not learn them either.
  - *Normal chats and groups* are stored as readable text on the server so
    history can sync across sessions. Transport is TLS; database access is
    restricted by row-level security (only conversation members can read).
- **Presence**: a "last seen" timestamp updated while you use the app. You
  can hide it completely in Settings → Advanced security → Last-seen privacy.
- **Read state**: per-conversation read timestamps powering read receipts.
- **Reactions on normal chats**, your **block list**, and **abuse reports**
  you submit (reporter, reported user, optional message reference, reason).

## What stays only on your phone

- Private keys (identity + per-session ratchets), stored encrypted at rest
  with a key in the Android Keystore (AES-256-GCM).
- Decrypted message history (encrypted at rest), your app-lock PIN hash,
  and self-destruct timers.

## Data retention & deletion

- Deleting a message tombstones it for everyone (the body is removed).
- Deleting your account (Settings → Delete account) permanently removes
  your account, profile and all messages you sent. This is irreversible.
- Signing out or deleting the account wipes all Mamoot data on the device.

## Your rights & controls

- **Export**: Settings → Advanced security → "Export my data" produces a
  readable file of your account info and on-device history.
- **Backup**: an optional passphrase-encrypted backup (PBKDF2-HMAC-SHA256,
  AES-256-GCM) can be created and restored only by you; it is never uploaded.
- **Block/Report**: block any user; report spam or abuse from any message.
- **Key controls**: end or reset secret sessions, revoke all sessions, or
  rotate your identity key at any time.

## Third parties

The backend runs on Supabase (EU, Frankfurt region) — a Postgres database
with row-level security. No third-party trackers or ad networks are used.
The app does not depend on Google Play Services.

## Contact

The project is maintained in the public MamootChat repository; open an
issue there for privacy questions or data requests.
