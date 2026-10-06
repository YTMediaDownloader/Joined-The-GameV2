# Joined the Game

AI players for **Minecraft 26.3 (NeoForge)**. Invite bots into your world and they join like
real players, then play survival on their own: chop trees, craft tools, mine, smelt, hunt,
fight and slowly gear up. It's fake multiplayer for when you're playing solo.

No LLMs. Every bot brain is hand-written game AI, and it works offline.

Phase 1 is a Java port of Gamemode One's Bedrock *AI Players* add-on, adapted to Java (a key
binding and commands instead of an admin item, crouching instead of emotes). See
`../ai-players-research/RESEARCH.md` for the original's feature list.

## Playing

1. Install NeoForge for Minecraft 26.3.
2. Put `build/libs/joinedthegame-<version>.jar` in your `mods` folder (the mod is needed on
   the server; clients need it for the K screen).
3. In game, press **K** to open the AI player list, or use `/jtg`.

| Do this | What happens |
|---|---|
| **K** → Invite | A bot joins near you ("X joined the game") |
| Crouch while looking at a bot | It crouches back and follows you for 5 minutes |
| Crouch near a bot | It crouches back (says hi) |
| Punch a bot | It takes the hit, stops what it's doing and looks at you |
| Mine near bots | They come and mine with you |
| Head off exploring | The friendlier bots tag along |
| Free beds nearby (villagers' count) | A homeless bot claims one; with none around it crafts a bed and digs a burrow |
| Put chests near a bot's bed | It stores its stuff there |
| A bot gets iron | It picks a big goal by personality: diamonds, settle down, or wander |
| A bot settles down | It moves into a village house, digs a hillside room, builds a hut or builds a proper cabin; adds chests, a secret chest under the floor and a name sign. Later it may build a cabin and move everything in |
| A bot is in full iron | It starts mixing diamond trips with personal projects: a wheat farm, taming a dog, doing up the house |
| A bot dies | It runs back for its items (5 min limit; 3 deaths in a row = fresh start) |
| Night falls | Bots go to bed if they can: their own, a free one nearby, one they carry or one they craft. Otherwise they dig a covered hole. Bots with a home count toward skipping the night |

### Commands (`/jtg`, ops or singleplayer owner)

| Command | What it does |
|---|---|
| `/jtg invite <name>` | Invite a bot. A new name adds it to the roster |
| `/jtg kick <name>` | Kick (can be invited again) |
| `/jtg ban <name>` / `unban <name>` | Ban or unban |
| `/jtg tp <name>` | Teleport the bot to you |
| `/jtg inventory <name>` | Open its inventory to give or take gear |
| `/jtg list` | Who's in and what they're doing |
| `/jtg focus <name> <house\|diamonds\|wander\|none>` | Nudge a bot's big goal |
| `/jtg project <name> <farm\|wolf\|decorate\|house>` | Start a personal project now |
| `/jtg memory <name>` | What a bot remembers: plans, places (* = never forgotten) and its latest diary |
| `/jtg guide` | Get the guide book (anyone can use this) |

### Config (`config/joinedthegame-synced.toml` in the game folder)

`maxBots`, `opsOnly`, `pauseWithoutPlayers`, `maxDistanceFromPlayers`, `followSeconds`,
`lootChests`, `giveGuideBook`.

## Fighting

Bots crit (jump-hit), back off between swings, weave toward skeletons, craft a shield after
their first iron and block arrows and creeper blasts with it. When losing they run away, or
pillar up if cornered. Nervous bots block more; brave ones push their luck.

## How it works

- **Bodies are real players.** Each bot is a `ServerPlayer` with an in-memory connection
  (`bot/BotConnection`, `bot/BotPacketListener`). That's why bots show in the tab list and
  on the locator bar, get join/death messages and count as players for other mods. A mixin
  keeps them as bots when they respawn, and another leaves homeless bots out of the night-skip count.
- **The brain** (`ai/BotBrain`) picks the most urgent goal every half second: fight, eat,
  sleep, follow, store items, hunt, copy the player, loot, progress, explore.
- **Progression** (`ai/Planner`) works backwards from "I want an iron pickaxe" using the
  game's real recipe list, so modded recipes work too.
- **Movement** (`ai/nav`) is an A* pathfinder with player moves: walk, jump, drop, swim,
  climb, dig through, dig down, bridge and pillar.

## Development

- `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runClient` / `runServer` start the game. Both run with
  `-Djoinedthegame.debug=true`, which logs every bot's state every 10 seconds.
- The dev server has RCON on port 25575 (password `jtgtest`) for scripted tests.
