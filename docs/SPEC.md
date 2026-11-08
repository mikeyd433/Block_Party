# Shared Street Tycoon — Game Spec

Working title. A mobile tycoon game where friends each run businesses on the same shared isometric street, and every business can be anything the player wants.

## Platform & Tech

- **Platform:** Android only (for now). iOS may come later via Kotlin Multiplatform.
- **Language:** Kotlin
- **UI:** Jetpack Compose for all app-style screens (editors, menus, chat, pricing, invites)
- **Street rendering:** Isometric view drawn on a Compose Canvas. Optimize later if streets get large/busy.
- **Backend:** Supabase via `supabase-kt` — auth, shared street state, realtime street chat, storage for uploaded/drawn sprites.
- **Hard rule:** No LLM or AI-generated content anywhere in the finished product. All "alive" feeling comes from hand-authored systems (tags, templates, simulation).

## Core Concept

- A shared isometric street per friend group. Each player owns one or more storefronts on it.
- Async play: players check in whenever; shops keep running while they're away.
- Mix of cooperative and competitive play depending on context (see Events & Competition).
- Endless sandbox — no win condition.

## Streets & Membership

- Join a street via **invite link/code** only.
- **Street creator is admin.**
- **No cap** on players per street.
- A player can belong to **multiple streets**. **Everything is separate per street** — money, businesses, progression, and player level don't carry between streets.
- **Street grows as players progress** — new lots/areas unlock with progression.
- **Adjacent lots can be merged** for bigger shops.
- **Lot pricing and rent:** prime spots (high foot traffic) cost more, and all lot prices and rents scale up as the street levels up.
- Game time (day/night, weekdays/weekends, seasons) is **synced to each street's timezone**.

## Businesses

### Ownership
- Players can own **multiple businesses** (buy additional lots as they unlock).
- Players can **close a business** at any time, and a business **closes automatically** if it stays in debt past the grace period (see Economy). On closing:
  - Fixtures, decor, and created items go to the player's storage.
  - The lot becomes vacant and claimable.
- Players can **buy shops from each other**.

### Identity
- **Free-text business name** and **free-text description**.
- Choose whether the business sells **products**, offers **services**, or **both**.
- Pick tags from a **large tag library** (see Tags).

### Products vs. Services
- **Products:** inventory-based. Stock, sell, restock. Limited by shelf space.
- **Services:** capacity-based. **Simple queue with wait times.** Limited by stations/staff throughput.
- **Quality** for products and services **improves with upgrades and staff**.

### Custom Content
- Players customize the **interior and the storefront facade**.
- Most visuals in the game are player-drawn (see Player-Drawn World).
- Furniture/decor comes from **all sources**: a shop catalog, progression unlocks, and player-drawn furniture.

## Player-Drawn World

Most sprites in the game are hand-drawn by players. The street becomes a shared sketchbook made by its members.

### What players draw
- **Products and services** (icons/sprites)
- **Customer NPCs**
- **Employees**
- **Furniture and decor**
- **Buildings/facades**

### Creation tools
- **Freehand drawing** in-app, plus **image upload**.
- Drawings are saved with a transparent background and auto-scaled to fit the world.

### Paper-cutout rendering
- Drawings **stay exactly as drawn** — no pixelation or restyling.
- Characters and objects stand upright as **flat paper cutouts** inside the isometric scene, so nobody has to draw multiple angles.
- Characters flip horizontally to face left/right.
- Movement is **procedural**: walking bob, squash and stretch, slight sway, small hop on interactions. One drawing becomes a fully animated NPC.
- Facades: the player draws a flat front panel, which is mapped onto the isometric building's front face.
- Furniture: drawn as a flat cutout and placed on the isometric grid with a simple footprint and drop shadow.

### NPC pool
- Every drawn **customer NPC** is added to the **street's NPC pool** (pools are per street, like everything else).
- **Shopping preferences (tags, budget) are assigned randomly by the game**, not by the drawer.
- Drawn **employees**: the drawer chooses **public** (joins the street's job board pool, hireable by anyone) or **private** (only the drawer can hire them).
- Employee traits and wage asks are assigned by the game.

### Credits
- **Artist's choice** per drawing: show the artist's name when tapped, or stay anonymous.

### Defaults
- A **small default set** of sprites (NPCs, employees, furniture, facades) ships with the game as a starter/fallback, so a new street isn't empty. Player drawings gradually take over.

## Tags

- Large library grouped into categories. Suggested starting categories:
  - Mood (cozy, spooky, chaotic, fancy, weird, loud…)
  - Price (cheap, premium…)
  - Audience (kids, nightlife, collectors…)
  - Type (food, music, crafts, oddities…)
  - Time (morning, late night…)
- Tags are the mechanical link between "anything" businesses and customer behavior.
- New tags unlock through progression.

## Employees

- **NPC employees only** (no friends working shifts, for now).
- Proposed system:
  - Job board refreshes daily with a few applicants (name, generated look, 1–2 traits, wage ask). Occasional rare applicants.
  - Roles: Cashier (checkout speed), Stocker (restocks while offline), Server/Provider (service throughput), Greeter (pulls in foot traffic).
  - Traits with trade-offs: fast, friendly, careful, chatty, night owl, early bird, clumsy, etc.
  - Staff gain experience; raises keep them; low morale leads to quitting; owner can fire anytime.

## Economy

- **One shared currency.**
- **Fully free pricing** — players set any price.
- Pricing affects customers **both ways**: higher prices reduce how much customers buy, and price level determines which customer types show up (cheap vs. premium).
- **Running costs:** rent per lot, wages, restock/supply costs.
- **Out of money:** the business can go into **debt** and keep operating, but only for a limited grace period. If it's still in debt when the grace period ends, the business closes (contents go to storage, lot becomes vacant). Grace period length is a tunable constant (suggested default: 7 in-game days).
- **Stock sources:** a generic supplier menu **plus** friends' shops acting as suppliers.
- **Money between friends:**
  - Gifting cash freely
  - Loans with repayment
  - Buying shops from each other
  - Shared promos (e.g., buy something here, get a discount there)

## Customers

- **Generic NPC shoppers** with tag preferences and budgets, drawn from the street's NPC pool (player drawings plus defaults). They wander the street and shop where tags and prices match.
- Foot traffic is shared: popular shops pull customers who then visit neighbors.
- **Reviews:** star ratings + **templated text reviews** generated from tag matches/mismatches, wait times, price, and quality (hand-authored phrase combinations, no LLM).
- **Customer flow changes with:** day/night cycle, weekdays vs. weekends, seasons, weather.

## Progression

- Tracked at **three levels**: player level/XP, per-business reputation, street-wide level.
- Unlocks: new lots/street areas, upgrades (quality, capacity), furniture/decor, new tags.

## Events & Competition

- **Co-op:** street-wide goals and shared milestones (e.g., serve X customers this month) that unlock features for everyone; neighborhood rating rises when the whole street does well.
- **Competitive:** businesses sharing tags compete for customers; leaderboards.
- **Events happen via:** weekly schedule, player votes, random surprise events, and player-hosted events. Events can be co-op (block party) or competitive (best burger week).
- **Leaderboards track:** revenue, ratings, customers served, most-visited.
- **Competition rewards:** cash or bonuses.

## Async & Offline

- Shops **run while offline until hitting stock or storage limits**. No fixed time cap: how long a shop runs depends on how much stock it holds and its storage upgrades (plus Stocker employees restocking from storage).
- On return, player sees a summary of what happened while away (sales, reviews, traffic).
- **Push notifications: minimal/none.**

## Social

- **Street chat.**
- **Notes left at shop doors.**

## Moderation

- The **street admin (creator) can remove content and players**: business names, descriptions, uploaded/drawn sprites, chat messages, door notes, and street members.
- No report queue or automated filtering; streets are private and invite-only.

## Art Direction

- **Paper-cutout look**: hand-drawn sprites stay as drawn and stand as flat cutouts in an isometric world.
- The base world (ground, street, lot outlines, default assets) should be simple and neutral so players' drawings are the visual focus.

## Suggested Build Order

1. Single-player street prototype: isometric Canvas, freehand drawing tool, paper-cutout renderer with procedural animation, one lot, place drawn furniture, create a product with a drawn sprite and tags.
2. Drawn customer NPCs walking the street (random tags/budgets) plus the default starter set.
3. Customer NPC simulation: tag matching, pricing response, sales, star + templated reviews.
4. Economy basics: rent, restock, wages, debt.
5. Supabase backend: auth, streets, invite codes, shared lots, offline simulation catch-up.
6. Multiple businesses, closing, lot merging.
7. NPC employees, including drawn employees (public/private job board).
8. Social: street chat, door notes, gifting, loans, shop sales, shared promos.
9. Progression, events, leaderboards.
