# Joined the Game: how it works

NeoForge 26.3 · mod id `joinedthegame` · written for version 0.8.0.

This is the full picture of what the mod does and how a bot decides what to do next. Nothing in
it uses an AI language model: every decision is ordinary game logic (scores, rules and memory)
running on the server.

---

## 1. What the mod is

Joined the Game fills your world with **AI players**: bots that join like real people, get a
name and a skin, and then just *play Minecraft*. They punch trees, make tools, mine, fight, build
houses, farm, tame dogs, enchant, go to the Nether, raid trial chambers and work their way up to
netherite. Each one has a personality, so two bots in the same world live different lives.

## 2. Bots are real players

Each bot is a **real (fake) server player**: the same kind of entity as you, not a mob dressed up
as one. That has a few big consequences:

- **They follow your rules.** Same reach, same mining speed, same hunger, same fall damage, same
  menus (crafting, furnaces, enchanting tables, smithing tables, grindstones and vaults are all
  used through their real menus).
- **The world treats them as players.** They load chunks around themselves, get advancements,
  trigger trial spawners, get their own reward from every trial vault, anger piglins by opening
  bastion chests, and show up in the player list.
- **No cheating.** They don't see through walls (with one small exception for ore within 4 blocks
  while mining, which stands in for "noticing it as you dig"), don't teleport, don't spawn items.
  Everything they own was mined, crafted, looted or traded for.

### Joining

- `/jtg invite <name>` brings a bot in. Up to `maxBots` (default 10) can be in at once.
- Bots named after real people (Duckatyt, cozmoiscool130, Redlantern8808, DanTDM,
  stampylongnose) wear their **real skins**, fetched from Mojang once and cached. If the fetch
  fails, they join anyway with the default skin.
- **MadisonBeer** is a real person who isn't a Minecraft player (someone else owns the name), so
  her skin is drawn for the mod and ships inside it. It shows on any client that has the mod
  installed (single player always does); on a vanilla client she has the default skin.
- Bots that were in the world come back when you rejoin. Only `/jtg kick` or `/jtg ban` removes
  one for good.
- With `pauseWithoutPlayers` on, bots stand still while no real player is online.

---

## 3. The brain: what happens every tick

Every bot has a **brain** that runs 20 times a second. In order, each tick it:

1. **Rolls a big goal** (every 5 seconds, if it hasn't got one). See section 5.
2. **Picks what to do right now** (every half second): every goal gives a priority score, and the
   highest one wins (with some rules about switching, below).
3. **Runs the current goal** one step.
4. **Watchdog:** checks the current goal is actually getting somewhere (section 13).
5. **Moves:** the navigator follows its path one step: walking, jumping, swimming, digging
   through, pillaring, opening doors.
6. **Habits:** reflexes and background noticing that happen whatever it's doing:
   - raise the shield at incoming arrows, put out fire with a water bucket
   - notice structures (every 5 s), ores (every 3 s) and players nearby (every 2 s)
   - pack and unpack the bundle when the bag is nearly full or empty
   - creep (crouch) near sculk sensors
   - wear the gold helmet in the Nether, put the totem in the off-hand when in danger
7. **Applies the controls:** the movement keys, look direction, use/attack, the same inputs you'd
   press.

---

## 4. Goals: what to do *right now*

A **goal** is one kind of activity. Each goal scores itself from 0 (not interested) up. The brain
runs whichever scores highest. These are the goals and their usual scores:

| Score | Goal | What it is |
|---|---|---|
| 100 | **Interrupted** | You punched it: stop and look at you for 3–6 seconds. |
| 88–94 | **Combat** | A monster's coming: fight, shoot, block, or retreat (braver bots score higher). |
| 85 | **Eat** | Hungry and has food: eat it the normal way (hold right-click). |
| 75–90 | **Sleep** | Night and a bed it can get to (its own, or a camp bed for nomads). |
| 72 | **Recover** | It died: run back for its stuff before it despawns (5 minutes). |
| 70 | **Follow** | You crouched at it: it follows you for a while (`followSeconds`). |
| 68 | **Shelter** | Night, no bed, not geared for the dark: dig in and wait for morning. |
| 60 | **Leash** | Too far from the nearest player: head back (configurable, see section 12). |
| 58 | **Project (committed)** | Already standing in a trial chamber, or running a chamber run. |
| 36–65 | **Hunt / food** | Low on food: hunt, harvest crops or hay, bake bread, cook meat, fetch food from home. 65 when starving. |
| 40–50+ | **Deposit** | Bag getting full: take things home (urgency rises the fuller it is). |
| 42–50 | **Settle** | Find or build a home (50 while a build is in progress). |
| 45 | **Project** | The current personal project (farm, dog, enchanting, chamber, netherite...). |
| 45 | **Home** | Claim a bed / move into a starter hut. |
| 40–45 | **Nether** | A Nether trip (40 while over there with no other plan). |
| 46–50 | **Farm** | Tend its farm (more when hungry). |
| 44–47 | **Pets** | Feed a hurt dog, sit a stray dog in the yard. |
| 46 | **Furnace** | Empty a furnace it left something in. |
| 35 / 46 | **Loot** | A chest nearby (46 inside a structure). |
| 48 | **Pickup** | Useful items lying on the ground. |
| 25–50 | **Tag along** | A player heading off exploring: sociable bots come too. |
| varies | **Copy mining** | A player mining nearby: come and mine with them. |
| 30–40 | **Progression** | Gear up: the next tool, armour piece or material (section 6). |
| 10–41 | **Explore** | Nothing better to do: wander, see what's out there. |

### Switching rules

Picking the highest score every half second would make bots flighty, so there are rules:

- **Clearly better:** a new goal has to beat the current one's score by **more than 5** to take
  over. (The current goal's score is re-checked each time, so a goal that has done its job loses
  its claim.)
- **Finish the block:** half way through breaking a block, only an emergency (score 80+) interrupts.
- **Mining for gear is protected:** while gearing up underground, only a fight, food, smelting, a
  full bag, the player, an emergency (60+) or a structure it just broke into can pull it away.
- **Cooldowns:** a goal that just finished or failed takes a short rest so the bot doesn't loop.
- **Dimension:** in the Nether, the "home life" goals (sleep, settle, farm, explore, hunt, deposit,
  progression...) switch off. Projects about the Nether (netherite) keep running.

---

## 5. Big goals ("focus"): what to do *with its life*

On top of moment-to-moment goals, each bot has **one big goal** at a time:

| Focus | Means |
|---|---|
| **HOUSE** | Build (or upgrade) a proper home. |
| **DIAMONDS** | A diamond trip (15–25 minutes, then back to normal life). |
| **PROJECT** | A personal project (section 7). |
| **NETHER** | A blaze-rod trip to the Nether. |
| **WANDER** | Go and see the world (10–20 minutes). |

**Gear first:** until a bot has iron tools, iron armour and a shield, it has *no* big goal. It
just gears up. (A project already under way, or one you started with `/jtg project`, isn't
cancelled by this.)

After that, when it has no big goal it **rolls for one**, weighted by its personality:

- **Diamonds** ~ `0.5 + miner×0.6 + diligent×0.3 − homebody×0.2 − builder×0.2`.
  With a diamond pickaxe already, much less: `0.25 + miner×0.4`. Zero once it's in full diamond.
- **House**, while homeless ~ `0.4 + social×0.6 + diligent×0.4 + careful×0.2`; for an upgrade
  ~ `0.2 + diligent×0.5 + social×0.3`. Both get `+ builder×0.8`. Zero once the home is good
  enough. Nomads on the road want none; a nomad that's geared up or desperate for storage wants
  one a lot.
- **Project** ~ `0.35 + social×0.3 + (1−miner)×0.3 + homebody×0.6 + animal lover×0.3 +
  scholar×0.3`, plus 0.25 for each homely project waiting (up to 3).
- **Nether** ~ `0.25 + brave×0.4 + explorer×0.3`, only with a diamond pickaxe (or a known
  portal) and fewer than 6 blaze rods.
- **Wander** ~ `0.05 + explorer² × 0.8 + nomad×1.2`.

A big goal can only be dropped by the goal that owns it (a failing house build can't cancel a
chamber run, say).

---

## 6. Gearing up: the wish list and the planner

### The wish list

The **progression** goal walks down a list of things the bot wants, in order, and works on the
first one it hasn't got:

1. Wooden pickaxe (skipped if it's carrying cobble or iron: a broken pickaxe mid-mine is
   replaced from what's on hand).
2. Stone sword (fighters first), stone pickaxe, stone sword, stone axe, a furnace.
3. Coal (for torches and smelting).
4. Iron pickaxe, a shield, iron sword, iron armour, iron axe. (A piece is made in diamond instead
   if it already has the diamonds.)
5. Diamond pickaxe, diamond sword, diamond armour.
6. A bow when it has the string, and arrows when it has feathers.
7. A mace once it has a heavy core; a bundle once it has string and leather.
8. Then stockpiling: logs, coal, iron.

### The planner: working backwards

For each wish, the **planner** works backwards to the very next concrete step:

> want an iron pickaxe → needs 3 iron ingots + 2 sticks → ingots come from smelting raw iron →
> raw iron needs a stone pickaxe and a trip underground → *next step: go mine iron.*

The answer is always one step: **mine**, **craft**, **smelt** or **fetch from home**. The bot
does it, then asks again, so the plan adapts to whatever it actually found. It uses the
**game's own recipe book**, so it knows every recipe in your modpack's version. It prefers
things in its bag, then things in its home chests, then crafting, then mining.

### Mining like a player

- **A proper haul.** Once it's gone underground it mines a real amount, not just what the next
  item needs: iron and coal **16**, gold and copper **8**, lapis and diamonds **12**, up to
  **32**. It keeps going while it's finding ore. It stops after 3 minutes without a find (diamonds
  5) or 12 minutes in all (diamonds 15), and keeps what it got.
- **How it finds ore:** ore in sight within 16–24 blocks, ore showing in a tunnel wall, ore
  within 4 blocks of where it's digging, veins it remembers from before. Nobody walks past
  diamonds or coal, so it grabs those on the way.
- **No ore in sight:** it digs a **staircase down** to the right depth for that ore (iron y 14,
  diamonds y −54, gold y −16, ancient debris y 15 in the Nether), then **tunnels**, turning now
  and then.
- **Stays down there:** it puts down its own crafting table or furnace (from the cobble it
  carries) instead of climbing back to one it remembers, unless that one is close.

---

## 7. Personal projects

When the big goal is **PROJECT**, the bot picks one by a weighted roll (only from those available
right now):

| Project | Available when | Pulls hardest on |
|---|---|---|
| **Farm** | Has a home and no farm near it. | Diligent, homebodies, animal lovers. |
| **Wolf** | No dog yet and 3+ bones (bag or home). Twice the pull for a first dog. | Animal lovers. |
| **Decorate** | Has a house and hasn't decorated. | Homebodies, builders, sociable. |
| **Bow** | No bow or crossbow. | Brave, explorers. |
| **Enchant** | Gear worth enchanting. 1.5× for a first table. | Scholars, and levels piling up. |
| **Raid** | A known structure worth raiding. | Brave, explorers. |
| **Village raid** | Well armed, well fed, knows a village. | Brave, reckless. |
| **Trial chamber** | Knows a chamber and is ready for it. | Brave, explorers, reckless, nomads. |
| **Netherite** | A diamond pickaxe and real diamond gear to upgrade. | Miners, diligent, brave. More when half way there. |

"Away" projects (raids, chambers, netherite) count for half while homely ones are waiting, and
homebodies like them less. A project that fails is left alone for 20 minutes.

### The big projects in brief

- **Enchanting:** builds a full setup (table, 15 bookshelves, its own room if needed), only
  enchants at level 30 with full shelves, and judges the result. If it's bad and worth retrying,
  it uses a grindstone and goes again (3 tries at most). Personality decides what counts as
  "good". Even the fussiest bot keeps Sharpness IV + Unbreaking III.
- **Trial chambers:**
  - **Readiness:** armour, a real weapon, food, health, a shield or bow, blocks. Brave and reckless
    bots go earlier, and each death raises the bar.
  - **Spawners, one at a time:** get in sight to start it, fight the wave, hunt down stray wave
    mobs, wait for the reward.
  - **Vaults:** opens every vault it holds the key for, room by room. Each player gets their own
    reward from a vault.
  - **Locked vaults are remembered:** it goes back to one once it has the key.
  - **Also:** loots the chamber's chests, and tries the ominous round when it's well geared and has
    a bottle.
  - **Leaving and returning:** leaves when hurt or out of food, and comes back later.
  - **Memory:** everything about each chamber is remembered (beaten spawners, deaths, why it left).
- **Netherite:**
  - **Which gear:** only its real loadout (worn armour, best sword, pickaxe, axe and spear),
    enchanted pieces first.
  - **Template:** from a bastion (a treasure bastion if it knows one, since those always have a
    template). It copies the template with 7 diamonds and netherrack when more pieces need doing.
  - **Ingots:** 4 netherite scrap (smelted from ancient debris, strip-mined at y 15) and 4 gold.
  - **Upgrade:** at a smithing table, keeping every enchantment.
- **Nether (blaze rods):** gets obsidian (from a lava pool and a water bucket, like players), builds
  and lights a portal, mines quartz, finds a fortress, hunts blazes, comes home through the same
  portal.

---

## 8. Personality

Every bot has **12 traits**, each from 0 to 1, rolled from its name (so the same name always gets
the same personality):

| Trait | High means... |
|---|---|
| **Miner** | Loves digging and diamond trips. |
| **Explorer** | Wanders further, explores more, goes to the Nether and chambers sooner. |
| **Social** | Wants a home, tags along with players, decorates. |
| **Brave** | Fights longer, flees later, goes on adventures. |
| **Reckless** | Takes bigger risks and needs less preparation (most bots have little). |
| **Diligent** | Keeps at work, gears up harder (every bot has at least some). |
| **Homebody** | Prefers home projects, less adventure. |
| **Animal lover** | Dogs, farms, pets (rare to have much). |
| **Scholar** | Enchanting, and fussier about enchantments. |
| **Hoarder** | Keeps more stuff, stores more, throws out less. |
| **Builder** | Wants a home and bigger houses sooner. |
| **Nomad** | (Rare: ~6% of bots.) No home; camps wherever night falls; settles only when geared or desperate for storage. |

The five real-person bots have hand-made personalities, e.g. **Redlantern8808** is a careful,
diligent veteran and a pure nomad; **Duckatyt** is reckless, loves enchanting and builds.

Personality never makes a bot stupid. It changes *which* sensible thing it picks and *when*,
not whether it eats, fights back or gets out of lava.

---

## 9. Memory

Each bot remembers (saved with the world):

- **Places:** ores it saw, its furnaces, crafting tables and smithing tables, portals (kept for
  ever, so it can always get home), and every structure it found: villages, mineshafts, temples,
  fortresses, bastions (treasure bastions separately), trial chambers.
- **A diary:** structured events such as who it met, what attacked it, what it killed, what it
  built, made, tamed and found, where it died. `/jtg memory <bot>` shows it.
- **Home:** its bed, its house and house plan (so a half-built house survives a restart), its
  chests and a snapshot of what's in them, and its **secret stash** for valuables.
- **Trial chambers:** per chamber, the spawners beaten (normal and ominous), entrances, deaths,
  when it may come back, why it left, how far it got, and the vaults it couldn't open yet.

---

## 10. Homes, storage and inventory

- **Settling:** a first night in a starter hut, a dug-out cave home or a claimed bed. Then
  proper homes in tiers, **cabin → house → large (two-storey) house**, picked from blueprints
  (cottage, stone house, longhouse, two-storey) to fit the site and its personality. It builds
  layer by layer, with storage rooms, double chests, furniture and ladders.
- **Upgrades:** a bot outgrows its home when it owns enough stuff (builders sooner).
- **Storage:** extras go into the chests at home. Treasure goes into the **secret stash** under a
  floorboard (dig it up, stash, put the floor back), or into normal chests if it has no stash.
- **Loadout:** it carries what the job needs and a working amount of basics (tools and weapons,
  food, 32 torches, a bucket, a bed, a crafting table, some planks and sticks, a stack of its main
  building block, a few diamonds). Each project adds its own kit.
  - **What's never thrown out:** treasure.
  - **Old gear:** obsolete wood or stone gear is dropped, and better old gear is stored.
- **Fetching:** when the planner finds something it needs in its chests, it walks home and
  takes it rather than making more.
- **Dogs** sit in the yard around the house, out of the way, and move house with it.

---

## 11. Fighting

- **Sizing up:** it compares its **combat power** (armour, weapon, health, shield) against the
  **danger** (what's there and how many) before committing. Braver bots take worse odds.
- **Fighting:**
  - melee with proper attack timing and the shield up between swings
  - bows at range, but never at a **breeze** (it deflects arrows)
  - spears jab from 2–4.5 blocks
  - a mace smash when there's a safe ledge or a wind charge and headroom
- **Retreating:** when outmatched or hurt:
  - **runs** if it can sprint
  - **digs in** or **boxes itself in** against skeletons
  - **pillars up** against zombies
  - gives up on hiding that isn't working (still exposed, or still taking hits) and fights or runs
  - the log says why it retreated
- **Reflexes:** shield at incoming arrows, totem in the off-hand when in danger, water on itself
  when on fire, knocking ghast fireballs back.
- **You:** bots never fight players. A punch makes it stop and look at you for a few seconds, and it goes in the diary ("Got punched by ..."); whatever it was doing waits 30 seconds.

---

## 12. Players, anchoring and config

### Around players

- **Crouch while looking at a bot:** it follows you for `followSeconds`.
- **Explore off somewhere:** sociable bots may tag along.
- **Mine near a bot:** it may come and mine with you.
- **Anchoring:** bots that get too far from the nearest player head back. All configurable:

| Config | Default | Does |
|---|---|---|
| `leashToPlayers` | on | Whether bots head back at all. |
| `maxDistanceFromPlayers` | 96 | How far (blocks) before heading back. 0 = no limit. |
| `comeBackToPercent` | 40 | How close they come back, as % of the max. |
| `anchorToSpectators` | off | Spectating players count as someone to stay near. |
| `leashOnExpeditions` | off | Pull back bots on a long trip (Nether, chamber, netherite, raid) too. |
| `driftTowardPlayers` | on | Exploring bots far from everyone wander back toward players. |

Other config:
- `maxBots` (10)
- `opsOnly` (only ops manage bots on servers)
- `pauseWithoutPlayers` (on)
- `followSeconds` (300)
- `lootChests` (on)
- `giveGuideBook` (on)

### Commands

| Command | Does |
|---|---|
| `/jtg invite <name>` · `kick` · `ban` · `unban` | Manage bots. |
| `/jtg list` | Who's in and what each is doing. |
| `/jtg tp <bot>` | Bring a bot to you (wakes it if asleep). |
| `/jtg focus <bot> <house\|diamonds\|wander\|project\|nether\|none>` | Set its big goal. |
| `/jtg project <bot> <farm\|wolf\|decorate\|bow\|raid\|enchant\|trial_chamber\|netherite\|house>` | Start a project now. |
| `/jtg build <bot> <cottage\|stone_house\|longhouse\|two_storey\|cabin>` | Build a particular house. |
| `/jtg memory <bot>` | Its memory and diary. |
| `/jtg inventory <bot>` | Its inventory. |
| `/jtg guide` | The guide book. |

---

## 13. Safety nets

Bots are complicated and the world is messy, so there are layers that catch trouble:

- **Freeze watchdog:** any goal that makes no progress for 30 seconds is dropped for a minute and
  the reason logged. Progress means moving, its bag changing, breaking something, using
  something, fighting, sleeping, or waiting on the game on purpose (a furnace, a trial spawner's
  reward).
- **Stuck detection:** trying to walk without getting anywhere for 20 seconds makes it wiggle
  free and try something else.
- **Progress-based limits:** long jobs (mining hauls, hiding) stop when they stop working, not
  just after a fixed time.
- **Stuck in a block:** sand fell on it, or its feet are inside a block: it digs out.
- **Getting at things:** it uses the game's real rules for each interaction:
  - reach is eye to block
  - a vault wakes up only for a player within 4 blocks
  - a chest won't open with a block on its lid, so it clears the lid
  - a chest it can't see from anywhere (inside a shipwreck hull, say) gets dug into
- **Never digs into its own house** to get somewhere (that's what the door's for). Houses cost the
  pathfinder extra, so it walks round.
- **Projects own their focus:** one goal giving up can't cancel a different one that's under way.
