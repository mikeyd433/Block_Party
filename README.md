# Block Party

A mobile tycoon game where friends each run businesses on one shared isometric street, and every
business can be anything the player wants. Nearly everything in the world is drawn by the
players and stands in the scene as a flat paper cutout. The full design is in
[`docs/SPEC.md`](docs/SPEC.md).

Build stages 1–7 are done (stage 5: accounts and playing together on shared streets), and stage 8
has started (chat, door notes, gifts and loans): a single-player street prototype with drawn customers who walk the
street, shop by their tastes and budget, pay, and leave reviews, plus running costs and debt.

## What works so far

- **Isometric street** drawn on a Compose `Canvas`: road, sidewalks, your lot, and locked
  neighbouring lots. Pan and pinch-zoom it, and tap your building to go inside.
- **Street view vs. inside view.** From the street your shop is a closed building with your
  storefront drawing mapped onto its front wall. Inside, the front walls are cut away so you can
  see and arrange the interior.
- **Freehand drawing tool**: brush and eraser, 4 sizes, 18 colours, undo/redo, clear.
  You can also **upload a photo** (with optional automatic background removal) and draw over it.
  Drawings are saved as transparent PNGs, cropped to the drawing and scaled to fit the world.
- **Paper-cutout renderer**: drawings are shown exactly as drawn, upright, with a ground shadow.
  Motion is all procedural: walking bob, squash and stretch, sway, a hop when tapped and a
  wobble when placed.
- **Drawn furniture**: draw a piece, pick its floor space (1×1 up to 3×2), then tap the floor to
  place it. Tap a placed piece to flip, move or remove it. Placement checks lot bounds and overlaps.
- **Drawn shopkeeper** who wanders the free tiles of your shop (BFS pathing) and flips to face
  the way they walk.
- **Business identity**: free-text name and description; products, services, or both; tags
  from the tag library (Mood / Price / Audience / Type / Time, with higher-level tags shown locked).
- **Products and services** with a name, any price, tags and a drawn picture.
- **Artist credit**: per drawing, choose to sign it with your artist name or stay anonymous.
- **Make it 3D**: draw a piece of furniture straight on, then turn it to the street's angle with
  adjustable thickness and keep drawing details on top. Drawings can also be leaned into the
  floor grid as flat panels.
- **Display pieces**: tap a placed piece, then **Display**, to give its drawing display spots.
  Tap to add a spot where an item rests, drag to move it, and set each spot's item size. The
  number of spots is how many items it holds. Each placed copy picks which product sits on each
  spot (or fills them automatically), and the products stand on the furniture in the shop,
  moving with it.
- **Furniture types**: in a placed piece's **Setup**, pick Decor, Checkout, Service station or
  Seating (display spots can be added to any type). Customers use them: browsers head for display
  pieces, queue and pay at a checkout, queue to be served on service stations (if the shop sells
  services), and sit on free seats while waiting or resting. Seat height is adjustable with a
  live preview, and seated customers are drawn in the seat with their legs hidden.
- **Drawn customers** (stage 2): draw a customer and they join the street's NPC pool. The game
  gives them a name, liked tags and a budget (players never choose these). Customers walk the
  sidewalks, and some step into your shop, browse between free tiles and leave. Tap one to see
  who they are. Who goes in is a placeholder based on shared tags until stage 3 adds real
  matching, prices and sales.
- **Customer simulation** (stage 3): who walks in depends on how well the shop's tags match
  their tastes, how its prices compare with their budget, and its rating. Inside, they look at
  products on display pieces (or a few products if there are no stocked displays), weigh tags
  against price, and carry what they pick. They pay at a checkout (or on the way out), pay for
  services when done, and some leave a 1–5 star review written from hand-authored phrases (tag
  matches and misses, value for money, things they couldn't afford, queue time). Coins and the
  shop's rating show in the top bar; tap it for stats and reviews. Staff hand the service's
  drawing to the customer at service stations, and the customer leaves with it.
- **Economy** (stage 4): game time follows the street's timezone, and a game day is a real day.
  Rent is charged per lot at midnight (prime middle lots cost more; days missed while away are
  billed on return with a notice). Products have stock: shelf space is 5 units per display spot
  showing them (or 8 behind the counter), restocked from the supplier at a per-unit cost that
  depends on the supply quality (basic, standard, fancy), which also affects reviews. Services
  use supplies each time. Sold-out products vanish from displays and annoy customers. Coins can
  go negative; after 7 days in debt a shop closes, its furniture goes to storage and the lot
  becomes vacant. The money section (tap the top bar) shows daily income and costs.
- **Multiple shops** (stage 6): buy vacant lots (prime spots cost more) to open more shops,
  each with its own name, products, furniture and reviews; tap a building to go into that shop.
  Close a shop from its editor (furniture goes to storage, the lot becomes vacant again).
  Expand a shop into the lot next door (buying it, or folding in another shop you own): the two
  lots and the gap between become one bigger shop, with rent scaled by area. Stored furniture
  shows up first in the furniture picker.
- **Employees** (stage 7): a daily job board (in the shop editor's Staff section) shows a few
  applicants with a look (employee drawings: public ones anyone can hire, private ones only the
  artist can), a role, one or two traits and a wage ask; rare applicants are better and pricier.
  Cashiers stand at the checkout and ring people up faster, stockers top up low products
  automatically, servers provide services alongside the shopkeeper, and greeters stand by the
  door pulling in passers-by. Traits trade off speed, reviews, waste and time of day. Staff gain
  levels, lose morale over time (a raise helps), quit when unhappy, and can be fired anytime.
  Wages are part of the daily bill.
- **Starter set**: a small hand-made set of customers, furniture (including 3D pieces) and
  storefronts is added to a new street so it isn't empty. Player-drawn customers gradually
  replace the starter crowd: after 6 drawn customers, starter customers stop appearing.
- Everything is saved locally (`world.json`, `streets/<user>/<street>.json` per shared street, plus a
  PNG per drawing).
- **Accounts and shared streets** (stage 5): tap the globe on the street to open **Streets**.
  Sign in or create an account (email and password; the account is shared with the other apps on
  the same Supabase project, and your name comes from your shared profile). Create a street
  (you're its admin and get a 6-character invite code to share or copy), join a friend's street
  with their code, see who's on each street, make a new code (admin), or leave.
- **Playing on a shared street**: **Open street** switches to it (and the single-player street is
  one tap away under Streets). Buy a vacant lot and run your shop next to your friends'. Their
  shops, furniture, products and drawings appear on your street, their customer drawings join
  the crowd, and you can visit their shops (look, don't touch). Your shops, drawings, coins and
  books are saved to the street, so they come back on a new phone. Each shared street has its
  own coins and its own local save, so it opens straight away and works offline; changes go up
  a few seconds after you make them, and other players' changes arrive within a second or two
  (live updates; every 20 seconds if the live connection is down). The top card says "offline"
  when it can't reach the server.
- **Social** (stage 8, first part), on shared streets: **street chat** (the speech-bubble button,
  with an unread badge), **notes on shop doors** (the note button inside a shop: leave one when
  visiting a friend's shop; owners can remove notes on their shop), and **money between
  friends** (the money button): give coins, lend them, and pay loans back. Coins live on each
  phone, so the server records each transfer and the recipient's phone collects it exactly once;
  money from friends shows in the day's books.

There is no LLM or AI-generated content anywhere. All behaviour comes from hand-written systems.

## Project layout

```
shared/   Pure Kotlin (Kotlin Multiplatform, JVM target for now). No Android dependencies.
  core/geometry  Isometric projection, storefront wall geometry, affine facade mapping
  core/model     World data (lots, businesses, offerings, sprites) and the tag library
  core/anim      ProceduralAnimator: walk / idle / hop / wobble poses for cutouts
  core/sim       Furniture placement rules, the wandering shopkeeper and pathfinding
  core/npc       Customer profiles, the NPC pool and the street crowd simulation
  core/art       Image helpers: content bounds, background removal, cutout sizing
  core/world     New-world factory and JSON save format
  core/net       Supabase client over plain HTTP: auth (sign in/up, token refresh), the streets
                 API, and street sync (SyncPlanner / SyncMerge / StreetSyncer)
app/      Android app (Jetpack Compose)
  ui/street      Street canvas, renderer, cutout drawing, gestures, placement UI
  ui/draw        Drawing tool and PNG export
  ui/shop        Business and product/service editors
  ui/online      Streets list, sign-in / new account, shared street (invite code, people)
  data/          Local persistence for the world and sprite PNGs, the starter set art, and the
                 backend wiring (HTTP transport, saved session, Supabase URL/key from BuildConfig)
```

Game logic stays in `shared` so iOS targets can be added later (`iosArm64()` and so on in
`shared/build.gradle.kts`) without rewriting it.

## Building

Requirements: JDK 17+ and the Android SDK (platform 36). Open the project in Android Studio, or
from the command line:

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # install on a connected device or emulator
```

minSdk 26 (Android 8.0), target/compile SDK 36.

## Tests

```bash
./gradlew :shared:jvmTest             # game-logic unit tests
./gradlew :app:testDebugUnitTest      # Robolectric: drawing export + screen screenshots
```

The screenshot test renders the real street, shop interior, shop editor and drawing screens
with Robolectric native graphics and writes PNGs to `app/build/screenshots/`.

## Database

Block Party uses the **shared Supabase project** (`dpflpwoivvpfvainzwgd`, also home to Not A
Book Club) rather than a project of its own, so people have **one login across all the shared
apps**.

- `public` schema: shared by every app. It holds `profiles` (one per person, auto-created on
  signup) and `get_app_secret()` for Edge Function secrets. Don't put Block Party tables here.
- `block_party` schema: all of Block Party's own tables, functions and RLS policies. The schema
  already exists in the project, with the same default grants as `public` (which give `anon`
  and `authenticated` full table access, so the migration revokes those and grants only what's
  needed; still enable RLS on every table).
- Drawings go in the private `block-party-drawings` storage bucket, under `<street_id>/`.

The schema is created by [`supabase/migrations/20261108_block_party_core.sql`](supabase/migrations/20261108_block_party_core.sql).
`block_party` must also be listed under Dashboard → Project Settings → Data API → Exposed
schemas before the client can reach it.

Client setup: point the client at the `block_party` schema (in supabase-kt, pass
`schema = "block_party"` to `postgrest.from(...)` / `postgrest.rpc(...)`, or set
`defaultSchema = "block_party"` in the Postgrest plugin config).

Players are the shared `public.profiles` rows: every player foreign key points at
`public.profiles(id)`, and the read-only `block_party.profiles` view (security invoker, the same
pattern as Not A Book Club's `0060` migration) lets the API embed them, for example
`lots?select=*,profiles(display_name)`. A lot's owner must be a member of its street, so when a
player leaves, is removed or deletes their account, their shops become vacant lots.

## Status

The Block Party backend is live in the shared database. Next is the app side of stage 5
(sign-in, shared streets and syncing): see [`docs/NEXT_STEPS.md`](docs/NEXT_STEPS.md).

## Next up (from the spec's build order)

5. Supabase backend: auth, shared streets, invite codes, offline catch-up.
