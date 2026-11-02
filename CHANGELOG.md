# Joined the Game — version history

NeoForge 26.3 · mod id `joinedthegame` · bots are real (fake) server players, no LLMs.
Jars from 0.3.2 on are kept in [`releases/`](releases/). Earlier ones weren't saved.

---

## 0.10.0 Combat and Brewing!
The last big update before the feature freeze (from here on: bug fixes only). Bots brew potions and drink them at the right moment, and fight a lot more like players: against crowds, archers and people.

**Brewing (new project).**
- **The stand.** A blaze rod and three cobblestone make a brewing stand, set up by home: indoors if possible, against a wall, never in a doorway or a one-wide passage. Blaze powder runs it (one powder is twenty brews; bots know the fuel left inside the stand counts, not just what's in the slot).
- **Bottles.** Glass bottles (sand, smelted to glass), filled at the nearest open water the way a player does it, by using the bottle on the water.
- **What they brew.** Whatever they're short of (three of each is plenty) and can make from what they have: healing (glistering melon), strength (blaze powder), fire resistance (magma cream), regeneration (ghast tear), swiftness (sugar, from their own cane), night vision (golden carrot), water breathing (pufferfish). Wart first, then the ingredient, then glowstone for a stronger potion (Strength II, Healing II) or redstone for a longer one, and gunpowder to turn healing into a splash potion (thrown, it heals instantly). Two batches a go.
- **Nether wart.** Bots on a nether trip now pick ripe wart in the fortress gardens (and take a couple of the soul sand blocks under it). A bot that brews but has run out of wart wants a nether trip for it. Soul sand brought home becomes a wart patch by the stand, picked and replanted when ripe, so it's not a nether trip every time. A bed left empty (picked, then a fight came along before replanting) gets replanted next time; an empty bed with no wart left to plant in it counts as no patch at all, so it's back to the nether for more. A patch with wart still growing is left to grow.
- **A stand that can't brew isn't waited on.** An ingredient on top that isn't brewing in (no fuel left anywhere, or it doesn't go with those bottles) is taken back out after a few seconds instead of being waited on for ever. A batch interrupted halfway (a fight, nightfall) is picked up from the stand next time.
- Tested on the dev server: stand crafted and placed by home, wart patch planted, bottles filled at a pond, then 3 x Strength II (wart, blaze powder, glowstone) and 3 x long Swiftness (wart, sugar from cane, redstone).

**Potions, used (home brews, witch drops and chest loot alike).** A potion is recognised by what's in it; nothing harmful is ever drunk.
- **Healing** when a fight's going badly (40% health): a splash one is thrown down at the bot's feet (instant); a drinkable one waits for a bit of room, since drinking takes a second and a half.
- **Regeneration** when hurt with a moment to spare. **Strength** going into a hard fight (a crowd, something nasty, a duel). **Swiftness** to get away.
- **Fire resistance** whenever on fire or in lava, fight or not, and *before* going into a nether fortress or a bastion (topped up while hunting blazes if it runs out). **Water breathing** when running out of air.
- While drinking, a bot backs off facing the fight instead of swapping to its sword halfway (which would waste the potion).

**Fighting mobs.** Ranged mobs (~40%) and zombie crowds (~27%) were most of the deaths in the overnight run.
- **Picking the target.** The most dangerous for how close it is, re-checked twice a second: a creeper within seven blocks beats everything; whatever's hitting us right now beats an archer across the field (charging the archer with a zombie on your back is how the zombie gets the kill); and it sticks with its current target unless something's clearly worse, so it doesn't flit about.
- **Crowds.** Three or more coming at it in the open: it backs into a gap close by (a nook, a doorway, a one-wide passage) so they can only come one or two at a time, from the front, and holds it ("a crowd of 5: backing into the gap at ... so they come one at a time"). Two or more in reach with a sword: a hit on the ground sweeps them all (a jumping crit doesn't sweep). The shield comes up between swings whenever two are on it, not just three. And a crowd now counts as worse than its mobs added up, so a bot leaves a losing fight earlier (three or more and down to 12 health, five or more and down to 15).
- **Archers, without a shield.** Nothing to block with and nothing to shoot back with: instead of charging across open ground, it gets round a corner and lets the skeleton come to it (a skeleton that can't see you walks over to find you, into sword range). Fifteen seconds and it hasn't come: charge after all.

**Fighting people.**
- **Shields.** Their shield's up: out comes the axe (it knocks the shield out of their hands for five seconds). No axe: it doesn't swing into the shield, it circles round to the side the shield doesn't cover.
- **Maces.** Someone in the air above it with a mace (a smash coming down): shield up facing them (shields take a smash), or get out from under them, sprinting sideways.
- **Potions** as above: strength going in, healing when it's going badly.
- **Teamwork.** A close friend (or partner) nearby badly hurt in a fight of their own: a bot that's doing fine in its own fight switches to whoever's on them ("Duck's in trouble with Jack: going for them instead"). Two on one finishes it and takes the heat off. Allies used to just not hit each other.

**Tested on the dev server.**
- Zombie crowds (one bot in iron with a sword and shield, 5 or 6 zombies at night, an arena with nooks and a one-wide passage): 5 of 5 rounds survived. It backed into the gaps ("a crowd of 6: backing into the gap ... so they come one at a time"), boxed itself in once when it got hurt, and drank its Strength on its own going into the six-zombie fights.
- Brewing edge cases: a stand with fuel only inside it (slot empty, no powder on the bot) brewed 3 x Regeneration on that fuel alone; a stuck stand (an ingredient on top, no fuel anywhere) was emptied, both items back in the bot's bag, rather than waited on.
- People: the axe comes out when the other's shield goes up; a bot with a mace dropped on someone from 7 blocks up: "JackBlack's coming down with a mace: shield up". The log only says "knocked their shield away" when the game actually disabled it (a player's shield goes on cooldown); otherwise "hit their raised shield with the axe".

**Guide book:** a Brewing page; Fighting rewritten; the inventory line says it's look-only without cheats.

**New: bots tidy up after themselves.** The blocks a bot puts down only to get somewhere or out of trouble (pillars up to a tree or a ledge, bridges over a gap, the box it walled itself into and its roof, pillaring up away from zombies, the block thrown down at a creeper) are remembered, and once it's done with them (twenty seconds on, nothing hostile about, not out in the dark) it mines them back and picks them up: "tidied up after myself: took back 3 blocks I'd put down". Up to 32 blocks away (much further and they're left, and forgotten after 15 minutes). Never the block it's standing on or the pillar under it, never one someone's standing on or that has something built on it, never one by water or lava, only if it's still the block it put there, and it never pillars up to take a pillar down. A pit's lid stays (taking it would leave a hole). Tested on the dev server (a crash found on the way, while checking a changed block, was fixed). A bot that runs a long way from a fight leaves its blocks: out of range.

**Fixes**
- **Brewing things thrown away as rubbish.** Blaze rods, blaze powder, nether wart, ghast tears, magma cream, glowstone, redstone, gunpowder, sugar and glass bottles weren't on the list of things worth keeping, so a full bag threw them out as "odds and ends" (it happened in testing: the wart went, and brewing stopped with "out of nether wart"). A nether trip's blaze rods were at the same risk. They're keepers now: stored at home like anything else worth having. And the bag-full clear-out never throws away anything the job on hand needs.
- **Things put away in the chests "didn't exist" for some projects.** Bots keep only their everyday kit on them and put the rest in the chests at home, to be fetched when a job needs it. Crafting already did that (the planner fetches from the chests), but a few checks looked only in the bag before going out to gather: string for a bow (off looking for cobwebs), leather, cane and lapis for enchanting (hunting cows, cutting cane, mining lapis again), cane for the sugar cane project, and everything for brewing. Now every project starts by getting its things out of the chests, going by the remembered list of what's in them, so it works from anywhere: far from home, it walks back first. Brewing takes out only the basics and what the brew it's making needs, not every ingredient there is. Brewing and sugar cane also count what's stored when deciding whether they can be done at all. The projects missing a keep list (brewing, sugar cane, pen, garden, paths, tree farm) got one, so their things aren't put away again halfway through.
- **"Picking the nether wart" for ever.** Harvesting a ripe wart patch used the kind of dig that counts a spot done once you can walk through it, and wart has nothing to bump into, so it was "dug" before it was broken, and the bot tried again every tick. It's cleared properly now (like crops), and a harvest is tried once a go: if it doesn't work out, it brews anyway.
- **Lag spikes when bots all set off at once (sunrise).** Caught by the new lag catcher: the server spent its ticks in several bots' path searches at the same moment, each asking about the same blocks over and over (passable? breakable? floor?) and unpacking chunk data every time. Now a path search remembers each block it has looked at (it's over within the tick, so nothing changes under it), and at most one long route (over 32 blocks) is planned a tick between all the bots: the rest wait their turn a tick or two, so a morning rush is a ripple, not a stall (a fight's quick re-plans never wait: an earlier try that made everything wait left bots queuing mid-fight, and was slower). The time to mine each kind of block is worked out once a search, not for every block looked at (it went through the whole bag every time). And each bot updated who-sees-what (entity tracking) every tick, standing still or not, the biggest single cost with 20 bots about: now, like a real player's game, only when it moves or turns, or once a second. 20-bot night benchmark: 68.8 ms a tick before these last two, 47.1 after. And "is this block dangerous to step on?" (asked constantly by pathfinding, a long chain of checks) is worked out once per kind of block and remembered, forgotten again whenever datapacks reload (tags can change); the nether portal, which depends on the moment, is still asked every time. Same answers, less work. Where the rest goes, with 20 bots at night: about two thirds is vanilla's own mobs and chunks (20 players' worth, since bots are players), a fifth is the bots' brains, spread thin.
- **New (testing jar): lag catcher.** While the server's running slow (over 60 ms a tick), it notes what the server's busy with and writes the busiest spots to `logs/joinedthegame-lag.txt`, so lag that comes and goes can be tracked down afterwards.
- **Chopping birch for ever to get "2 more oak planks".** The crafting planner preferred a kind it already had a little of over one it could make in full from the bag: with 2 oak planks and 31 birch logs, stampylongnose kept chopping trees for more oak and never crafted. A partial stack only wins now if the rest can be made from the bag too.
- **No knockback when a player (or another bot) hit a bot.** A player's hit sends the knockback to the victim's own client to apply and puts the server's copy of the motion back as it was; bots have no client, so the push was lost (mobs knock things back another way, so bots only seemed to get knocked about once a fight was on). The bot now takes that push itself on its next tick, like a client would. Bot-against-bot fights get knockback too.
- **Brewing gave up on everything when one ingredient couldn't be made.** If the planner found no way to make one recipe's ingredient, the whole session ended with nothing said. Now it says what it couldn't make and brews something else. Bots also take a fresh look in their chests when a project is being decided or starting at home, so things put in since (by you, say) count straight away instead of up to half a minute later.
- **The secret stash was a one-way trip.** Treasure (lapis, gold, emeralds, enchanted books) goes in the buried stash, but only the trial chamber, netherite and heavy core ever took anything back out of it. So lapis went in and never came out, and enchanting mined more while the stash held plenty (all you'd see in a stash was lapis piling up); gold in there didn't count for brewing either. Every project's start-of-job fetch now looks in the stash too, and brewing counts stash gold when deciding. (Iron and other everyday things going in the ordinary chests is how it's meant to be; diamonds stay in the bag until the bot's in full diamond.)
- **Potions got put away at home.** Potions weren't part of the everyday kit, so a bot back home stored every one it had and then had none in its next fight. Up to 6 drinkable and 4 splash ones now stay in the bag (they don't stack, so a handful: the rest wait at home), and only ones that do something get those slots: water bottles, awkward potions and anything harmful are put away. A trial chamber run takes 8 and 4; brewing keeps its water bottles and what it's just brewed.
- **"Need a blaze rod for a brewing stand" with a stand at home.** A bot that had wandered far off (900 blocks, mining diamonds) looked for its brewing stand round where it was standing, didn't find it, and gave brewing up. The stand and the wart patch are looked for round home now, however far away the bot is (and never checked against the wrong dimension). Every other remembered place in the mod was checked for the same mistake: the enchanting table had it too (fixed the same way); the rest are things meant to be near the bot (ore it saw, sheep, a crafting table to use, the portal it's heading for), or were already looked for round home (farm, garden, cane, paths, campfire).
- **Server crash: tree farm with no saplings.** A bot starting a tree farm whose sapling fetch from its chests came back empty crashed the whole server (a list of zero planting spots counted as "enough" for zero saplings, then the first spot was read from it). Found on the dev server while testing this update. Now: no saplings, no plan.

## 0.9.1 Fixes from the 0.9.0 test
- **Bots stuck together in a hole.** Three bots followed each other down one bot's one-wide hole after the same stone, then all tried to pillar out from the same block. The game won't put a block down where someone's standing, so they hopped on the spot for 20 seconds, wiggled, and walked back in. Now:
  - paths never plan a block where somebody is standing (a bot squeezed in with others digs a step out instead), and squeezing into someone's spot costs extra;
  - if someone steps into the spot after the path was planned, the bot gives them a moment, then finds another way;
  - when looking for stone (or anything but wood), a bot skips blocks somebody's already standing right by: stone's everywhere.
  - Tested: three bots dropped into the same one-wide, four-deep shaft each dug their own way out, in different directions, within 15 seconds.
- **Bots shoving into each other at spawn.** Everyone starts in the same spot and went for the same little patch of stone; a bot trying to step onto a block three others stood on got pushed back, again and again (Duckatyt for 30 seconds). Now a step somebody's standing on counts as reached once the bot is close (it'd only be shoved off the middle), and a bot that's being pushed back by people goes round after a second instead of shoving for three. Plus the stone personal-space rule above, so they spread out to begin with.
- **"Crafting a stone pickaxe" after dying.** GeminiTay died to a skeleton and her status kept saying what she was doing before, while she'd actually decided her stuff was guarded. Two fixes:
  - When a bot holds off on getting its stuff back, the reason now shows next to what it's doing in the bot list: "(my stuff's guarded by a skeleton, waiting for it to leave)" or "(not going back for my stuff in the dark)", sleeping included.
  - Dying wipes the mining/crafting plan it was halfway through (it was carrying on with a plan for a bag it no longer had); it thinks again from what it's got.
- **Accidental sword sweeps started fights to the death.** PenTester and Redlantern went for the same sheep; PenTester's sword sweep caught Redlantern, who took it as an attack, and "self defence" on both sides ended with Redlantern dead. Now:
  - Bots mostly don't sweep with someone (a player, a pet, a villager, a golem) stood by what they're hitting, but careless and reckless ones don't always look first.
  - A bot caught by someone's sweep at something else knows it was an accident: a small dent in liking, no fight. Rarely it's taken badly anyway and swung back at, like people do: a few percent for easygoing bots, up to about a fifth for hot-headed, unforgiving ones, much likelier with someone they already dislike or after repeat accidents. Friends and partners never; rivals always. Works for real players' sweeps too.
  - Tested: four bots crowding one zombie caught each other three times; all three were shrugged off.

- **The bot named after you was loading your stuff.** In singleplayer, vanilla decides who owns the world by name alone, so the Redlantern8808 bot in Redlantern8808's world counted as the owner: every time it joined it loaded the player's own saved inventory (guide book and all), it got operator powers, and if it had ever disconnected the game would have shut the world down as if the player had quit. Now the owner is whoever has the owner's account id; a bot has its own id, so it's just a bot with the same name. (Your own inventory was never overwritten: saves go by id.) Any guide book a bot is carrying is dropped from its inventory when it joins.

- **Bots froze when hit, even mid-fight.** Every punch from a player still did the oldest thing in the mod: drop everything, stop following, stare for a few seconds, put the job off for half a minute, outranking even fighting back. So LeBronJames decided to defend himself and then stood staring while he was beaten to death; later, mid-fight, he stopped swinging every time he was hit. Punches are reworked:
  - In a fight already: a hit's just part of the fight. A hit with anything in hand: an attack (feelings, self defence), no freezing.
  - A bare-hand tap is "hey!": the bot glances at you and carries on (still following you, if it was); a friend crouches hello back.
  - Tapping over and over (four in a few seconds) gets annoying: friends and the very sociable put up with it, others like you a bit less, and a hot-headed one may shove you back. Past eight, it's not a joke any more: from then on it counts as an attack.
  - A bot following you stops if you attack it.
- **Tagging along with their killer.** Ten seconds after respawning, LeBron was "Exploring with you" with the player who'd just killed him. Tagging along when you explore, and mining beside you, now skip anyone the bot dislikes, a rival, or someone who's gone for it in the last five minutes (and asking it to come along gets "they just went for me").
- **One crouch, five invites.** A crouch (or a quick bob) counted as several "come with me?"s; now one.
- **Bot list: the reason got cut off.** "(not going back for my stuff...)" ran off the end of the row. The reason now sits on the name line in its own colour, shorter ("leaving my stuff till morning", "my stuff's guarded by a skeleton"), and hovering a row shows the whole thing.

- **Hit and run worked every time.** Self-defence stops six seconds after the last hit (so one punch doesn't mean a chase across the map), which meant hit, run, come back, hit again, for ever. Now bots remember who hit them for five minutes, counting each hit-and-run as a round. When that person turns up again (after actually going off: out of sight or well away), the bot decides whether to go for them: likely, and likelier each round, for the brave and the unforgiving; an easygoing one may just keep an eye on them; a close friend lets it go. After two rounds it stops falling for the "back off for six seconds" trick and chases like a rival. Either of them dying settles it.
- **Knocked to their death didn't count.** "redlantern8808 was doomed to fall by LeBronJames": the mod only counted the killing blow, so LeBron didn't know he'd won, didn't loot, and only picked up the odd useful thing out of habit. Kills now go by the game's own kill credit (knocked off a ledge, into lava or the water), for bots dying that way too.
- **Bots stood by a pile picking up nothing.** Picking up drops went for the nearest item and nothing else until it was picked up, so one caught somewhere out of reach (up in a dark oak's leaves) had LeBron standing by his own pile, then a rival's, empty-handed until time ran out. Now an item that won't come after a few seconds gets knocked loose (the leaves it's caught in, if they're in reach) or left, and the rest get picked up.
- **Getting high up, or walling yourself in, made you safe from bots.** In a fight they only ever tried to walk to you. Now, getting no closer:
  - up a pillar near them: they knock the block out from under you, and keep going till you're down;
  - walled in, boxed up, behind a door: they break through what's between you (yours or not: it's a fight). Not obsidian-hard blocks or chests.
  - Tested: Dan broke into a cobblestone box at eye level; then took a five-high pillar down from under Gemini and landed 23 hits.
- **Fighters judged reach feet to feet.** A bot at the foot of a pillar was "too far" to hit someone standing just over it, and not far enough to move, so it stood there. Reach is now the game's own (eyes to the nearest part of them), and close-but-can't-hit means doing something about it.

- **Everyone in two little holes (stress test).** All the bots start together and went for the one patch of stone in sight: 8 to 10 of them mining in two spots a few blocks across, three getting stuck on each other (LeBron, Benny and Redlantern took turns standing on the same block). The personal-space rule only covered a bot's first look for stone; the close-by fallbacks ignored it. Now it covers every search, and in a crowd (two or more digging within five blocks) a bot doesn't look there at all: it heads off its own way, away from them, and staircases down there ("too many people digging here: off my own way (north)"). Tested: six fresh bots dropped on one spot by one small patch of stone spread out and had stone tools within about a minute, nobody stuck.
- **Crafting tables in a tight spot.** In a one-wide staircase there was "nowhere" to put a table (only open spots counted), so crafting failed over and over ("couldn't place table at null"). Now a hemmed-in bot digs a nook in the wall and sets the table there. And a spot somebody's standing on is skipped: the game won't put a block down on anyone, which is why DanTDM's table failed three times in the crowd.

- **Anything that happened to a bot the moment it joined was forgotten.** A bot's brain started with a blank memory and only picked up its real, saved one on its first tick, so whatever came in between (feelings set as it arrived, a hello, a hit) went into the blank one and was lost. Found in a team battle: teammates set as close friends came in still hating each other from the round before, and killed each other. Now the brain has the bot's own memory from the moment it spawns. Tested: five teams of two, nobody touched a teammate (Redlantern8808 and Duckatyt won it together).

- **Beds: bots down a mine at nightfall tunnelled toward them through solid rock.** In the 1h40m test, Duckatyt and MillieBobbyBrown (mining at y=30-40) dug sideways and upward toward their surface beds, ran out of time half way (or got stuck), went back to mining, and tried again a minute later; several others never went at all. Now a bot deep underground (no sky, surface more than eight blocks up) whose bed isn't close carries on down there for the night ("night's fallen, but I'm down a mine: carrying on down here"): it's dark underground anyway. That's decided once for the night, so no flicking back to "Sleeping" as the tunnel nears the surface. Bots on the surface still go to bed (and get up to three minutes to walk there if they're on the way, not ninety seconds).
- **Bedless bots kept hunting sheep that weren't there.** In a dark forest, hunts came back empty again and again (CaptainSparklez and Aphmau seventeen times each), two minutes of wandering each. Now failures in a row mean a longer wait before the next: five minutes, then fifteen, then thirty; sheep in sight, or wool got, starts over.
- **"Chopping wood" deep underground.** Bots down a mine went off up through the rock to the trees for a spare stick, a spare crafting table, a shield or a better pickaxe. Now wood only comes before getting back up top when there's no other way on: no pickaxe at all.
- **Switching too suddenly: "Mining iron", "Picking up items", "Mining iron"...** The block a bot just mined dropped right next to it, and the pickup goal took over for a second to grab it, every few blocks (269 quick flips in the test). A drop that's just landed right by a busy bot is now left to the job it's doing (it picks its own drops up); only something still lying there a few seconds later gets gone after. Tested: no pickup flicker at all in a minute of six bots mining.
- **Popping out of the night's hole to empty a furnace.** Dug in and not geared for the night, bots kept stepping out to collect from a furnace and back in ("Digging in", "Emptying a furnace", twenty times). The cooked food waits till morning unless the furnace is in reach from where they are.
- **"Building my house, chopping wood, mining iron, building my house..."** A house wasn't started until the bed for it was in the bag, and with no sheep about the hunt failed, the house was put down for half a minute, the bot went off mining, came back, hunted again... (28 times in the test). Now, with no wool to hand, a bot builds the walls and roof first and sees to the bed when it's time to move in ("no wool for a bed yet: building first, the bed when it's time to move in"); only then does it go after sheep (if a hunt's due) or wait for one.
- **Smelting at night, up and down from the night's hole.** LeBron, dug in underground, kept climbing toward a furnace near the surface to smelt, getting exposed, digging back in, and going again. Dug in and not geared for the night, a bot now leaves a furnace up near the surface (under open sky, or above its head) for the morning and sets one up down where it is instead.
- **"Mines, sits there for five seconds, mines again."** After every ore, a bot collects the drops round it. With its bag full it had been throwing junk out at its feet (which, on purpose, can't be picked back up for minutes), and the collecting went for that junk, stood on it waiting to pick up what couldn't be, and gave up only when its time ran out: Redlantern, at about one iron ore every thirty seconds with ore all round him. A bot's own throw-outs are no longer anything to collect.
- **Pickaxe broke, went exploring.** When the pickaxe gave out mid-dig, the dig counted as finished, the brain stepped back to think, and Redlantern went "Exploring" underground with no pickaxe for half a minute before making a new one. A broken pickaxe now goes straight on to making the next one.
- **A trial chamber found, never raided.** Redlantern found one, looted its chests, and never took it on: the trial chamber run sat at 0.4 in the day's choices against diamonds at 1.0 and lost every roll (if he was ready for it at all; full iron includes boots, and he had none). A chamber close by that the bot's fit for now pulls much harder; and one it isn't fit for yet says why, every ten minutes or so ("I know a trial chamber at ..., but I'm not ready for it yet (not geared up enough (3.6/4.0))").
- **Trial chambers: wandering round setting spawners off.** "Raiding the trial chambers" was only the everyday chest-looting: Redlantern walked the corridors from chest to chest, set the spawners off as he passed, fought whatever came out (down to three and a half hearts), and flicked over to "Mining iron" and back when he lost track of the next chest. The proper run (take each spawner on, collect the keys, open the vaults) never started. Now:
  - everyday chest-looting leaves trial chambers alone;
  - a bot standing in a chamber it's fit for starts the proper run there and then ("I'm in a trial chamber and I'm ready for it: taking it on"), whatever the day's plan was;
  - the run loots each room's chests itself, once the room's quiet ("a chest here, looting it while it's quiet");
  - not fit for it, the bot says why now and then (see above), and leaves the place be.
  - any time of day: projects wait for morning at night, but a chamber's always deep underground, so a bot already down there takes it on (or carries on) at night too. (Redlantern walked straight through his to mine diamonds "because it was night".) Farms, pens and gardens still wait for daylight.
  - (Not testable on the flat test world: a placed chamber doesn't count as a real one there. To check in a real world.)
- **Redlantern moved into a trial chamber.** Homeless (a nomad, he'd packed his bed up that morning), he came across the beds in a trial chamber's bedroom, took them for a free bed indoors like a village's, claimed one as home and filled the chamber's loot chest with 193 of his things, between the spawners. Beds inside dangerous places (trial chambers, mansions, outposts, fortresses, ancient cities: anything rated half-dangerous or worse; villages are fine) are camps now, not homes: a bot can sleep in one for the night when nothing hostile is within sixteen blocks ("sleeping here tonight, it's quiet; not living here, though"), but never claims it, so its chests aren't the bot's storage either. A bot that already lives in one moves out, taking its things back out of the chests first, as much as it can carry ("taking my things out of the chest, then moving out"): treasure first (discs, books, gold), then the useful stuff, never the cobble and dirt, so a full bag takes the best of it and not whatever's in the top row; nothing more gets put away there meanwhile. (His diamonds were never in there: bots keep diamonds on them for gear.)
- **Standing behind a shield getting shot.** Backing away from archers with a shield up only steps onto safe ground; in a pit there isn't any, so the bot stood still behind its shield for up to 45 seconds, then chose the same again, while bogged in the trial chamber picked at it from all round (a shield covers the front): Redlantern went from fifteen health to six, twice. Now, getting nowhere and still being hit after about three seconds, it walls itself in if it has the blocks ("can't back away from them and still getting hit: walling myself in"), otherwise goes for them and fights it out for a while rather than straight back to the shield ("...going for them").
- **Bow fights with archers.** A bot with a bow shot anything more than five blocks off, including skeletons and bogged: a bow can't block, so it traded arrows and took every one, shield in the other hand. And it would start drawing while knocked off a ledge or mid-air, ending up shooting and jumping at once. Now: no drawing a bow off its feet or in the water; an archer within fourteen blocks gets walked in on behind the shield and hit (the bow's for ones further off, or with no shield); and in a bow duel, when the archer draws and the bot isn't nearly ready, bow down, shield up to take its arrow, then back to it.
- **Calling truce on bots didn't work.** A player's "peace" or "truce" only went to bots registered as mid-fight at that very moment, and was only ever answered from inside a fight: typed between blows (or while JackBlack was coming back at you), it reached nobody and nobody said a word. Now it reaches any bot fighting you and any nearby with a quarrel to make up (a rival, one that can't stand you, one you've hit and run from), and a bot not mid-fight answers too, out loud, yes or no, by its grudge and its temper (never one you made a John Wick). Nobody to make peace with? You're told so. Every call is in the debug log ("[peace] ... offered to ...").
- **Close friends let you beat them up.** A bot that's a close friend (liking 60+, gifts get you there) never fought back at all. It still lets a hit or two go, but three in a few seconds isn't a joke: it defends itself.
- **Climbing out of the night's hole after a chest or a cow.** Dug in for the night (not geared for it), a bot's shelter steps back so it can mine on down from the hole, which also let looting, hunting, exploring and company walk it straight back outside (over a hundred flip-flops in the night). Tucked into a hole near the surface at night, it doesn't start those now; mining from the hole still goes on, and bots well down a mine aren't affected.
- **Wood, from down a mine.** A house-building bot that needed logs dug straight up through the rock toward the nearest tree (most of the night's 267 "Chopping wood" stuck moments). Bots now remember where they last stood under the sky, and go back up that way first.
- **Gathering for a house three hundred blocks from it.** A bot gathering for its house that's wandered far (over 128 blocks) from the site heads back there before gathering more.
- **Hunting and looting by a pillager outpost.** Cozmo went hunting by one, and looting its chest, and was shot down five times in a night. Hunting skips animals near an outpost or where the bot keeps dying (unless starving); an outpost's chests are only for a planned raid.
- **No critical hits.** The fighter let go of sprint before jumping for a crit, but a sprint carries on till something stops it, and a sprinting hit is never a critical: bots "never really got crits". It now actually stops sprinting, and swings on the way down by the game's own reach.
- **Running from creepers with the shield down.** With a shield, a bot only blocked a hissing creeper within two and a half blocks and turned to run from anything further, getting caught by the blast with its back to it. Now it faces any hissing creeper within five with the shield up (no shield: still runs, or walls off if it's close).

**New (testing jar): stats in the live snapshot.** Each bot in `logs/joinedthegame-bots.json` now also has `stats` (deaths, by cause; items got back vs lost; stuck; flip-flops; watchdogs; failed tasks, all since the game started), `timeSpent (minutes)` (where its time went, by goal, top eight) and `recentDeaths` (the last five: when, what, where, and what it was doing). A night's trouble at a glance, without reading hours of log.
- **(Overnight test, 7.5 hours, 109 deaths.) Losing everything after dying.** Bots got their stuff back 33 times and lost it 50. A death pile "guarded" by a skeleton anywhere within sixteen blocks (one behind a cave wall counted) meant waiting it out; and at night a freshly respawned bot (no armour) was never "ready for the night", so never went at all, last minute or not. Redlantern lost a full diamond kit that way. Now: night only matters if the stuff's out under the sky (down a mine it's dark anyway); an archer only guards it if it can actually see it; and the last two minutes (not one) are a go whatever, night included.
- **Drowning in a flooded mineshaft, nine times in thirteen minutes.** Out of air, MadisonBeer's way to air was found (a pocket three blocks off, through the water) but the path planner couldn't plan it, and the fallback was digging straight up through rock while floating, a twenty-fifth the speed of digging on dry land. She drowned, respawned, went back for her things, and did it again. Now the bot swims the way the search found, cell by cell; digging up is only for when there's no way through the water at all.
- **Looting a trial chamber through a mineshaft.** The "leave trial chambers' chests alone" rule went by the structure the bot thought it was in; MadisonBeer, standing in a mineshaft that ran through a chamber, "raided the mineshaft" into it, set the spawners off and died to its strays nine times. Chests inside any trial chamber are left alone now, whatever overlaps them; and so are chests near a spot where the bot keeps dying.
- **Mining under water.** Sand off a river bed, stone at the bottom of a lake: five times slower to dig in water (twenty-five floating), drops float off, and Cozmo got stuck at it again and again. Blocks with water over them are skipped while the bot's on dry land.
- **Crafted diamond boots dropped on the floor.** Redlantern's bag was full (36 of 36, his whole old iron set still in it: iron and better normally goes home to the chests, and he'd just moved out), so the boots he crafted fell at his feet, counted as thrown away, and he set off to mine diamonds for another pair. Now crafting makes room first (odds and ends into a bundle, then junk, then outgrown gear, then a spare duplicate furnace/table/bow) or doesn't craft ("no room in my bag to make that", not the old misleading "recipe didn't match"). The room check counts a stack the result can go on top of (logs to planks onto planks), not only an empty slot; and freeing a slot goes by whole slots (furnaces and tables stack, so "three furnaces" frees nothing): a second slot of the same thing, or a second stack of building blocks while keeping a stack's worth. Anything that still overflows lands as an ordinary drop to pick up once there's room; and with no home to take it to and the bag full, outgrown iron gear gets thrown out like anything else outgrown.
- **Hiding hurt, never eating.** In the trial chamber Redlantern sat half a minute in a hole at eight health with food at seventeen (one short of healing at all) and never ate: in-the-hole eating only happened once properly hidden, and a bogged could still see in. Now a hurt bot eats whenever nothing's right on it (four blocks), hidden or not; and at three hearts or less, a golden apple first if it has one.
- **Sorting the bag mid-fight.** Packing the bundle and throwing junk out are instant, but "tucked 3 odds and ends into my bundle" at three hearts with bogged shooting is no way to look. No bag housekeeping while fighting, or for ten seconds after being hit.
- **Hitting things with the bow.** Some ways into a swing (something getting into the hole with it, backing off, charging an archer) came straight from shooting and swung whatever was in hand: the bow. Every swing now puts the bow away and gets the best weapon out first (and if it had to swap, waits for the next swing: a swap resets the charge, so it'd only be a weak hit).
- **A home dug into a trial chamber's wall.** Straight after moving out of the chamber's bedroom, Redlantern dug a cave home into its wall, then moved out of that too. No house-hunting while inside a dangerous place now; that waits till it's out.
- **Bundles, used properly.** Bots had one bundle at most, used it only once the bag was all but full (after junk had already started going out of it), and never made one. Now, before anything's thrown out to make room, the odds and ends (seeds, feathers, nuggets, ink sacs, slime balls, bones, saplings, string, leather, rabbit hide, redstone, glowstone dust, honeycomb, shells, prismarine, membranes, amethyst, scutes...) go into every bundle in the bag; short of room with string and leather to hand and nowhere for them, a bot makes another ("made a bundle for all the odds and ends"). Packed to free the most slots within the game's own limits (a bundle holds 64 seeds, or 16 pearls, or one sword): the smallest leftovers first, and only whole stacks, since half a stack in a bundle still leaves its slot taken. Things bots plan around (food, torches, fuel, arrows, building blocks, keys) stay out. Tested: a bag at 31 slots, no bundle: one made, eight leftovers packed to 54/64, the forty seeds that wouldn't fit whole left loose.
- **Tunnelling to a crafting table with one in the bag.** MadisonBeer's pickaxe broke and she dug toward a table thirteen blocks off through the dirt, got stuck, and went wandering, with a table in her bag the whole time. A bot carrying a table now puts its own down when the nearest one is a walk away or out of sight (and picks it back up after).
- **Setting up a furnace in a pool.** Redlantern spent four minutes bobbing in an underground pool trying to put a crafting table and a furnace down in it. A bot about to put one down while it's in the water now gets out onto dry ground first.

**New: live bot snapshot (testing jar).** `logs/joinedthegame-bots.json`, next to the debug log, rewritten every five seconds while the game runs: every bot online with where it is, health, food, what it's doing and why (goal, focus, any "holding off" reason), who it is, its home and house, where its stuff is if it died, everything it's wearing and carrying (counts, durability, enchantments), and how it feels about everyone it knows. Plus the real players' positions, the day and the time. Plain JSON, swapped in whole each time so it's never half-written: anyone (a person, a script, another AI) can open it mid-game for the state of things now, instead of digging back through the log.

**New: Frost Walker.** Bots wearing Frost Walker boots (from a ruined portal chest or an enchanting table) now walk straight across still water on the ice they make, instead of planning a swim, ending up on top of the ice and pacing back and forth. Tested: 15 blocks of pond crossed on foot in about 5 seconds.

**New: bot inventories are look-only without cheats.** The bot list's "Inv" button (and `/jtg inventory`) let anyone take whatever a bot was carrying in a plain survival world. Now, unless you have cheats (op, or a singleplayer world with cheats allowed) or you're in creative, it opens as "Name's inventory (look only)": you can see everything they've got, but nothing can be taken out or put in (no clicking, shift-clicking, number-key swaps, dragging or double-click gathering on their side). To give a bot something, toss it to them. With cheats on, it works like before.

**Lag with bots spread out (big one).** Every bot is a player as far as the game's concerned, so each kept the world loaded and running out to *your* render and simulation distance, since in singleplayer those are the server's settings. At 20/20, that's about 1,700 chunks per bot: seven bots spread out cost as much as eight people each playing on render distance 20, and the server ran seconds behind ("Can't keep up!" every 20 seconds in the 31st's first session). Bots now hold their own, smaller area: 10 chunks out (160 blocks), or your simulation distance if that's less. That covers everything a bot looks for (exploring is within 96 blocks, homes within 128, long trips go 48 blocks at a time). They still count as players for mob spawning, so nights around a lone bot are as dangerous as before. Your own render distance is untouched. Tested on the dev server at 20/20 with seven bots 1,500 blocks apart: 30,000 loaded chunks and 41 ms a tick before (50 is where lag starts), 15,500 and 9-11 ms after.
- The live snapshot now has `msPerTick` and `loadedChunks` at the top, so lag shows up there straight away.

**Mining the block you're standing on with lava under it.** Duckatyt mined the iron he was standing on and was dead in lava a second later. Blocks dug *through* to reach an ore were checked for lava, but the ore itself wasn't. Now nothing is broken that lava's touching, and a bot won't dig out its own floor if there's lava anywhere in the drop below it, at the bottom, or beside where it would land ("lava by the block at ...: leaving it").

## 0.9.0 Living, together (the full release)
The biggest update yet, all in one: bots that live like people (needs, a plan for the day, taking it easy, knowing when not to interrupt), the building update (pitched roofs, new houses, pens, tree farms, gardens, paths), the new roster, and relationships: feelings, friends, rivals, John Wick, romance and living together. The previews (pre1 to pre5, below) have the details; this is what's new since pre5.

**Living together** (part 3, step 5)
- Partners who've been together a while move in together: the one with the lesser home (a hut, or a smaller house; the less homely one if they're even) brings a bed, its own or a new one, and puts it down beside its partner's, inside. It beats carrying on with a place of its own.
- From then on it's home to both: the same chests (what's in them is shared, not stolen), beds side by side.
- Houses aren't re-built once they're finished, so changes a partner makes stick.
- Break up, and the one who moved in moves out, and won't move back into the ex's place by accident (its old bed there looks free).

**Crouching at a bot (reworked: it hadn't changed since the first version)**
- Crouching near a bot is saying hi: it crouches back and it counts like any hello (you get to know each other a little better, once every ten minutes). A bot that can't stand you just gives you a look.
- Crouching while looking right at it is asking it to come along, and the answer is how it feels about you: a partner or close friend comes for the full time (followSeconds, 5 minutes by default), a friend for a while, someone who likes you for a bit; a stranger only if it's the sociable sort, and only briefly; one that dislikes you, or a rival, says no. The debug log says why.

**Getting to know each other early on**
- Saying hi: a sociable bot passing someone it barely knows (close by, daytime, safe) goes over and crouches hello; the other stops a moment and says it back. A couple of seconds, then back to work. Both know each other a little better and warm a little. Once per person every ten minutes, one hello every three minutes at most; loners hardly bother.
- Company is easier to come by: very sociable bots will visit someone they just know a bit, a little further away, and time spent around each other counts from a bit further off.

---

## 0.9.0-pre5 Love (preview)
Part three of 0.9.0, step 4: romance. Everything in pre4, plus:

**Romance** (between bots only; no sexual content, ever)
- Every bot has a gender and an orientation: straight, gay, lesbian or bi. Gender follows the roster. Orientation is rolled once per world and saved (the same bot can be different in another world: the bots are fictional versions, not claims about anyone). Fixed by request: Redlantern8808 (male, straight), Duckatyt (male, bi), cozmoiscool130 (male, straight). `/jtg debug <bot>` shows it.
- Attraction grows only between bots who could be into each other, and only once they're good friends who know each other well: time together, help, gifts. A romantic sort falls harder, and personalities that fit (alike in what matters day to day) speed it up. It can be one-sided (a crush).
- At the end of some time together, a bot that's really into someone (and isn't with anyone) may ask them out, giving them a flower first if it has one. They say yes or no from their own feelings. A no is awkward, not a grudge: it won't ask again for a while.
- Someone who's already with someone says no, and their partner, if they see it, doesn't like the one asking (jealousy can grow into a rivalry).
- Partners: go to each other first for company (and want it more often), bring each other along when they take it easy, share food sooner, give each other flowers, and jump into each other's fights first.
- Breakups: betrayal ends it at once (hurting them, killing their pet, taking their stuff, a broken truce), falling out ends it, and partners who don't see each other for days drift apart. Exes keep their distance for a couple of days.
- `/jtg relationships` shows partners, exes and crushes.

**Fixed**
- Hiding for the night out in the open: water beside a bot counted as a wall and leaves over its head as a roof, so a bot at the edge of a pond under a tree "hid" without digging at all, and then flip-flopped between hiding and mining. Now only full solid blocks count as cover, and a bot about to dig in beside water walks a few steps to dry ground first.
- Holes dug beside water (hiding, or a path digging down) flooded and trapped bots, sometimes other bots that fell in. Bots don't dig down beside water any more.
- A John Wick hunting spell carried on into the night: it ends at nightfall unless the bot's geared for the dark.
- A visit to a busy friend never ended (they kept moving off): trailing after them counts, and a visit wraps up after six minutes at most.
- Accepting a partner's invite while mining: it goes, instead of saying yes and carrying on mining.

Testing: `/jtg debug <bot> partner <other>` puts two bots together; `/jtg debug <bot> feel` takes an attraction too.

---

## 0.9.0-pre4 Rivals (preview)
Part three of 0.9.0, step 3: rivalries. Everything in pre3, plus:

**Rivals**
- Bad enough history (repeated theft, being killed, a broken truce...) and two bots become sworn enemies: how bad depends on who they are (vengeful bots get there sooner, forgiving ones hardly ever). Bots can become your rival too.
- Rivals who run into each other usually fight (about 90% for the deepest hatred), once per meeting. A real fight: best weapon, waiting for a full swing, jumping for crits, stepping back out of reach, strafing, shield up when the other's ready to swing, a bow at range. Never while drowning, hurt, dug in for the night or with mobs about.
- The first fight makes it mutual.
- Self-defence: hit someone for real (not a friendly tap) and they hit back, until you stop. A close friend's hit is taken as an accident.
- Not always 1v1: a close friend of one fighter jumps in on their side (if they don't like the other one). Whoever gets ganged up on remembers it.

**Peace (the only time bots talk)**
- Losing badly or outnumbered, a bot may call peace in chat ("ok ok, truce?"), backing off while it waits. The other answers ("fine." / "no."), holding off while it thinks: a forgiving bot with no long grudge often says yes; a winning one, or one with a long history of bad blood, usually says no. Proud bots call it less, and their lines are gruffer.
- Yes: they back off, and a truce holds for a day. Vengeful bots sometimes break one, and nobody forgets that.
- Killing someone who called peace (refused, or never answered) is remembered for good, and any bot that saw it thinks less of the killer.
- You can join in: when a bot calls peace on you, say peace / truce / ok / fine to accept, or no (silence is a no). Or say peace or truce yourself near a bot fighting you.
- Making up is very rare: rivals under a truce, something good between them, a forgiving bot. They become neutral, not friends.

**Getting away**
- A bot that decides a fight isn't worth it never digs in: it runs home (shutting the door behind it) or to its friends, shield up when they're on its heels, an ender pearl if it has one. Chasers give up after a while (sooner the less they care).

**Winning**
- The loser's stuff is taken only when the hatred runs deep. The loser finds out.

**Pets**
- Killing a pet only counts on purpose (a hit aimed at it, or something shot at it; not a sweep, an explosion, fire or lava). Killing a pet that went for you first is self-defence: it hurts, but less.
- Some bots kill pets: out of spite (deep hatred of the owner), or because they're pranksters (a new trait, mischief: PenTester, Grian, Duckatyt and markiplier have the most). Yours too.
- The owner's reaction is who they are: peaceful bots grieve and go and find a new pet, middling ones make the killer their rival, and the most vengeful go John Wick (certain for JackBlack, Duckatyt and markiplier).
- John Wick: maximum hatred, for good. It hunts the killer in spells (going out to find them, then getting on with life a while), and the moment the killer shows up it drops everything and goes for them. It never calls or takes peace, never retreats (staying alive aside: it still eats, breathes and heals first) and always loots.

**Config**
- `rivalries`, `playerRivalries` and `fightsToTheDeath` (off: fights end once someone's badly hurt).

Testing: `/jtg debug <bot> spite` sends a bot after the nearest pet it isn't fond of.

---

## 0.9.0-pre3 Friends (preview)
Part three of 0.9.0, step 2: friendship. Everything in pre2, plus:

**Company**
- A new need, Social: bots want company now and then (sociable ones more often). When they do, they go and find someone they like, another bot or you, and spend a few minutes with them: tagging along if they're busy, standing about if they're not. Both sides count it: a bot that comes to see another one gives them company too.
- Who they go to see depends on how they feel: someone they like and know, never someone they dislike.
- Being around someone you don't mind slowly warms you to them, so bots who keep crossing paths drift into liking each other.

**Friends** (a bot likes them well: friends with you counts too)
- Friends share food: a bot with food to spare walks over and throws some to a hungry friend, you included. A bot friend who catches it is grateful. Starving friends come first, before chores.
- Friends defend each other: a bot steps in when a mob goes for a friend. It still helps real players by default, but not one it dislikes. Whoever was saved knows it, even if the mob turned on the helper before it died.
- Friends muck in: working the same tree as a friend isn't competition, and picking up a friend's logs isn't stealing. A friend helping themselves gets let go.

**Keeping their distance**
- A bot that dislikes someone keeps away from them: it won't chop near them, won't visit them, and won't step in for them.

**Fixed along the way**
- `/jtg focus <bot> leisure` (and the new `social`) ran out the moment it was set.
- A visit under way isn't cut short by its time running out.

Testing: `/jtg debug <bot> feel <other> <liking> <familiarity>` sets how a bot feels about someone.

---

## 0.9.0-pre2 The new roster (preview)
Everything in pre1, plus the roster overhaul.

**Who joins now**
- 46 new default names: celebrities, Minecraft YouTubers and creators, and the original regulars who stayed (Redlantern8808, Duckatyt, cozmoiscool130, TorchBearer99, BedrockBenny, PixelPickle, AxolotlAndy, MudMuncher), plus PenTester, the buggy idiot.
- The random generic names are retired from new rosters. Bots already living in an old world keep their names, and the new names are added to that world's roster alongside them.
- Every new name has its own (made-up) personality.

**Skins**
- Creators with a real Minecraft account use their real skin.
- Everyone else gets a drawn skin bundled with the mod, with wide or slim arms as fits.
- Jack Black is dressed as his Steve from A Minecraft Movie: cyan tee, jeans, brown boots, full beard.
- To swap any skin, put a PNG named after the bot (lower case, like `sabrinacarpenter.png`) in `config/joinedthegame/skins/`.

**Sharing (a first taste of relationships)**
- Bots can see what others are working: the tree someone's chopping (the whole tree, every log joined to it, however tall) or the vein someone's digging. With other trees about, they go for their own instead of all piling onto one.
- The only tree about and someone's on it: each bot decides once, by who it is. A selfish one (reckless, a hoarder, not one for company) goes for it too; a patient one waits its turn, standing back out of the way, and carries on when they're done (up to a minute and a half); the rest leave it to them and look elsewhere. First come, first served: stepping away for a moment to pick up the drops doesn't stop it being yours.
- Competing isn't stealing. Chopping the same tree is competition. Picking up logs someone else chopped off their own tree is taking what's theirs: much rarer, mostly the selfish ones, and never by a bot that chose to wait for or leave that tree. Logs off a tree you'd piled in on aren't anybody's in particular; logs that came off your own tree are just your wood back.
- They only remember what they notice: a bot whose tree gets crowded, or whose logs get taken, remembers it only if it saw (close enough, nothing in the way). Crowding counts once per pile-in, not once per armful.
- These go in the diary as their own kinds of memory (went for the same tree, piled in on my tree, took what they'd chopped, they took what I'd chopped, waited my turn, left a tree to them), ready for relationships, trust and grudges in part three. For now a little bad blood from what someone did makes a bot a bit more likely to do it back ("after last time"); one stolen log is never a feud.

**Feelings (part 3, step 1: the start of relationships)**
- Every bot now has its own feelings about everyone it has met, other bots and real players alike: liking, trust and how well it knows them. Saved with the bot, one-sided (what one bot thinks of you is only in its memory). See them with `/jtg relationships <bot>`.
- Built from what actually happens:
  - getting to know someone: meeting them, time spent around them;
  - being hit (a friendly empty-handed punch to get a bot's attention doesn't count);
  - being killed (the killing blow isn't counted twice);
  - a player giving them something useful (toss it to them: the good stuff, food when they're hungry, anything useful; junk means nothing);
  - someone killing a mob that was going for them;
  - from the tree sharing: someone piling in on their tree, taking what they chopped, or waiting their turn.
- Personality decides how much it matters: bad things hit vengeful bots harder and forgiving ones softer, good things mean more to sociable bots.
- Feelings fade slowly over in-game days, bad ones slower than good ones, but never all the way: part of everything sticks for good (more of the bad, half of being killed), so they always remember what you did. People they haven't seen for days get half forgotten ("barely knows them, but remembers what they did").
- In an older world, the first time a bot has feelings about someone it already has history with, the diary counts (who it met, who crowded its tree, who took its logs).

**Fixes**
- Taking it easy only when it's actually safe: not while hurt, not within a minute of being attacked, not with anything hostile nearby, and not while its stuff is lying where it died. (A bot hiding from a skeleton on 8 hp climbed out of its hole to go and take in the view, and was shot.)
- Dying ends whatever it was off to enjoy. Before, a bot that died on the way to a view walked straight back to the same hilltop after respawning (still "Taking in the view") instead of going for its stuff.
- A view spot is never picked near where it died or a place it's died before.
- When its stuff is guarded (a skeleton still standing over it), the bot now says so in the debug log and gets on with other things, then goes back for it once the coast is clear.
- Caught out in the water at nightfall (three bots in one AFK test): under the surface the game counts the sky as hidden, so "dig in for the night" switched itself off, a wander floated the bot back up, it switched on again and failed to dig in the water, every few seconds for up to the whole night. Now being in open water after dark is its own plan, and Shelter keeps the bot all night:
  - At the surface: swims for the nearest dry ground it can climb out onto, climbs out at the bank like a player, and digs in there.
  - Under the surface: swims up as it goes (coming up for air still takes over at once if it's running out).
  - No land in sight: heads back towards the last dry ground it stood on, a stretch at a time, watching for nearer land on the way.
  - No idea where land is: treads water at the surface and keeps looking, instead of flipping between goals.
  - A dry cave isn't any of this: the bot carries on mining or sits tight like before.
- Chopping their own house for wood (and other bots' houses, and villages): any log counted as wood, and a house's corner posts and beams look just like a trunk. Now wood comes off trees only. A log counts as a tree's if the logs joined to it have the tree's own leaves near the top (natural leaves, which players can't place; decorating a house with leaves doesn't make it a tree), or it's a fallen log lying on the ground; and anything touching building stuff (planks, stairs, slabs, doors, fences, glass, cobble, bricks, lanterns...) is a building, never wood, whatever leaves are about. Acacias, big oaks, cherries, dark oaks, mangroves, giant jungle trees and fallen logs all still count. No tree in sight: it goes looking further off rather than starting on its roof.
- Trees seen from afar that turn out to have no wood it can get at (all out of reach, or just leaves): marked as a dud for ten minutes, instead of walking back to the same spot again and again. A treetop straight overhead isn't "in the distance" either (a bot under a tall half-chopped spruce kept "heading over" to where it was standing).
- Picking up items: giving up on one (out of reach, or not worth digging for) now gives up on the pile of the same stuff with it, instead of starting and giving up on each one in turn.
- A crafting step planned before something else came first (building, putting stuff away) and its ingredients gone: the debug log now says so and it re-plans, instead of "recipe didn't match".
- Testing: `/jtg debug <bot> tree <pos>` says whether bots would chop that log and why not; `/jtg debug <bot> home <pos>` sets a bot's home.
- Looking for trees from the sea (JackBlack, respawning in an ocean at world spawn, went over an hour without a single log): with no tree within 24 blocks, a bot walked 20-40 blocks in a new random direction each time, which at sea just goes round in circles. Now it spots trees on the horizon the way a player does (treetops up to 96 blocks off, an island or a wood across a plain) and heads straight over. With none in view it keeps one heading instead of turning at random every few steps (back towards the last dry ground it stood on, if it's in the water), and only turns when it's blocked.
- Building a house, died and respawned at spawn: it walks back to finish it now. A site more than 128 blocks away counted as "teleported miles away" and the half-built house was thrown away. Bots now know a respawn from a teleport (a jump of 32+ blocks in one tick, just after dying or not): only an actual teleport mid-build starts the house over somewhere new. Both show in the debug log ("respawned 408 blocks from where I died", "teleported 714 blocks").

---

## 0.9.0-pre1 Living, not looping (preview)
The biggest update yet: bots that live like people instead of climbing a ladder, a building update, then the new roster and relationships. See DESIGN_0.9.0.md.

**A life, not a ladder**
- Needs instead of a ladder: gear, food, a home, wealth, curiosity, creativity, comfort, adventure, knowledge, rest. Each one rises and falls with the bot's situation, and each bot's personality decides how much each matters (`/jtg debug` shows them).
- What a bot sets out to do next is picked by what it needs, with odds by appeal, not always the top one: two bots in the same spot go different ways. The old "full iron before anything else" lock is gone: a bot that badly wants a home builds one with stone tools, like real players do. Dangerous things (raids, trial chambers, netherite, the nether) still wait until it's geared for them.
- Boredom: whatever it just did is less appealing for a while. Things that just went wrong are put off a while.
- A plan for the day: each morning two or three things it means to get done today, which pull extra until they're done.
- Taking it easy: a new need (rest) and a new thing to do. Fishing (a real fishing rod, cast, watch the bobber, reel in when it bites), sitting by a campfire at home (built the first time), climbing somewhere high to take in the view, or a stroll round its own place.

**Knowing when not to interrupt**
- The longer a bot's been at something, the more it takes to pull it away (emergencies still cut straight through).
- A goal that takes over and quits at once, with nothing to show for it, waits longer and longer before it can try again.
- Flip-flopping between two goals gets caught: it sticks with one for a while. Chopping and changing between lots of goals: it settles down.
- A big thing (a garden, a house) is never dropped for another big thing half way: the next one's only picked once it's done or has failed.
- Fixed: making a chest for storage and making a crafting table took turns grabbing the same wood.

**Building**
- Proper pitched roofs: stairs in the local wood, gable ends filled in, overhangs. The cottage, longhouse and two-storey house have them now (the stone house keeps its flat stone roof).
- New houses: a farmhouse, a bungalow, and a big lodge (the top tier, with the two-storey house).
- New personal projects: an animal pen (built, then animals led in by holding their favourite food, then bred), a tree farm, a sugar cane patch by the water, a flower garden, and paths (dug with a shovel from the door to the farm, garden, pen and the rest, with lamp posts).

**Fixes found along the way**
- Hiding from a skeleton: the tunnel out stays underground and under cover the whole way (one along a hillside came out right in front of the skeleton). Getting your stuff back after dying: not while a skeleton is still standing over it (unless it's about to vanish).
- Mining at night from a hole: routes stay under cover, instead of wandering back up to the surface and getting sent back down.

---

## 0.8.1 Bug fixes
**Nether portals**
- Half-built portals: a bot that ran short of obsidian half way through the frame (some went in a chest at home) gave the whole trip up and started a new frame somewhere else next time. Now it goes and gets the rest (chests and stash first) and finishes the same frame, and a half-built frame from an earlier trip is remembered and finished rather than left standing.
- The portal kit (obsidian, flint and steel, the gold helmet) stays on a bot for any nether trip, netherite ones included, instead of being put away at home before it's built.
- A gold helmet isn't thrown out as "old gear" any more: one piece of gold armour is nether kit, not junk.
- Getting something ready for a trip (a gold helmet, say) and it turns up anyway: it stops going for it.

**Obsidian from lava**
- Bots remember lava pools they've seen and go back to one when there's none in sight, instead of digging off down a staircase to look.
- The obsidian deadlock: to pour, a bot wanted an open gap right beside the lava at its own level, which almost no real pool has (they sit in a dip with ground all round). So it walked up to the lava, couldn't use it, left, and came back. Now it pours on top of the rim, the way a player does, and the water runs over onto the lava.
- All four sides of a pool are tried before giving up on it, and the bucket's real reach is used.

**Big goals keep going**
- A nether trip or a house build under way isn't dropped by the "full iron gear first" rule when a tool or armour piece wears out mid-way (projects already weren't).
- Unfinished projects are remembered (kept over restarts): one that was set aside, or couldn't carry on just then, gets picked up again later, and pulls harder when a new big goal is chosen.

**Spawners and dungeons**
- A dungeon or mineshaft spawner is dealt with like a player would: get to it (digging in if need be), break it with a pickaxe, and go through the dungeon's chests, instead of fighting what comes out of it for ever. Overworld only (blaze spawners are what nether trips are for), and only when fit for it.

**Remembering what's at home**
- Chests round the house a bot built count as home storage, not just the ones near its bed (a longhouse's or two-storey house's storage room can be further off), so it doesn't go mining iron it's already got a stack of.
- At home it takes a fresh look in its chests every half minute, so things put in them while it was out don't go unnoticed.

**Not drowning**
- Out of air under water, a bot drops everything (fights included) and swims to the nearest place its head will be out of the water: sideways under a ceiling to a shaft or a gap, not just straight up into the rock. Under a ceiling it goes before the air gets low. With nowhere to swim to, it digs up towards the air. Before, it only held jump, which under any ceiling (a flooded tunnel it had just dug into, an underwater cave) meant drowning with air a few blocks away.

**From a 20-minute test in a fresh world (10 bots)**
- Sheep hunts failed over and over (every bot, every couple of minutes: ten bots soon hunt an area out). Now four string make a wool (no sheep needed), bots remember where they saw sheep and go there instead of wandering, and a hunt that comes to nothing waits longer each time (up to 20 minutes) before the next.
- Nights wasted: a bot in a shelter hole that wasn't perfectly sealed sat still all night (up to nine minutes) instead of mining from it. Two blocks down with walls most of the way round now counts as safe to mine from (down and sideways only, never up into the open).
- Reckless bots skipped sheltering at night with stone tools and no armour, and died. Reckless now still needs a shield or a couple of armour pieces before it stays out.

**Loops found in your debug log**
- The place-a-block, mine-it, jump, place-it-again loop: mining and picking up items were taking turns every couple of seconds. Every block mined dropped an item; an item up on a ledge got a pillar built to it; mining then dug back down through the pillar (dropping more). Bots won't build or dig their way to a bit of cobblestone or dirt any more (still will for ore, food, logs, valuables), and they stick to the item they picked.
- Digging in for the night and "looking for a home" took turns all night: climb out to go house-hunting, get sent back down the hole, climb out... No house-hunting in the dark now unless the bot's geared for it (or already deep underground, where it makes a home right there).

**Staring at a block and never swinging**
- Picking up items was allowed to break into a mining job for any item at all, every second or two (and with the new "don't build your way to cobblestone" rule it often gave up straight after). Each time, the mining job's route and swing started over, so the bot stood facing the block and never broke it, until the "stuck" timer moved it on to another block, and the same again. Now only something valuable interrupts mining; the mining job picks up its own drops as it goes.
- The block breaker remembers progress on the last few blocks, not just one: anything that switched it between two blocks used to start both from nothing every time (and each restart waits to turn and face the block first, so there was no swinging at all).
- "In reach" means the same thing to the walking and the breaking now: the bot could "arrive" somewhere the breaker said was too far, and just stand there.
- The stuck watchdog counts blocks actually broken, not "partway through one" (chipping at a block that never breaks used to count as busy for ever), and logs a full snapshot when it fires (testing jar).
- Hiding for the night: a lid with nothing to rest against (a hole only two deep) was tried for ever, the bot frozen in its hole. It gives up after two seconds and waits it out down there.

**Skeletons and digging in**
- Holed up from a skeleton, bots used to pop out as soon as nothing was within 3 blocks, get shot, dig in again, over and over until the skeleton walked in on them. Now the bot remembers the archer that sent it down, counts any archer that's after it or has a clear shot at the hole (a skeleton 12 blocks off with a view is worse than one 8 off behind a wall), and only comes out after 10 quiet seconds. Healed up and it's a fight it'd win: it goes and finishes it. Not a fight it'd win: after 45 seconds it tunnels off underground, 14 blocks away from the skeleton, and comes up somewhere else. Never a nine-minute underground vacation: 90 seconds at most.
- Archers count as dangerous at any range (arrows don't care how far off they are), so "healed up, go and fight it" can't disagree with "not a fight I'd win" any more.
- New: routes that must stay underground (the tunnel out can't just go up and over, past the skeleton).

**Tools**
- Mining with a sword: the tool was only picked at the start of a block, so a fight or a torch going up half way left the bot hacking at stone with whatever it was holding. It re-checks every half second now.
- A broken pickaxe down a mine: the mining job carried on ("Mining iron" with bare hands). It stops now, and the bot makes a new pickaxe from what it's carrying.
- Bots carry a spare-pickaxe kit before heading down a mine: two sticks and a crafting table (the cobble's all round them).
- A pickaxe with only a few uses left gets a spare made before the next big dig (a stone one for a worn wooden one; an iron one for worn diamond or netherite, not more diamonds). The spare isn't thrown out as "old gear".
- Bots pick their crafting table back up after using it out in the field (they used to leave a trail of them).

**Animals**
- No more hunting sheep (or cows, or anything) through twenty blocks of rock: a bot only goes for an animal it can actually see, or when both of them are out under the sky.

**Testing jar**
- `/jtg debug <bot> path <from> <to>` shows the whole route a bot would plan, every dig and every block put down.

**New player: MadisonBeer**
- Madison Beer joins the roster (old worlds get her too, ready to `/jtg invite`). She has her own skin, drawn for the mod and shipped inside it (long dark-brown hair, brown eyes with winged liner, a black top with a little gold necklace, high-waisted jeans and boots, on the slim arms), because the real "madisonbeer" Minecraft account belongs to someone else.
- She plays her own way: creative and particular about how things look (a big builder who's always doing her place up), sociable, loves animals, keeps her things tidy, and would rather stay out of a fight than start one.

**Testing jar**
- A separate `-debug` jar for testing, with a bot debug log (`logs/joinedthegame-debug.log`, the last run kept as `-previous`): every bot's status every 10 seconds, every decision, and every goal switch with the scores (`Mining iron (38) -> Pickup (48)`). Plus `/jtg debug <bot>`: what it's doing, every goal's score, its big goal and unfinished projects, its path and personality. The normal jar has none of this.

**Combat**
- Shot at while swimming: it swims off instead of standing in the water with its shield up for ever.

---

## 0.8.0 Netherite
**Netherite**
- A new project for bots with a diamond pickaxe and a diamond loadout: upgrade it to netherite at a smithing table, enchantments and all, instead of replacing it.
- Picky about what gets upgraded: only the real, permanent loadout. Armour it's actually wearing, and its best sword, pickaxe, axe and spear (the one it'd use). Enchanted pieces always first (a Sharpness V sword before anything plain); never a hoe, a shovel or some spare diamond thing it happens to be carrying.
- Works out each step: whatever's put away at home first; an ingot from 4 netherite scrap (smelted from ancient debris) and 4 gold ingots (gold ore mined and smelted if need be); a smithing table made and put down if there isn't one; copies its last template (7 diamonds and a netherrack) when there's more to upgrade.
- Netherite nether trips: netherrack for template copies, a bastion for the template if there isn't one, then strip-mining down at y 15 where the ancient debris is, and home with it.

**Bastions**
- Bots tell the four bastions apart (treasure, bridge, units, hoglin stable) and remember treasure bastions specially: the treasure one always has a netherite upgrade template.
- A proper bastion raid: the nearest known treasure bastion first, any bastion otherwise (or go and look for one), chest by chest for the template, gold and treasure; out when it gets too hot. Everyday looting still leaves bastions alone.

**Anchoring (config)**
- In spectator mode you're not an anchor: bots aren't tied to you and go wherever they want (`anchorToSpectators`, off by default).
- New anchoring options alongside `maxDistanceFromPlayers`: `leashToPlayers` (on/off), `comeBackToPercent` (how close they come back), `leashOnExpeditions` (off by default: a nether trip or a trial chamber run isn't dragged back half way) and `driftTowardPlayers` (exploring wanders back toward you).

**Mining like a player**
- Bots mine a proper haul once they're down there, not just the three iron the next item needs before climbing all the way back up to smelt it and digging a whole new staircase for the next: iron and coal at least 16, gold and copper 8, lapis and diamonds 12, up to 32. They keep at it while it's turning ore up (three minutes without a find, or twelve all told; diamonds five and fifteen), and keep what they got.
- Down a mine, a bot puts down its own crafting table or furnace (from the cobble it's carrying) instead of walking back to one it remembers 30 blocks up. A remembered one is only worth the walk if it's close.
- A pickaxe that wears out half way down: straight to the best one it can make from what it's carrying (stone, or iron if it has the ingots), not back to a wooden one and up for a tree.

**Getting at things**
- A chest it can't see from anywhere (inside a shipwreck's hull, behind a ruined portal's blackstone): it gets within reach and digs through to it, like a player, instead of going back and forth for ever. Water beside the blocks is fine for this; lava isn't, and never into its own house. Works for every chest, furnace and table, not just looting. Waterlogged stairs, slabs and fences (a beached shipwreck's hull is full of them) count as solid blocks it can break through; plain water doesn't.
- A chest with something on its lid won't open: it takes the block off first (a bot once put its own crafting table on the shipwreck chest it was about to loot). And crafting tables, furnaces and smithing tables are never put down on top of a chest or anything else with an inside.

**Food**
- Hungry with nothing to hunt about: it checks its home chests and stash for food before wandering off to look.

**Fixes**
- The planner couldn't use anything that only comes out of a furnace as an ingredient (netherite scrap); it can now.
- Obsidian (and now ancient debris) asked for an iron pickaxe; it's a diamond one.
- Ancient debris, netherrack and quartz are never dug for in the overworld.
- A failed step in a long project waits a moment before trying again instead of using up every retry in one tick.
- A bot that lands in the nether takes a look round for structures before wandering off, and stops wandering the moment the bastion it's looking for turns up.
- Only one nether trip at a time: a netherite trip isn't shadowed by the usual "get home from the nether" one.
- A project started with `/jtg project` isn't quietly undone by the "full iron gear first" rule any more.
- A project that's about the nether (netherite) keeps running once the bot's through the portal; before, every project was switched off in the nether, so the usual blaze-rod trip took over.

---

## 0.7.4 Trial Chamber fixes
Found by running full trial chamber clears in a test world.

**Vaults**
- Vaults actually open now. Bots crouch when using an item on a block (so a shield in the other hand doesn't get in the way of stripping logs or tilling), but crouching with anything in your hands skips the block's own action, so the key never went in. Keys go in standing up now.
- Stands close enough for the vault to notice it. A vault only wakes up (and only takes a key) for a player whose block is within 4 blocks of it; being able to reach it isn't enough, so from 4-4.5 blocks away it stood there turning the key for ever. Now it steps within 3.
- A vault (or chest, or any block it uses) counts as in sight when any face turned its way can be seen, not only the exact middle: a vault tucked in an alcove between candles was "out of sight" from right in front of it.
- Vaults between trials: after beating a spawner, a vault in that room it has the key for gets opened there and then, before moving on.
- Locked vaults are remembered (per chamber, kept over restarts): when a key for one turns up later (an ominous key from an ominous trial), it goes back to that vault instead of hoping to stumble on it again. A vault right where it's standing is always opened before setting off for one further away.

**Getting the run going and keeping it going**
- A chamber run could never start while already standing in a chamber: looting the chamber's chests always came first. Being in one now counts as committed.
- A worn-out pickaxe or a broken shield halfway through a run no longer cancels it (the "full iron first" rule now leaves a run that's under way alone; the run checks its own kit).
- One big goal giving up (a house that keeps going wrong, a nether trip) no longer wipes out a different one that's under way, like a chamber run.
- Trial keys and ominous bottles kept in ordinary home chests (no secret stash) are fetched for a run; the heavy core for a mace too.
- A walk that doesn't work out inside a chamber (exploring, going back to a vault) no longer ends the whole run.
- `/jtg project <bot> trial_chamber` ignores the chamber's "come back later".

**Combat**
- No more running from mobs standing in water. Trial chamber dispensers splash water about, so half the mobs in there are in some; only a ranged mob fully under the water (a drowned with a trident) counts as "shooting from the water" now. (A breeze in a puddle had a fully kitted bot digging in at full health.)
- Hiding that isn't working (still out in the open after 20 seconds: half a box, a skeleton picking at it from across the room, chamber mobs never wander off) is given up on: fight it out if it's winnable, otherwise run. Before, it could hide in the open for ever.
- The log says why a retreat started.

**Getting about**
- No pillaring up off a slab or bed: from half a block down a jump can't clear the block that has to go in underneath, so it jumped on the spot (trial chambers are full of copper slabs).
- Feet stuck inside a block (with the head in a torch or air, so not "in a wall"): dig out of it.

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
