# Next steps (paused 2026-11-08)

## Shared database: done

The Supabase project **not-a-book-club** (ref `dpflpwoivvpfvainzwgd`) is now a shared database
for all the owner's apps. One schema per app: Block Party's is `block_party` (it already exists,
empty). Book club tables stay in `public` for now. All apps share Supabase Auth and the
`public.profiles` table. See the README's Database section.

Keep in mind: database/storage/bandwidth limits and free-plan pausing are shared by all apps.

## Block Party backend: applied

- `supabase/migrations/20261108_block_party_core.sql` was run in the SQL editor on 2026-11-08,
  and `block_party` is listed under Exposed schemas. Verified afterwards: the tables (RLS on),
  functions, policies, the private `block-party-drawings` bucket and realtime on lots, sprites
  and members are all in place; `anon` has no access and `authenticated` has no TRUNCATE.
- `supabase/migrations/20261108_block_party_fk_indexes.sql` adds two foreign-key indexes the
  performance advisor asked for. Run it in the SQL editor too if it isn't applied yet.
- Security advisor notes that are expected: the game's RPCs are SECURITY DEFINER functions that
  signed-in players may call (each checks membership or ownership itself), and the "anonymous
  access" warning only matters if anonymous sign-ins are turned on for the project.
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
