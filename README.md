# Block Party

A mobile tycoon game where friends each run businesses on one shared isometric street, and every
business can be anything the player wants. Nearly everything in the world is drawn by the
players and stands in the scene as a flat paper cutout. The full design is in
[`docs/SPEC.md`](docs/SPEC.md).

This is **build stage 1**: a single-player street prototype.

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
- Everything is saved locally (`world.json` plus a PNG per drawing).

There is no LLM or AI-generated content anywhere. All behaviour comes from hand-written systems.

## Project layout

```
shared/   Pure Kotlin (Kotlin Multiplatform, JVM target for now). No Android dependencies.
  core/geometry  Isometric projection, storefront wall geometry, affine facade mapping
  core/model     World data (lots, businesses, offerings, sprites) and the tag library
  core/anim      ProceduralAnimator: walk / idle / hop / wobble poses for cutouts
  core/sim       Furniture placement rules, the wandering shopkeeper and pathfinding
  core/art       Image helpers: content bounds, background removal, cutout sizing
  core/world     New-world factory and JSON save format
app/      Android app (Jetpack Compose)
  ui/street      Street canvas, renderer, cutout drawing, gestures, placement UI
  ui/draw        Drawing tool and PNG export
  ui/shop        Business and product/service editors
  data/          Local persistence for the world and sprite PNGs
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

## Next up (from the spec's build order)

2. Drawn customer NPCs walking the street, plus a default starter set of sprites.
3. Customer simulation: tag matching, price response, sales, star ratings and templated reviews.
4. Economy basics: rent, restocking, wages, debt with a grace period.
5. Supabase backend: auth, shared streets, invite codes, offline catch-up.
