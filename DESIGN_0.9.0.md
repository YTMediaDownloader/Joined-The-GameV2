# 0.9.0 design: living, not looping

The biggest update yet: the goal system overhaul, then the new roster, then relationships and
romance (in that order). This file is the plan for the first part, the brain.

## What's wrong today

- **One ladder for everyone.** `ProgressionGoal` wants the same things in the same order
  (wood pickaxe, stone tools, furnace, coal, iron pickaxe, shield, iron sword, iron armour...).
- **"Full iron first."** `updateFocus` locks every bot out of everything else (house, projects,
  the nether, wandering) until it has iron tools, iron armour and a shield. So every bot's first
  hour is the same hour.
- **Highest score always wins.** Two similar bots in similar spots do exactly the same thing.
- **No memory of what it's been doing.** Nothing gets boring; nothing is a favourite.
- **One road to each thing.** Iron always comes from mining, food always from hunting.
- **Interruptions.** One flat rule (switch when something scores 5 more), whether the current job
  just started or is nearly done. Most 0.8.1 bugs were two goals undoing each other.

## The new brain, in three tiers

### Tier 1: emergencies (unchanged, hard overrides)
Breathe, being punched, combat, starving, bedtime, getting your stuff back after dying,
sheltering at night, following/leash. Exactly as now: these cut through everything. **A bot is
never too creative to eat.**

### Tier 2: life (new)
**Needs** instead of a ladder. Each need is a level from 0 to 1, worked out from the bot's
situation every few seconds:

| Need | Rises when | Personality that cares most |
|---|---|---|
| Gear | tools, weapon, armour are missing or worn | diligent, brave |
| Food | few meals in the bag and the chests | everyone |
| Home | no home, or a home it's outgrown | homebody, builder |
| Wealth | no diamonds, few ores stocked | miner, diligent |
| Curiosity | it's been a while since it saw anywhere new | explorer, nomad |
| Creativity | it's been a while since it built anything | builder |
| Comfort | no farm, no animals, no pet | homebody, animal lover |
| Adventure | it's been a while since anything exciting | brave, reckless |
| Knowledge | unenchanted gear and levels piling up | scholar |
| Social | (0.9.0 part 3: relationships) | social |

**Activities** offer to meet needs: "gear up" (the old ladder, now one activity among many),
a diamond trip, building a house, each personal project (farm, dog, decorating, a bow, raids,
enchanting, trial chambers, netherite), a nether trip, exploring. Each activity's appeal is
*how much the needs it meets matter right now, to this bot*. Gear still matters most early on,
so early life is still mostly gearing up, but not *only* gearing up.

**Weighted choice, not the max.** The bot picks among its most appealing activities with odds by
appeal, so similar bots drift apart.

**Boredom and favourites.** Doing the same kind of thing again and again makes it less appealing
for a while; a bot's favourites (from its personality) pull a little extra.

**A plan for the day.** Each morning the bot picks two or three things it means to do today. They
get a bonus all day. Detours still happen when something interesting turns up (a structure, ore
in the wall, a player).

**Many roads.** Iron from mining, or from chests in structures it knows about; food from hunting,
farming or village farms. More roads get added over time.

### Tier 3: chores (as now)
Putting things away, smelting, picking things up, tidying. Short, and they don't interrupt
anything that matters (see below).

## A life, not a ladder (the user's brief)

They should seem like real people, not a progression script with a few personal goals bolted on.
So the list of things a bot might *feel like doing* gets much longer, and most of it has nothing to
do with gear. Some ideas, each an activity meeting one or more needs:

- **Home life:** extend or rebuild the house, add a room, decorate, light up the grounds, lay paths
  between the house, the farm and the mine, build a fence round the property, put up a garden.
- **Farming:** more kinds of farm (below), harvest, replant, breed animals, keep a pet.
- **Outdoors:** go fishing (new), go see somewhere new, climb a mountain for the view, follow a
  river, explore a cave for the fun of it, check on a structure it found.
- **Collecting:** flowers of every colour, music discs, a full set of something; show it off.
- **Making things:** a bow and arrows, a shield, enchanted gear, potions (brewing, later).
- **Adventure:** structures, trial chambers, the nether, raids.
- **Social** (part 3): visiting other bots, working together, trading, gifts, friendships, romance.
- **Rest:** sit by the fire at home, watch the sunset, wander the garden. Real people idle too.

## The building update

0.9.0 is a building update too.

- **Many more houses.** Today: hut, cabin, cave, village room, cottage, stone house, longhouse,
  two-storey, all with flat stone roofs. Add: proper pitched roofs (stairs), biome styles (desert
  sandstone, spruce taiga lodge, birch cottage, jungle stilt house, mangrove/swamp hut, snowy
  cabin), a tower, a hillside home dug into a slope, a big lodge, a treehouse. Which one a bot builds
  depends on its personality, its biome, what it can afford and its taste.
- **Houses grow.** Extensions and new rooms over time (a storage room, an enchanting room, a
  bedroom upstairs), not one house forever.
- **More farms.** Today: one 5x5 wheat field. Add: bigger and different crop fields (carrots,
  potatoes, beetroot), a pumpkin/melon patch, sugar cane by the water, animal pens (cows, sheep,
  pigs, chickens) with breeding, a tree farm, a flower garden.
- **The grounds.** Paths, fences and gates, lamp posts, a well, a dock, a campfire, a lookout.

## Believable mistakes (personality, not bugs)

Bots should get things wrong the way people do, from who they are, and it should make stories.
None of these are loops: each is one decision, logged as what happened.

- **Distractible** (done): working on something, it notices a lake, a structure, a cave, and the
  explorer or the laid-back one goes off for a while; the diligent builder notes it and carries on.
  The work's set aside, not forgotten. Never a timer: no sighting, no distraction.
- **Forgetful** (done): now and then walks off and leaves its crafting table behind.
- **Reckless** (done): takes the quick way down (five-block drops, and the knock that comes with it).
- **Inexperienced, and getting better** (started): a first house goes up slowly; one that's built
  half a dozen things gets on with it. Later: better choices in fights and mining with practice.
- **Indecisive** (to do): changes its mind about where to build, once, before the walls go up.

## Setbacks and other people's changes

- **A setback isn't the end of a project.** After building, a project checks what's really there,
  works out what's missing (dropped dying half way, blown up by a creeper), gathers exactly that
  and finishes (a few rounds at most). Done for pens and gardens; houses already re-gather.
- **Missing isn't the same as changed.** An empty spot where a block should be: put it back. Some
  other block there instead: someone changed it on purpose (a player, a housemate), so the bot
  accepts it as the new design and doesn't "fix" it back. (Done for fence rings.)
- **For relationships (part three):** shared homes need this properly: the house's "as built"
  record updates when a housemate renovates, the owner decides on big changes, and two bots never
  take turns undoing each other's work (that's just a two-goal loop between two bots).

## Knowing when NOT to interrupt (the lesson of 0.8.1)

- **Interrupting costs more the further along the current job is.** A new idea has to beat the
  current one by more than the work it would throw away. Emergencies still cut straight through.
- **Decide before taking over.** A goal works out whether it's worth it *before* taking over,
  never by taking over, finding it isn't, and quitting.
- **Quitting straight away gets noticed.** A goal that keeps starting and quitting at once waits
  longer and longer before it's allowed to try again.
- **Flip-flops get caught.** Switching back and forth between the same two goals a few times in a
  minute: the bot sticks with one for a while and logs it.
- **Interrupted jobs resume** where they were.

## Building it (in steps that each work on their own)

1. **Commitment** (this first): interrupt cost grows with time on the job, quick-quit backoff,
   flip-flop detection. Fixes a whole class of bugs before anything else changes.
2. **Needs**: worked out and shown in `/jtg debug`, not yet driving anything.
3. **Activities**: the focus roll and the "full iron first" lock are replaced by needs-based,
   weighted choice. Gear-up becomes one activity among many.
4. **Boredom, favourites and the day plan.**
5. **More roads** to iron and food.
6. **The life list**: the new everyday activities (fishing, paths, gardens, collecting, rest...).
7. **Building**: new houses with real roofs, biome styles, extensions; new farms and pens; the grounds.
8. **Long test**: an hour with ten bots in a fresh world, measuring how different their days are.

Then the roster, then relationships and romance.
