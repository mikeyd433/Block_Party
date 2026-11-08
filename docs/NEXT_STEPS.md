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

1. **Stage 5 (app side).** Done: sign-in and new accounts, the Streets screen, create / join /
   leave, invite codes, and **playing on a shared street**:
   - Each shared street has its own local save (`streets/<user id>/<street id>.json`, a
     `WorldState` with `myUserId` set). The first open downloads the lots, drawings and the
     player's coins/books (`player_state`); after that it opens from the local copy.
   - Sync (`shared/core/net/StreetSync.kt`): each device owns its player's shops and drawings.
     `SyncPlanner` compares the local world with the server and produces claim / close / merge /
     update / sprite / player-state calls, so buying, closing (including by debt) and merging
     work locally first and are sent afterwards, offline included. `SyncMerge` takes everyone
     else's lots and drawings from the server and keeps the player's own. Losing a race for a
     lot hands it to the other player and refunds the price. Drawing PNGs go to the
     `block-party-drawings` bucket under `<street id>/<sprite id>.png`.
   - The loop in `GameViewModel.startSync` sends changes ~3 s after they happen and pulls every
     20 s. Other players' shops can be visited but not changed (the view model also ignores
     edits to them).
   Still to do:
   - Pulls are incremental: a cheap `lot_id,updated_at` check, then only lots whose stamp
     changed (compared for equality, so device clocks don't matter). Shop updates set
     `updated_at`; claim / close / merge set it on the server. Every 15th pull (~5 minutes)
     downloads the whole street, which also catches `remove_member` (it doesn't touch
     `updated_at`). Drawings' details are always fetched in full (they're small).
   - Removed by the admin (or the street is gone): the sync notices, tells the player,
     forgets the local copy and goes back to the single-player street.
   - Join with code fills itself in from a copied invite message or code.
   - Live updates: `StreetLive` (app, OkHttp websocket) joins a Supabase Realtime channel for
     the street's `lots`, `sprites` and `members` (`RealtimeProtocol` in shared). Each change
     wakes the sync, which does its usual incremental pull half a second later; payloads aren't
     applied directly, so there's one way changes come in. Heartbeat every 25 s, the refreshed
     access token is passed on, and it reconnects with backoff (2 s up to 1 minute). While it's
     connected the poll drops to once a minute. Row-level security applies to Realtime too.
   Still to do:
   - **Invite links** that open the app need a website domain the owner controls (for Android
     App Links' assetlinks.json); Supabase won't serve web pages from its own domain.
   - Deleting another player's drawing from your picker only hides it until the next pull.
   - Customer profiles for other players' drawings use your level's tag set, so tastes can
     differ slightly between devices with different levels.
   - If the project requires email confirmation, the confirmation link goes to the project's
     Site URL (set for Not A Book Club). The app tells players to confirm and then sign in.
2. **Stages 8–9:** street chat, notes at shop doors, gifting, loans, buying shops from each
   other, shared promos; player XP, business reputation, street level and unlocks (locked
   lots, new tags, catalog furniture); events (weekly, votes, random, player-hosted),
   leaderboards, seasons and weather; admin moderation tools.
