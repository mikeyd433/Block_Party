# Next steps (paused 2026-11-08)

## Planned: turn `not-a-book-club` into a shared database for all apps

The owner wants the Supabase project **not-a-book-club** (ref `dpflpwoivvpfvainzwgd`,
org "The Jackie Chan Fan Club", us-east-1) to become a general database for every app they
build, not just the book club. This transfer will be done in a **new chat with both repos**
(this one and the not-a-book-club app's repo) so the two apps can be coordinated.

Agreed approach:

- **One schema per app.** Block Party lives in a `blockparty` schema. New apps get their
  own schemas the same way.
- **Book club tables stay in `public` for now.** They can move to their own schema later,
  but that app's code would need updating at the same time. Decide this in the new chat.
- **Shared sign-in.** All apps share Supabase Auth: one account works across apps, and
  sign-in settings (providers, email confirmation, email wording, redirect URLs) are
  project-wide. Review them so they suit every app.
- Optionally rename the project (for example to "apps"). The URL and keys don't change.
- Keep in mind: database/storage/bandwidth limits and free-plan pausing are shared by all apps.

## Block Party backend: not applied yet

- The migration is ready at `supabase/migrations/20261108_blockparty_core.sql`. It creates the
  `blockparty` schema (streets, members, player_state, lots, sprites), grants, row-level
  security, game functions (create/join street, claim/close/merge lots, new invite, remove
  member), the private `blockparty-drawings` bucket and realtime.
- Applying it through the Supabase connector timed out three times waiting for approval, so
  **nothing has been created in the project yet.** Either run the file in the SQL editor, or
  retry via the connector and approve the prompt quickly.
- After applying: **Project Settings → Data API → Exposed schemas → add `blockparty`.**
- Then verify the tables, grants and policies, and check the security advisors.
- This container's network policy blocks `*.supabase.co`, so the app can't be tested against
  the live backend from here (the phone is unaffected).

## Remaining build work

1. **Stage 5 (app side):** sign-in (email + password), streets list (the local single-player
   street plus shared ones), create street (admin, invite code), join by code or invite link,
   and syncing: own lots, player state and drawings up; other players' lots and drawings
   down; realtime updates. Each street gets its own local `WorldState` file. The groundwork
   is already in the app: lot ownership (`Lot.ownerId`, `WorldState.myUserId`, `isMine`),
   offline catch-up, and per-device sales for your own shops only.
2. **Stages 8–9:** street chat, notes at shop doors, gifting, loans, buying shops from each
   other, shared promos; player XP, business reputation, street level and unlocks (locked
   lots, new tags, catalog furniture); events (weekly, votes, random, player-hosted),
   leaderboards, seasons and weather; admin moderation tools.
