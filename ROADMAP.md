# Joined the Game: roadmap

> **Status:** the plan to 1.0. Each version gets built when it's time for it; bug fixes for the
> current version come first.

## Where we are

**Phase 1: Bedrock parity (done, v0.1.x)**
- Real-player bots: join/leave messages, tab list, saving, respawning
- K screen and `/jtg`: invite, kick, ban, teleport, inventory
- Guide book; crouch to follow; friendly punch to stop
- Survival loop: wood → stone → copper → iron, with furnaces and crafting tables
- Combat, eating, hunting, looting chests, copying what the player is doing
- Homes: claim a free bed (villager beds count) or craft one and dig a burrow

**Expansion 1: Combat & shields (done, v0.2.0)**
- Shields: crafted after first iron, kept in the offhand, raised against arrows, creeper
  blasts and (for hurt, outnumbered or nervous bots) melee
- Better melee: critical hits, backing off between swings, weaving toward skeletons
- Retreating when losing; pillaring up when cornered
- Going back for their stuff after dying (5-minute limit, fresh start after 3 deaths in a row)

**Expansion 2: Settling down (done, v0.3.0)**
- After iron, a weighted personality roll picks the next big goal: diamonds, a home, or wandering
- Homes: move into a village house, dig a 3x3 room into a hill, or build a scrappy 5x5 hut
- Furnishing: bed, chest, torch, and a secret chest buried under the floor for valuables
- Name signs on homes ("Twiggy_'s House")
- Smarter storage: keep working amounts, store extras, hide treasure
- `/jtg focus` admin command

**Memory & nights (done, v0.4.0)**
- Long-term memory saved on each bot: plans, places, and a diary of structured events
  (died to, attacked by, killed, met, found, made, settled...). Repeats merge into one entry
  with a count, and the diary is capped
- "Remember usefulness, not everything": everyday places (tables, furnaces, ore veins) fade
  after a few days unused, and mined-out veins are forgotten straight away. Villages, the stash,
  death hotspots and unfinished houses are sticky
- Half-built huts and caves survive restarts; remembered tables and furnaces get reused
- A bed always beats a hole: home bed → free bed nearby or in a remembered village → put down
  a carried bed → craft one from wool. Wool is gathered at dusk if sheep are about
- Digging in actually works: centred over the hole, three deep, lid placed against the walls
- Bots open wooden doors, so village beds are reachable
- Wheat baked into bread properly; half-built houses are finished instead of abandoned
- `/jtg memory` admin command

---

**Farming & Personal Goals (v0.5.0)**
- Gear first: nothing but gearing up until iron tools, full iron armour and a shield
- After that, a weighted roll for each stretch of time: a diamond trip (they keep coming
  until full diamond), a home or a proper house, a personal project, or wandering
- **Mini building update**: a 7x7 log-and-plank cabin with glass windows, a door, a plank
  floor and an overhanging stone roof, furnished with bed, chests, table, furnace, torches,
  the secret stash and a name sign. Bots in a hut, cave or village house later build one and
  move in, bringing their bed and everything in their old chests
- **Farm**: a fenced 5x5 wheat field with water in the middle (under a block and torch so it
  never freezes), harvested and replanted from then on; the wheat becomes bread
- **Dog**: tame a wolf with bones, feed it when it's hurt, it follows them around
- **Doing the place up**: flowers and torches round the house
- Materials come from the chests at home first; doors and gates are shut behind them
- Smarter fights: bots size up a fight first (health, armour, weapon, shield vs how many
  mobs and how nasty) and leave hordes alone; when it turns bad they get out early by
  sprinting, pillaring 3 up (and hitting down) or digging in, and eat once clear
- Fixes: doors/gates closed behind them, water that freezes over, planks not wasted on
  scaffolding, building materials not thrown away or stored mid-build, long walks home
- `/jtg project` admin command

---

**Nether Expansion & Structure Overhaul (v0.6.0, v0.6.1)**
- Structure knowledge (real structures recognised and remembered), raiding chests and minecart
  chests, hazards (trap plates, tripwires, sculk, the warden)
- The nether: obsidian, portals, quartz, fortresses, blazes, coming home; hoglins as last-resort food
- Bows and crossbows, totems of undying, hitting ghast fireballs back, village raids on purpose
- See CHANGELOG.md for details and known issues

---

## The road to 1.0

### v0.6.x: Nether & Structure Stabilization *(current)*
- Bug-fix 0.6.0/0.6.1 thoroughly before adding another giant system.
- Portal travel reliable from anywhere: home → portal → Nether → portal → home.
- Better Nether navigation around lava, cliffs, fortresses and weird terrain.
- Better blaze and ghast combat, wither-skeleton judgment, hoglin fighting.
- Explore the Nether naturally without losing the portal.
- Proper emergency Nether food: hoglins only when food is genuinely needed.
- Finish obsidian from lava pools.
- Validate raid projects, trial vaults, outposts, villages and structure looting in real worlds.
- Finish chest ownership/looting logic so bots don't keep re-checking empty containers.
- Polish bows/crossbows, arrow reserves, switching between ranged and melee.
- Totems: shield normally, totem when death risk is actually high.
- Better structure difficulty assessment, so under-geared bots don't go sightseeing in hell.
- Keep fixing navigation, pickup, furnace, inventory and task-priority bugs as they appear.
- **0.6.2:** "raided" only once a structure's containers are all done; looted containers remembered for good.
- **0.6.3:** bundle support, boxing in, villager beds, ore memory (done).

### v0.7.0: Enchanting & Inventory *(done)*
- XP has a purpose: build an enchanting table (diamonds, obsidian, books), then bookshelves.
  The whole setup is planned at once: the table only goes where all 15 shelves and the way in
  fit too (digging a room out if needed).
- Resource sourcing: lapis, sugar cane (paper, books), leather (cows), obsidian.
- Gear scoring that counts enchantments: good enchanted gear beats newer-but-worse gear.
- Enchantment preferences: each bot prefers the best, but has its own favourites; an enchant
  it doesn't want goes through the grindstone (getting some XP back) and it tries again.
- Storage behaviour: actually hiding valuables, putting things away after mining runs,
  remembering what's in which chest and going back for it when needed.
- Better inventory management.
- Putting themselves out with a water bucket when on fire.

### v0.7.1: Better Homes *(done)*
- A new house tier and several house blueprints, all with storage rooms.
- Proper chest placement: double chests.
- More intelligent furniture layouts.

### v0.7.2: Large Homes, Names & Personality *(done)*
- Fixes from testing 0.7.1.
- A naming/personality update (details to come).
- A two-storey house, bigger storage rooms, richer interiors; maybe personality/biome variants.

### v0.7.3: Trial Chambers & Mace *(done)*
- Deep Trial Chamber logic: go back to remembered chambers, fight trial spawners, know when a
  wave is done, collect trial keys, find vaults, use keys correctly, remember which
  vaults/sections are done.
- Ominous Trials for well-geared bots, and knowing when one is too dangerous and leaving.
- Mace loot obtained legitimately; basic mace combat (falling smash attacks, no fancy movement yet).
- Better ranged/melee choice in trial chambers.
- Structure states: discovered, partly explored, raided, exhausted.

### v0.7.4: Trial Chamber fixes *(done)*
- Vaults that actually open (no crouching, standing inside the vault's range, seeing any face),
  vaults opened between trials, locked vaults remembered and gone back to with the right key.
- Runs that start, and keep going through worn-out tools, other goals giving up and restarts.
- No running from mobs in a puddle; hiding that isn't working is given up on.

### v0.7.x: Enchanting & Chamber Polish
- Fix the inevitable funny problems from 0.7.0.
- No wasting XP on rubbish enchantments; better bookshelf and enchanting-room layouts.
- Better wave survival and vault navigation.
- Prepare before going back to a hard structure, instead of rushing straight back after dying.
- Tune mace combat so they don't become professional fall-damage victims.
- Maybe wind charges once normal mace combat is stable.

### v0.8.0: Netherite *(done)*
- Search for ancient debris on purpose, mine it safely, smelt scrap, keep enough gold, craft
  ingots, get the smithing template, use smithing tables, and upgrade valuable enchanted diamond
  gear rather than replacing it. Netherite is late-game gear, not just another ore.

### Later: Maps & Treasure
- Read buried treasure maps (the X is stored on the map), travel there, dig down to the chest
  (sand, gravel, underwater ones too); explorer maps to monuments and mansions; maps in general.

### v0.8.1: Brewing
- Build and remember brewing stands, gather nether wart and ingredients, know the useful
  recipes, carry situational potions and know when to drink them (fire resistance for
  nether/lava trips, healing/regeneration for raids, strength for big fights, slow falling for
  the End, night vision for exploring).

### v0.8.2: Expedition Preparation
- Proper expedition prep: food, arrows, blocks, spare tools, potions, totem, ranged weapon, armour.

## Current direction (decided 2026-10-11)
1.0 is far future. The focus now: **bugs, then a goal-system overhaul, then relationships.**
The big problem found in play: bots are too repetitive. They do the same things in the same
order, even when the project names differ.

### v0.8.x: Bug fixes
- Fix what turns up in play (and verify the untested bits of 0.8.0: bastion raids, template copies,
  shipwreck chests).

### v0.9.0: Goal system overhaul + relationships (the biggest update yet)
Updated 2026-10-12: relationships come in 0.9.0 alongside the overhaul, not after it.
- **More personal goals, and variations of each** (old ones included), so two bots on "the same"
  project still do it differently. Overhaul the core parts of the mod to feel more realistic.
- **Relationships** (see below), including **romance**: bots can fall in love. Each bot has an
  orientation (straight, gay or lesbian), and that's as far as sexuality goes. Trans bots are a
  possible later feature, the user's call, not now.

- **Roster overhaul:** more celebrities, famous people and Minecraft YouTubers. The random
  gamertag names are phased out, except the ones that became regulars: TorchBearer99,
  AxolotlAndy, BedrockBenny and MudMuncher. YouTubers with real accounts wear their real
  skins (fetched, like DanTDM); people without an account get a skin drawn for the mod (like
  MadisonBeer). Each gets a personality preset.

#### The goal system (living, not looping)
- **Needs instead of a ladder:** drives (safety, hunger, gear, wealth, home, curiosity, social,
  creativity...) that rise and fall; personality sets how much each matters; activities are scored
  by the needs they meet. No more single wooden→stone→iron→diamond script for everyone, and no
  "nothing else until full iron" lock-out.
- **Weighted choice, not always the max:** similar bots in similar spots still do different things.
- **Boredom and novelty:** repeating the same kind of thing gets less appealing; new things and
  favourites pull.
- **Many roads to the same need:** iron from mining, caves, mineshafts, shipwrecks, trading, golems;
  food from hunting, farming, fishing, trading, village farms.
- **Commitment: knowing when NOT to interrupt** (the lesson of 0.8.1, where most bugs were two goals
  undoing each other). Every activity has a cost to interrupt that grows with progress made; a
  new idea has to beat the current one by more than that, not just score higher. Interruptions are
  decided before taking over, never by taking over and then quitting. A task that is interrupted
  resumes where it was. Emergencies still cut through everything.
- **A plan for the day,** with spontaneous detours when something interesting turns up.
- **Hard rule: survival is never optional.** Emergencies (a fight, starving, drowning, burning, lava,
  caught out at night unprepared) are hard overrides above the needs system, exactly as now. Needs
  and boredom only choose between normal activities; a bot can't be too creative to eat.

#### Relationships
- Who they've met, friends and rivals, helping each other; built on the new goal system so it
  changes what they choose to do (and, later, co-op and willingness to share).
- Romance: attraction that grows from time spent together, falling in love, couples who stick
  together (share a home, do things together). Orientation decides who can fall for whom.

---

### v0.8.x: Bases & Property Overhaul
- Several house blueprints, not everyone ending up in the same cabin. Styles by
  biome/resources/personality: wood cabin, stone cottage, two-room house, hillside home, larger
  lodge, desert-style home, taiga/spruce home.
- Houses upgrade as the bot gets wealthier; better site choice and terrain prep.
- Smarter storage rooms and chest organisation; dedicated workstations (furnaces, enchanting,
  brewing, crafting).
- Farms, animal pens and pet areas built into the property; paths between the important parts.
- Property upgrades: gardens, fountains, wells, sheds, docks, campfires, lookout towers, statues, banners.
- Landmarks away from home (cairns, signs, towers, outposts, bridges, trail markers),
  remembered and revisited.
- Bases survive interruptions and restarts without losing project state.

### v0.9.0: Personal Life Expansion
- The big "Minecraft isn't only progression" update: a much bigger personal-goal pool.
- Bots may: tame cats, fish, start animal farms, collect flowers, wood types, dyes/wool, music
  discs, armour trims and pottery sherds, grow decorative trees, make gardens, banners, trophy
  displays, a library, a pond, keep bees, take boat trips, visit landmarks, build distant
  outposts, improve their property, explore unusual biomes, go sightseeing.
- Persistent personal wants in memory: "I want a cat", fail to find one, remember, try again days later.
- Still personality-weighted. Progression continues, but after full iron the bot increasingly
  has an actual life.

### v0.9.x: Social Foundation
- Relationships come late (bots have lives worth reacting to by then).
- Opinion/familiarity towards known players and bots, built from meaningful events, not just
  standing nearby.
  - Positive: helping in fights, sharing food/resources, recovering someone's gear,
    travelling together, working on nearby projects, defending a home.
  - Negative: repeated punching, stealing, killing, taking valuables, repeated hostility.
- Personality affects forgiveness and attachment.
- Friends help each other fight, share food and spare materials, visit, travel together.
- Disliked players/bots get avoided and denied resources, maybe treated as rivals later.
- `/jtg memory` shows relationships and their main causes.

### v0.9.x: Living Together & Group Projects
- Friends can choose to live together: shared houses with several beds, shared storage and farms.
- Small bot settlements that emerge because bots like each other (not scripted villages).
- Building near friends rather than hundreds of blocks away.
- Group mining trips, group structure raids, help with big builds, shared farms and workstations.
- Rivalries and fallouts: moving away, no more sharing, arguments shown through behaviour,
  optional bot-vs-bot combat behind a config toggle.

### v0.9.x: Advanced Structures & End Preparation
- Every meaningful Overworld/Nether structure profile: desert pyramids, jungle temples, ocean
  monuments, woodland mansions, ancient cities, bastions, fortresses, trial chambers, pillager
  outposts, strongholds (stronghold exploration especially).
- Smarter deep dark: wool where useful, avoid sensors/shriekers, watch the danger build up,
  loot carefully, never fight the warden as ordinary combat.
- Bastions: better piglin, brute and chest awareness.
- Ocean content only once underwater survival is solid.
- Bots understand structure objectives, not just "loot the nearest chest".

### v1.0: The End & Complete Minecraft Life
The graduation test: bots do it all themselves.
- Ender pearls, blaze powder, eyes of ender; throw and follow eyes without wasting them all.
- Find, remember and explore the stronghold; find the portal room; fill the missing frames.
- Prepare properly: good enchanted gear, strong ranged weapon, arrows, food, blocks, water,
  potions, totem.
- The End safely: don't stare at endermen, respect the void, bridge carefully, deal with the
  obsidian towers.
- The dragon: shoot reachable crystals, deal with caged ones, avoid dragon breath, ranged while
  it flies, melee when it perches, retreat and heal when needed, track the remaining crystals,
  and finish it without human help. Remembered as a permanent milestone.
- End gateways, outer islands, End cities: fight shulkers, loot properly, get shulker boxes and
  an elytra. Basic safe elytra use (not instant crater). Shulker boxes as expedition storage.
- Get home safely after End trips, and keep playing afterwards: the credits aren't the end.

### v1.0: Full Villager Support & Economy
Trading becomes a full progression path alongside mining:
- Recognise villagers, professions, workstations, and trade tiers.
- Inspect trades and judge whether they're actually useful.
- Earn emeralds naturally through gameplay (selling sticks, crops, etc.).
- Buy useful supplies: food, tools, armor, enchanted books, arrows.
- Understand librarians and enchanted-book trades (Mending, Unbreaking, etc.).
- Remember useful villagers, where they live, and what they sell in long-term memory:
  - *Example in `/jtg memory`: Librarian — Mending — Village at 420, -180*
  - *Fletcher — sticks → emeralds*
  - *Armorer — diamond leggings*
- Revisit known villagers when trades restock; understand that trading unlocks higher tiers.
- Avoid wasting emeralds on garbage trades.
- Factor trades into big-picture goal planning: "I want Mending" → "Librarian at 420, -180 sells it" → "Gather sticks to get emeralds from Fletcher" → buy the book.
- Defend and protect their valued trade partners during raids.
- Notice when a remembered merchant dies and clear/update that memory.
- Move between naturally generated villages to scout for better trades.

---

## v1.1 — Villages & Trading Halls (Beyond 1.0)
Advanced villager engineering and industrial settlements:
- Deliberately design and construct dedicated trading halls.
- Safely transport villagers into designated stalls (boats, minecarts, lead pathing).
- Assign specific professions using targeted workstations.
- Reroll trades by breaking and placing workstations until desired trades (e.g. 1st tier Mending) appear.
- Maintain valid bed and workstation linkages so merchants restock properly.
- Cure zombie villagers with weakness potions and golden apples for trade discounts.
- Establish emerald-producing farms (sugarcane, melon/pumpkin, iron farms) to feed the economy.
- Fortify and protect trading complexes against raids and mob sieges.
- Share trading hall facilities between allied bots.

---

## Parked for later
- **Background smelting:** load the furnace and go do something else, then come back when the
  batch is done (from far away, never forgetting it). Tried it in v0.5.6 development; needs
  "check every remembered furnace, not just the nearest" plus a test where batches are split
  across several furnaces. For now bots wait at the furnace.

## Ideas parked from research
Things players asked for in the Bedrock version that could also come later:
- Nether and End trips; helping kill the dragon
- Better survival sense (fall heights, lava, low-health retreat)
- Chat commands ("DizzyLead, follow me")
- Boats and horses
- Tidy up after themselves: dig down the dirt/cobble pillars and bridges left from
  chopping tall trees (the "cobblestone T" seen in testing)
