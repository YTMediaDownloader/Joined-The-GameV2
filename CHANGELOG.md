# Joined the Game — version history

NeoForge 26.3 · mod id `joinedthegame` · bots are real (fake) server players, no LLMs.
Jars from 0.3.2 on are kept in [`releases/`](releases/). Earlier ones weren't saved.

---

## 0.7.3 Trial Chambers, Mace & Spear
**Trial Chambers** (built on the real 26.3 trial spawner and vault code)
- A proper chamber run as a project: readiness first (armour, a real weapon, food, health, a shield or a bow, blocks; brave and reckless bots go earlier, careful ones prepare more, and each death in there raises the bar), keys and ominous bottles fetched from the stash, then travel there and work through it.
- Trial spawners one at a time: get in its sight to start it, stay with it while the wave's fought, hunt down any of its wave that wandered off (the spawner knows which mobs are its own), and only count it beaten when the game says so (its state), then wait for and pick up the reward.
- Vaults: each opened with the right key from where it can really be reached; a vault remembers which players it has rewarded, so each bot (a real player) gets its own go.
- Ominous trials: a well-equipped bot with an ominous bottle drinks it at the chamber (Bad Omen turns into Trial Omen when a spawner sees it) and goes round again for the ominous vaults.
- Chamber chests and barrels, and useful drops (breeze rods, keys), are picked up as part of the run.
- Committed: once in, a bit of iron or a tree doesn't pull it away; fights, eating and emergencies come first, and it carries on after. Sleep and digging in wait while in a chamber.
- Remembered per chamber: spawners beaten (normal and ominous), entrances, deaths, why it left, how far it got. It leaves when hurt or out of food and comes back later; restarts carry on where it was.
- Chamber loot is kept: trial keys, ominous keys and bottles, the heavy core, music discs, banner patterns and trim templates are treasure (to the stash); wind charges, breeze rods, potions and pearls are kept.

**Mace**
- Crafted from a heavy core (dug out of the stash) and a breeze rod.
- Smash attacks only when there's a real, safe chance: stepping off a ledge onto something 2-6 blocks below, or a wind charge at its feet with plenty of headroom, solid ground all round and no lava or drop near. Never from a dangerous height. A missed smash lands, and it carries on with its normal weapon.
- Density, Breach and Wind Burst scored for enchanting.

**Spear**
- Spears jab (26.3: a piercing stab at anything along your aim from 2 to 4.5 blocks, not closer, on a full charge). A bot whose best weapon is a spear keeps that distance and jabs; Lunge scored for enchanting. Spears are kept as weapons (they have no weapon tag, so they used to look like junk).

**Combat**
- Never shoots at a breeze: it bounces every arrow straight back. Breezes are melee only.

**Freeze watchdog**
- Any goal that's getting nowhere for 30 seconds (not moving, nothing changing, not fighting, not waiting on the game on purpose) is dropped for a minute and the reason logged, instead of standing frozen.

---

## 0.7.2 Large Homes, Names & Personality
**Real players**
- Five new bots named after real players, wearing their **actual skins** and playing the way they do:
  - **Duckatyt**: reckless in a fight, loves enchanting his gear, and a builder.
  - **cozmoiscool130**: a pure builder. He'll mine and explore when he has to.
  - **Redlantern8808**: a hardened veteran and a nomad. Smart, doesn't pick fights he doesn't need, beds down wherever night falls and is off exploring again in the morning. Sets up a base once he's geared up, or when he badly needs somewhere to put things.
  - **DanTDM**: a friendly adventurer, mad about pets, loves a lab and his gadgets (enchanting), always off exploring.
  - **stampylongnose**: Stampy, a big builder of his lovely world: homely, sociable, gentle, loves his animals and his farm.
- Skins are looked up from Mojang in the background when the bot joins (it waits a few seconds for it, then joins anyway with the default skin if the lookup fails). Found skins are saved (`config/joinedthegame-skins.json`), so restarts don't look them up again. They're on the bot list in new and existing worlds.

**Personality**
- Two new traits: **builders** (build their own house straight away, upgrade it sooner, do the place up; less keen on diamond trips) and **nomads** (no base while they roam: a bed down for the night, packed up in the morning; only fight what's after them).

**Large homes**
- A third tier of house: a **two-storey house** (9x9). A workshop and storage room downstairs, a bedroom and more storage upstairs, a ladder in the corner; double chests and barrels on both floors. A bot whose one-storey house is full (or a builder, much sooner) builds one next.
- Blueprints can have any number of floors now, with ladders, upstairs floors, barrels, and furniture on any level.
- Home storage counts chests and barrels on every floor of the house.
- A bot never treats its own house as a shortcut: the walls, floors, roof and foundation are off-limits to its pathfinding (unless there's truly no other way).
- `/jtg build <bot> two_storey`.

**House fixes**
- A house build that's picked up again (after a restart, or a hiccup moving in) knocked down its own walls: building starts by clearing the site, and that dug out what was already built. It leaves finished work alone now.
- A house picked up again after the walls were up no longer goes off gathering a whole house's worth of materials; just what's actually missing.
- The secret stash is nice to have, not a must: if it can't go in (no room under the floor), the house is finished without it instead of the whole move-in failing.

---

## 0.7.1 Better Homes
**Houses**
- A new tier of house above the cabin, with three blueprints, each with a proper storage room: a **cottage** (living room at the front, storage room at the back), a **stone house** (the same in stone, for the bots that live underground anyway) and a **longhouse** (a long hall with a storage wing down one side). A bot whose cabin is outgrown (about two chests' worth stored) builds the one it likes best (miners stone, explorers the longhouse, homebodies the cottage) next door and moves its stuff over.
- Houses are drawn as floor plans now, so new kinds are easy to add; materials are worked out from the plan.
- Double chests at last: bots used to place every chest crouching, and a crouch-placed chest never joins the one next to it. Every house, hut, cave home and village room now gets its chests as a facing pair, and when storage fills up the new chest goes alongside an old one.
- Building sites in woods: trees and bushes in the way no longer rule a spot out (they're quick to clear); hills and rock still do. Sites are looked for further out too.
- `/jtg build <bot> <cottage|stone_house|longhouse|cabin>`: have a bot build a particular house next and move in.

**Dogs**
- Dogs live at home now: once tamed, the bot walks its dog home and sits it in the yard (outdoors, 7 to 14 blocks from the bed, clear of the house and the enchanting room, spread apart if there are several). It stays there, out from under everyone's feet.
- They only move when the bot moves house (they come to the new yard) or when one's in the way: standing where a block's going, or plugging a tunnel. Then it's sent back to the yard (or steps aside, if home's far off). Other people's animals in the way get a shove.
- Any dog the bot owns is looked after (fed when hurt, sat back down in the yard), including ones a player gives it.

**Inventory: a kit for the job, everything else at home**
- Bots carried everything: a stack each of cobble, deepslate, andesite, granite and diorite, seeds, flowers, glass, string, a saddle, spare doors and chests. Now the bag holds a kit: their best tools, weapons and armour, a bow and arrows, food, torches, a bucket, one stack of building blocks (all kinds share it), a few planks and sticks and a crafting table for mending things in the field.
- Plus whatever the job at hand needs: a house build keeps its materials and furniture, enchanting its books, lapis and leather, a farm its seeds, a dog project its bones, a nether trip its obsidian and gold. Ores and loot ride along on a mining trip.
- Everything else goes in the chests whenever they're home (not just when there's a pile of it; not every few ores while mining under the house, though). Things just fetched from a chest for a job aren't put straight back.
- At home, nothing worth keeping gets thrown out to make room: it's put away instead.
- Treasure goes in the secret stash if there is one, and the normal chests if not (rather than being carried about).

**Fixes**
- Fixed bots freezing at the start of "Digging a home" on flat ground and then giving up on it: dug out from under the middle of the block while standing near its edge, the next block held them up and they waited to fall forever. They step into the middle first now.
- On open ground with the blocks for it (a stack of cobble), a bot's first home is a little hut instead of a pit; the proper house later takes it over.
- Mining no longer starts over every time something interrupts it for a moment (picking up what just dropped): it kept its plan, tunnel and the ore it was after.
- Fetching water: bots stood at the water's edge for two minutes "in reach" while every scoop missed (too far for a bucket, or the bank in the way). They get close with a clear view now, closer if it keeps missing, and try other water after that.
- No "storage's full, another chest" while moving house.
- No more homes dug next to lava: cave homes, the first dug-out home and the enchanting room all keep 3 blocks clear of it (behind the walls and under the floor).
- Fetching from chests: a bot with a full bag opened the chest, found no room to take anything, put it back and went off to make more (chopping wood for one stick). It now puts some of what it's meant to be storing into the chest first to make room.

---

## 0.7.0 Enchanting & Inventory
**Enchanting**
- XP finally has a use. Geared-up bots (diamond pickaxe) take on enchanting as a personal project: 2 diamonds, 4 obsidian and a book (paper from sugar cane, leather from cows) make the table.
- The whole setup is planned at once: the table only goes where all 15 bookshelves and the way in fit too. No open space that big near home (a home dug into a hill)? They dig a room out for it, never through walls, floors, beds or chests.
- Bookshelves go up a few at a time (each is 3 books), always leaving the way in clear.
- They enchant with 30 levels and the full set of shelves (or as many as they'll manage), most important gear first: sword, chestplate, leggings, pickaxe... No more wasting levels on Unbreaking I.
- Enchantment preferences: everyone wants the best (Sharpness, Protection, Efficiency, Power), and each bot has favourites (miners love Fortune, explorers Feather Falling, the reckless Fire Aspect and Thorns). The score decides: a roll bad enough to remove, on gear worth re-enchanting, with the lapis and levels to try again, goes through the grindstone (they make one and put it by the enchanting room). Sharpness IV + Unbreaking III is never ground off.
- Lapis gets mined when needed; sugar cane cut; cows hunted for leather (leaving a couple, and the calves).
- Gear is judged by its enchantments too: Protection IV iron beats plain diamond, and a good enchanted sword is the one they fight with.

**Storage & inventory**
- Fixed bots digging up their stash, opening it, putting nothing in and closing it again (they had treasure they keep on them, like the diamonds for tools or golden apples, and thought it needed hiding). The normal chests had the same mix-up.
- They remember what's in their chests at home. Need iron, coal, logs, obsidian...? They go home and get it instead of mining more (from a long way off only for things worth the trip). `/jtg memory` shows what's stored.
- Bag full on a long trip: they head home and unload instead of throwing things away.
- Storage full: they make another chest and put it next to one of the others (they join up into a double chest).
- Making room: when the bag's nearly full they join split stacks, throw out rubbish, keep just their two main building blocks (cobble first, not a stack each of diorite, granite, andesite and tuff) and trim flowers, saplings and seeds to a few. Never treasure, gear or iron.
- Old gear they've replaced (the stone pickaxe once there's an iron one) is dropped; replaced iron and better goes in a chest. Thrown-out items aren't picked straight back up.
- Treasure (emeralds, name tags, enchanted books...) is never thrown out when the bag's full.
- The hotbar is laid out like a player's: weapon, pickaxe, axe, shovel, bow, water bucket, torches, blocks, food.

**Survival**
- On fire with a water bucket: water at their feet puts it out, then they scoop it back up (not in lava, not in the nether).

**Personality**
- New traits: homebodies (projects and home life over raids), animal lovers (dogs, farms), scholars (enchanting) and hoarders (put things away little and often, keep their spare blocks to take home). Existing bots keep all their old traits.
- Personal projects get picked more: the more homely projects a bot has to do, the more they pull; raiding counts for less while there are, and first times (a first dog, a first enchanting table) count for more. Diamond trips weigh less once there's a diamond pickaxe.

**Fixes**
- Fixed bots freezing in "Looking for a place to live" / "Getting ready to move", switching every tick. A house being built to replace an old home was planned by the house, not the bed, and was taken for a far-off site to abandon; it picked the same spot again straight away, forever.
- Obsidian is now a reusable job (stored first, then natural, then cast from lava), shared by the nether trip and enchanting.

---

## 0.6.5.3
- **Fixed bots freezing on the way to structures** (and anything else far away). Long trips went in 48-block hops aimed along the straight line to the target; for anything mostly *below* the bot (mineshafts, trial chambers, any underground place once it got close) that hop landed right where it stood, so it "arrived" every tick and stood still for 4 minutes. Now it crosses the map at ground level, then works down (or up) a staircase at a time, and gives up after 30 seconds without getting closer instead of standing there.
- Fixed bots freezing on the spot when they set out to get obsidian. With no lava pool nearby the step failed instantly and retried every tick; now they dig down to lava depth and tunnel about (for up to ~6 minutes) until they find some, mining any natural obsidian on the way.
- Obsidian casting can't loop forever either: missed water pours make the bot step closer (bucket reach is short), a pool that won't work gets skipped, natural obsidian it can't actually mine is given up on, and after enough failures the nether trip is dropped for now.
- Obsidian is kept: no longer thrown out as junk when the bag's full. It goes in the chests at home, and a nether trip fetches it from there before making any.
- Never digging straight down into a cave: a bot won't break the block it's standing on if there's more than a 3-block drop under it (a bot fell 30 blocks into a cavern in testing).
- Using the front door: digging through walls (planks, glass, bricks, stairs...) and the walls of its own home now costs a lot more than walking round to the door, so bots stop breaking out the side of houses.
- Chests, barrels, furnaces and crafting tables: bots now have to be properly close (about 4 blocks) and able to see the block to use it. No more opening chests through walls.

---

## 0.6.5.2
- No more getting stuck in the shooting pose when the target dies or blows up mid-draw.
- A creeper close by (within 7 blocks) is dealt with first, even if a zombie's hitting us.

---

## 0.6.5.1
- Fixed bots getting stuck swapping iron and food in and out of the same furnace (the iron job
  and the cooking job kept taking each other's stuff out). A furnace that's still cooking is
  left alone; they use another one.

---

## 0.6.5 — Creepers
- Sprint-hit creepers for extra knockback, then back right off (about 4 blocks, not a hop) and
  let them come again. Bows still shoot them from range.
- Hissing nearby: turn and sprint straight away right now (onto safe ground only), no slow
  route planning. Too close with a shield: shield up.
- Too close without a shield: one block down between us first (soaks up the blast; the creeper
  still sees us and goes off instead of wandering round).

---

## 0.6.4 — Hole fighting
- **Shield no longer gets stuck up** after a fight (it stayed raised forever once an arrow had
  made it go up, even while mining). Now it's down unless something actually wants it up.
- **Dug in at night, mining stays down here:** nothing above head height, so it never breaks
  its own lid and lets the mobs in.
- **Fights back from the hole:** a mob that gets in (or is right at the edge of a hole or box)
  gets the shield while the swing recharges, then a hit when it's ready, instead of turtling.

---

## 0.6.3 — Bundles, escapes and beds
- **Bundles:** crafted once there's string and leather (loot ones kept too). When the bag's
  nearly full, odds and ends (seeds, flowers, feathers, string, bones, dyes, flint...) go in the
  bundle; when there's room again they come back out so crafting can find them.
- **More ways out than pillaring:** **boxing in** (walls all round and a lid, right where it
  stands: safe from arrows too) when it can't outrun them; never pillaring with skeletons about;
  and if it's **getting hit while pillaring or boxing in, it gives up and runs**. A failed dig-in
  now falls back to boxing in rather than a pillar.
- **Villager in the bed:** the first click kicks the villager out, the next one gets the bot in
  (it used to give up after one click and dig in instead). Beds with a sleeping villager count as free.
- **Not missing ore:** ore it can see but isn't after right now (iron, diamonds, coal) gets
  remembered, and it goes straight back for it when it needs it; ore showing a face in the
  tunnel wall nearby counts even out of line of sight.

---

## 0.6.2 — Raids that actually count
- A structure only goes in the diary as raided ("Finished raiding a village") once **every**
  chest, barrel, minecart chest and trial vault in it is done (emptied by the bot, or found
  empty). Before, the first chest was enough.
- Looted containers are remembered for good, so bots never re-check one they've emptied, and a
  big structure can be finished over several visits.
- The raid project keeps its progress instead of calling a place done after one look round;
  trial vaults count once opened with a key.

---

## 0.6.1 — Totems, ghasts and raids
- **Totems of undying:** kept, and swapped into the offhand (instead of the shield) when death's
  close: low health in a fight, lava, on fire. Back to the shield once things calm down.
- **Ghast fireballs get hit back** (a punch sends them back at the ghast).
- **Blazes:** shield up exactly when a blaze glows (that's it about to fire).
- **Exploring the nether:** a few exploring legs before going to the fortress (structures get
  noticed on the way). **Hoglins** as last-resort food when out of food over there.
- **Village raids:** bots learn what a raid is. Geared, brave bots with an **ominous bottle**
  (outpost captains drop them) can start one on purpose: drink it in a village, hold the village
  through the waves (they stand their ground more in a raid), and pick up the totems evokers drop.
- **Tougher structures:** which structures a bot will raid depends on its gear (temples and
  shipwrecks for anyone, outposts and trial chambers in full iron, the nasty stuff in diamond);
  it won't set off hurt or without food, and backs out if it's getting beaten up in there.
- Tested: totem swap at low health, hitting a ghast fireball back. Not yet tested in a real
  game: village raids, hoglin hunting.

---

## 0.6.0 — Nether Expansion & Structure Overhaul *(expect bugs)*
- **Structures:** bots recognise real structures near them (villages, mineshafts, temples,
  outposts, trial chambers, ancient cities, fortresses, bastions...), remember them for good,
  and know what each one is about.
- **Raiding:** chests, barrels and minecart chests inside structures get looted (no more walking
  past village chests). A **raid project** sends geared bots to known structures they haven't
  been through yet; trial keys get used on vaults.
- **Hazards they know about:** no stepping on trap pressure plates (desert temple TNT) or
  tripwires (jungle temples); crouching near sculk and never stepping on sensors or shriekers;
  the warden is always "run". Zombified piglins are left alone unless they come for you, and
  piglins too if the bot's wearing gold.
- **The nether:** with a diamond pickaxe (or a portal they already know), bots go to the nether:
  obsidian (mined, or made by pouring water on lava), a portal near home, flint and steel, then
  quartz, finding a **fortress** and fighting **blazes** for rods, and home through the same
  portal. Both portals are remembered. No beds, no iron-digging, no food-roaming in the nether;
  a gold helmet goes on there. Hurt? They head home instead of pushing on.
- **Bows and crossbows:** shoot from range (switch to the sword up close), keep arrows topped up,
  and a small **"make a bow"** project (string from cobwebs).
- Shields now block **fireballs** too (blazes, ghasts); blazes and ghasts are treated as ranged.
- Getting home from deep underground: long walks head for daylight first when stuck below.
- `/jtg focus <bot> nether`, `/jtg project <bot> bow|raid`.

**Known issues (tested, not solved yet):**
- Blaze spawners are still dangerous: bots now back off facing the blazes behind their
  shield (only onto safe ground) and shoot them with a bow, and they've killed blazes, but a
  whole spawner's worth can still win. Expect nether deaths.
- Making obsidian from lava (water on a lava pool) is built but not yet tested in a real game.
- The raid project and trial chamber vaults are built but not yet tested end to end.

---

## 0.5.x — Farming & Personal Goals

### 0.5.6 — Staying on task
- **Mining focus:** while mining for gear, only a fight, eating, desperate hunger, smelting
  and furnace collecting, a full bag, picking up drops, following you or nightfall can pull a
  bot away. Wool hunts, house plans and projects wait their turn.
- **No more freezing while looking for food:** from underground they head for daylight first;
  they always take a few steps even when every direction is water; animals they can't reach
  (up a cliff) get ignored for a minute instead of retried forever.
- **Food sense:** raw meat counts as food; a bot with a full belly only hunts animals already
  in sight and saves the long search for when it's getting hungry. (Before: a third to half
  of their time went on "Looking for food".)
- **Whole stacks in the furnace** instead of just what one recipe needs.

### 0.5.5 — Diamonds
- With an iron pickaxe, any diamond ore in sight gets mined, whatever they came for.
- Diamonds on hand jump the queue: if a bot has enough for a piece (3 pick/axe, 2 sword,
  8 chest, 7 legs, 5 helmet, 4 boots) it makes the diamond version instead of iron.
- Diamonds are kept for gear until full diamond (no longer buried in the stash at 3+).

### 0.5.4 — Straight to iron, and pick it up
- No copper gear at all: after stone tools it's straight to iron. Copper only gets mined
  for a recipe that needs it; coal still gets grabbed on the way.
- Drops get picked up: ore dug through while tunnelling used to be left behind. Bots now
  collect useful drops nearby between blocks, wait longer for an ore's own drop, and count
  emeralds/lapis/gold as worth picking up.

### 0.5.3
- Bots ignore you in spectator mode (crouching to fly down isn't a greeting; followers stop).

### 0.5.2 — Nights, fights and furnaces
- **Nights:** no foraging or item runs on the surface in the dark (unless fully geared);
  once dug in they mine downwards through the night instead of sitting in the hole.
- **Fairer fight odds:** a fresh bot with a stone sword takes on two zombies, backs off from
  three. No more pillar-on-top-of-a-pillar.
- **Pillar loop fixed:** centre over the column before jumping; break what's overhead if stuck.
- **Furnaces:** their own leftovers are theirs (take them back, reuse the furnace instead of
  placing another); no fuel means taking the food back out; remembered furnaces first; a new
  goal empties abandoned furnaces nearby.
- **Coal** as soon as they have a stone pickaxe, and grabbed whenever seen while mining.
- Trident-throwing drowned count as ranged (get out of sight); no endless cook-retry loop;
  goal switching uses each goal's priority *now*, not when it started.

### 0.5.1
- No more digging out their own floor to see an ore (the break-and-replace loop).
- Stay out of water: no roaming over lakes, no chasing animals into rivers, no fighting
  drowned on the riverbed.

### 0.5.0 — Farming & Personal Goals
- **Gear first:** iron tools, full iron armour and a shield before anything else; then a
  personality roll between diamond trips, a home, personal projects and wandering.
- **Mini building update:** a proper 7x7 log-and-plank **cabin** (windows, door, plank floor,
  overhanging roof, furnished with bed/chests/table/furnace/stash/sign). Bots in a hut, cave
  or village house upgrade to one and move their stuff over.
- **Personal projects:** a fenced **wheat farm** (water under a block-and-torch so it can't
  freeze; harvested, replanted, baked into bread), **taming a wolf** (and feeding it when hurt),
  **decorating** (flowers and torches).
- **Smarter fights:** size up odds (health, armour, weapon, shield vs the mobs); leave hordes
  alone; get out early by sprinting, pillaring (and hitting down) or digging in.
- Doors and gates closed behind them; bots open fence gates; materials come from home chests;
  long walks home; `/jtg project`.

---

## 0.4.0 — Memory & nights
- **Long-term memory** saved on each bot: plans, places and a diary of structured events
  (died to, attacked by, killed, met, found, made, settled...). Repeats merge; capped.
- *Remember usefulness, not everything:* everyday places fade, mined-out veins are dropped;
  villages, the stash, death hotspots and unfinished houses are sticky.
- **A bed beats a hole:** own bed → free/village bed → carried bed → craft one; wool at dusk.
- **Digging in works:** centred, three deep, lid against the walls.
- Bots open wooden doors (village beds reachable); half-built houses survive restarts.
- `/jtg memory`.

---

## 0.3.x — Settling down
### 0.3.2 / 0.3.1
- Bread baked from wheat and hay; food stocking (cook in batches, raid hay, harvest ripe crops).
- Shield held through a skeleton's whole shot (not dropped as it releases); creepers blocked.
- Bots actually fight instead of carrying on mining; dig in against archers.
- Houses finished instead of abandoned mid-build; "moving in" no longer stalls.
- Bots rejoin by themselves after a restart (no more re-inviting every time).

### 0.3.0 — Expansion 2: Settling down
- After iron, a weighted personality roll: diamonds, a home, or wandering.
- Homes: move into a village house, dig a room into a hill, or build a 5x5 hut.
- Chests, a **secret stash** buried under the floor for valuables, name signs on houses.
- Smarter storage (keep working amounts, store the rest); `/jtg focus`.

---

## 0.2.x — Expansion 1: Combat
### 0.2.0
- **Shields:** crafted after the first iron, raised against arrows, creepers and (when hurt or
  outnumbered) melee.
- Better melee: crits, backing off between swings, weaving toward skeletons.
- Retreating when losing; pillaring up when cornered.
- **Going back for their stuff** after dying (5-minute limit; fresh start after 3 deaths in a row).
- Bots count toward sleeping through the night (once they have a home).

---

## 0.1.x — The port
### 0.1.1 – 0.1.4
- Built for NeoForge 26.3.0.51 (the first jar crashed on 51: server config type removed).
- Bots claim beds by themselves (villagers' included) or craft one and dig a burrow.
- Sprinting, smoother movement, building up to reach things instead of jump-spamming.
- You can punch them (it lands, and they stop to look at you).
- Mining no longer resets just before a block breaks; drops picked up after mining.
- Iron before copper (back then copper only if found first); standing on beds/slabs fixed.
- Phase 1 signed off at 0.1.4.

### 0.1.0 — First version
A 1:1 Java port of Gamemode One's Bedrock add-on "AI Players", no LLMs:
- Bots are real server players: join/leave messages, tab list, saving, respawning.
- K screen and `/jtg`: invite, kick, ban, teleport, inventory. Guide book.
- Crouch at a bot to have it follow you (instead of Bedrock emotes).
- Survival loop: wood → stone → copper → iron, furnaces and crafting tables, hunting,
  fighting, looting chests, copying what the player's doing.

---

*Early patch numbers (0.1.1–0.1.4, 0.3.1) are approximate: the fixes are right, exactly which
patch each landed in isn't recorded.*
