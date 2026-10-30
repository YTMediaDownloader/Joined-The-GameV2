# 0.9.0 part 3 design: relationships (draft)

What the third part of 0.9.0 adds: bots that know each other (and you), like or dislike each
other, make friends, fall in love, and sometimes become sworn enemies. Everything is built from what
actually happens between them (their memories), filtered through who they are (their personality).
No LLMs: it's rules, numbers and a handful of pre-written lines.

Status: **built (0.9.0).** Kept as the reference for how it works and why. Nothing here is built yet except
the groundwork from pre2 (claims on trees/veins, take/wait/leave, the
competed/crowded/stole/stolen/waited/left memories, a small grudge effect).

---

## 1. How a bot feels about someone

Every bot keeps its own view of everyone it has met: **other bots, and you (the player)**. It's
one-sided: A can hate B while B has no idea.

| Value | Range | What it means |
|---|---|---|
| **Liking** | -100 to +100 | Do I like them? |
| **Trust** | 0 to 100 | Will they do right by me (not take my stuff, keep a truce)? |
| **Familiarity** | 0 to 100 | How well do I know them (time spent near them, things done together)? |
| **Attraction** | 0 to 100 | Romance only, between bots, and only if their orientations match (section 6) |

From those comes a **status**: stranger, acquaintance, friend, close friend, partner, ex, rival,
or rivals under a truce.

- **It's built from memories.** Every event has a weight. Waiting your turn is a small plus,
  helping in a fight a big one; crowding a tree is a small minus, theft bigger, being killed by
  them huge.
- **Feelings fade slowly**, over in-game days. Good things fade faster than bad ones. A killing
  never fully fades.
- **Personality decides how much things matter.** There's a new derived trait, *forgiving vs
  vengeful*, built from existing traits. A vengeful, proud bot holds on to things; a forgiving one
  lets most of it go.
- **It's saved with the bot,** so it lasts across restarts.

### How they feel about you
You're judged the same way, by what you do.
- **Raises it:** giving them things (tossing food when they're hungry, gear), helping them in a
  fight, saving them, time spent nearby.
- **Lowers it:** hitting them, killing them, taking from their chests, breaking their house.
- **Killing their pet on purpose** is the worst thing you can do to a bot. How it takes it depends
  on who it is, from grieving and getting a new pet to John Wick mode (section 5, "When someone
  kills my pet").
- A bot that likes you is a friend to you (section 3). One that hates you enough becomes **your
  rival** (section 5). Romance is between bots only.

## 2. Chat: only for calling peace

Bots don't chat, with one exception: **calling peace** and the answer to it (section 5). That's the
only time a bot says anything. Everything else shows through what they do, and you can always look
it up with `/jtg relationships <bot>` and the diary (`/jtg memory <bot>`).

> Calling peace: "ok ok, truce?" · "enough. peace?" · Accepting: "fine." · "...fine. stay away
> from me" · Refusing: "no." · "not a chance"

The lines are picked to fit the bot (a proud bot's peace call is grudging, a gentle bot's is
quick). There are a few of each, so it doesn't repeat word for word.

## 3. Meeting people and making friends

- **The Social need switches on** (it's 0 today). Social bots seek others out; loners don't.
- **Meeting:** bots notice each other and get familiar over time by working near each other and
  running into each other.
- **What friends do:**
  - help each other against mobs;
  - share food when the other is hungry;
  - give small gifts;
  - visit each other's homes;
  - do leisure together (fishing, the campfire, the view);
  - work the same tree happily: it isn't competition between friends.
- **What builds friendship:** time together, help, gifts, waiting your turn, being saved from death.

## 4. When someone does you wrong (reactions)

What a bot does about something bad depends on **how bad it was**, **who did it** (their
relationship) and **who it is** (personality). With no chat, it all shows in what they do. From
mildest to strongest:

- let it go;
- keep their distance (avoid them for a while, stop working near them);
- take it back (pick up what they took, if it's lying about);
- hold a grudge (liking and trust drop, and it'll do it back sometimes, like "after last time" in
  pre2);
- start a rivalry (section 5).

For example, a friend helping themselves to your logs gets let go. A stranger doing it again and
again turns into a grudge. **One small thing never causes instant hatred,** with one exception:
killing their pet on purpose (section 5, "When someone kills my pet").

## 5. Rivalries (sworn enemies)

Some bots end up genuinely hating each other (or you), and when they meet they almost always fight.

### How a rivalry starts
- **Liking drops below about -70** after real history, such as:
  - repeated theft;
  - being killed by them;
  - losing a fight to them;
  - damage to your house;
  - a broken truce;
  - jealousy (they went after your partner);
  - refusing your peace offer and killing you.
- **Personality matters:** vengeful, proud, brave or reckless bots slide into rivalry faster.
  Gentle, forgiving ones almost never do.
- **It can start one-sided,** and becomes mutual after the first fight. You can end up with a bot
  rival too.

### When someone kills my pet
The worst thing anyone can do to a bot: deliberately kill its dog or cat. "Anyone" means you or
another bot.

**Only on purpose.** It counts only if the killer **hit the pet directly** (a melee hit aimed at it,
or something they shot at it) in the last few seconds before it died. These don't count:
- a sword sweep that caught it while hitting something else;
- an explosion, fire or lava;
- a mob.

Killing a pet that was attacking you at the time (it was defending its owner) is self-defence. The
owner still takes that badly (a big drop), but it's not the full reaction below.

**Why a bot would kill someone's pet.** Most never would. The ones that might:
- **Spite:** it hates the owner deeply (well into rivalry) and wants to hurt them where it counts.
- **Pranksters:** a new personality trait, *mischief* (set in the presets, so it's the same bot
  every time). High-mischief bots do cruel "jokes", now and then, to people they don't like much,
  without needing deep hatred.
- Bots can do this to **your** pets as well as other bots'.

**How the owner reacts depends on who it is.** It's worked out from its personality: vengeful vs
forgiving, brave, reckless, and how much it loves animals.

| Owner | Reaction |
|---|---|
| **Peaceful / forgiving** | Grieves, likes the killer much less (a big drop, not maximum), keeps away from them, and goes and **finds a new pet**. |
| **In between** | Becomes the killer's **rival** (deep hatred, fights on sight), but doesn't hunt them down. |
| **Vengeful / brave / reckless** (the most extreme: **100%**) | **John Wick mode** (below). |

Between those, it's a chance: the more vengeful the bot, the more likely John Wick mode is. A few
bots are certain to go there, and a few never will.

### John Wick mode
- **Maximum hatred, instantly and for good.** No build-up, no one-sided phase.
- **It hunts the killer, on and off.** The hunt isn't every waking moment: it has spells of going
  after them, and spells where it gets on with life (food, gear, its home) because the killer isn't
  its only priority right then.
  1. **Gearing up:** best weapon and armour it can make or fetch from its chests, a shield, food,
     maybe a bow.
  2. **Hunting spells:** goes looking, starting where it last saw the killer and then the killer's
     home if it knows it. For you, it knows roughly where you are, the way a player hunting someone
     on a server tracks them down.
  3. **Comes back for more** after every death, day after day.
- **If the killer shows up while it's doing anything else, it drops everything and goes for them.**
  Mining, building, fishing, a snack: it stops and attacks. That applies to bots and to you.
- **Survival still wins, every time.** "Drops everything" never includes staying alive. On half a
  heart, it eats, backs off or heals first, and only then goes for them. The same goes for drowning,
  lava, fire and falling: those are handled before the hunt, exactly as they are today.
- **Never accepts peace from them,** and never calls it.
- **Fights to the death and never retreats,** even outnumbered. Survival is still the hard override:
  it won't drown or burn chasing them, and it won't fight on in lava or at near-zero health with no
  food. Once it's healed, the killer is fair game again.
- **Always loots** the killer's stuff when it wins.
- **Never fades and never makes up.** The only thing that might ever soften it, very rarely: giving
  it a new pet (a tamed wolf or cat brought to it). Even then it's a truce, not friendship.
- The killer's friends who get in the way become fair game.
- **It stops** only if the config turns rivalries off (or rivalries with players off, for you).

### When rivals meet
- Once they see each other (within about 20 blocks), the chance of a fight scales with the hatred.
  For sworn enemies it's **about 90%: they nearly always fight.**
- **Survival still comes first.** No fight starts while either bot is drowning, on low health,
  dug in for the night, or in the middle of another fight.

### The fight
- A **real PvP fight** with whatever gear they have:
  - swords and axes;
  - shields;
  - bows at range;
  - armour;
  - eating to heal mid-fight;
  - strafing and critical hits.
- No taunts (chat is only for peace).

### Not always 1v1
- **Friends can jump in.** A close friend or partner of one fighter may join on their side, if
  they're nearby, brave enough, healthy enough, and don't like the other side. A bot that's friends
  with both stays out of it.
- **Being outnumbered changes things.** The bot on its own weighs it up: its hatred against the
  odds (health, gear, how many there are). If it's not worth it, it **retreats** (below) or calls
  peace. A vengeful bot with enough hatred might fight on anyway.
- **Jumping in costs something.** The one who got ganged up on remembers the bot who joined in, and
  likes them less.

### Retreating (no digging in: against a bot that just follows you down)
A bot that decides a fight isn't worth it gets away the way a player does:
- **Breaks line of sight and sprints off** (bot fighters sprint too, so distance and cover matter
  more than speed): round corners, behind hills, through trees, out of view.
- **Heads somewhere safer:** home (in through the door and **shuts it**), or towards its own friends
  and partner (back-up).
- **Shield up** while backing off, if it has one, to block hits on the way out.
- **Escape tools if it has them:** an ender pearl thrown away from the fight, water to swim through,
  a golden apple or food to heal while running.
- **Blocks the way** in a tunnel or doorway behind it, if it has blocks and the gap is narrow.
- **Calls peace on the way out**, if it thinks the other might take it.
- **The chasers give up** after a while. How long depends on their hatred: they lose sight of it, or
  it's got too far ahead, or it's reached home and its friends.

### Calling peace (the only chat)
- **Either side can call peace,** before or during the fight (and while retreating). It's usually
  the one losing, or the one with less hatred. Proud bots rarely do. Low health makes it more
  likely.
- **The other side decides:**
  - **Accept** ("fine."): both back off, and a **truce** holds for a while (about an in-game day).
    The hatred eases a little.
  - **Refuse** ("no.") and keep going, possibly to the death.
- **What sways that decision:**
  - their own hatred;
  - forgiving vs vengeful;
  - whether they're winning (a winner is less likely to accept);
  - whether truces have been broken before.
- **With you:**
  - **A bot calls peace on you** in chat. Type **peace / truce / ok / fine** to accept. Type **no**,
    or keep hitting it, to refuse. Saying nothing for a while counts as no.
  - **You call peace** by typing **peace** or **truce** near a bot fighting you. It answers in chat.
- **Killing someone who called peace is remembered forever** by the victim. Bots who witness it
  trust the killer less.
- **Vengeful bots sometimes break a truce.**

### How it ends
- **The loser dies or gets away.** A death drops their items and they respawn, with the hatred
  deeper than before.
- **Looting the loser:** the winner takes the loser's dropped stuff **only if its hatred is above a
  set point** (deep hatred: rubbing it in). Below that, it leaves the drops alone. Being looted is
  remembered and makes the hatred worse.
- **Making up is very rare.** It needs a truce, time, and a good moment (one saves the other from
  a mob, a gift is accepted), plus forgiving personalities on both sides. Even then it's a small
  chance each time. When it happens they "bury the hatchet" and become neutral, not instant friends.

### Config
- Rivalries on or off.
- Bot rivalries with players on or off (separately).
- Fights to the death on or off (off means a fight ends at low health with a retreat or a peace
  call).

## 6. Romance

- **Orientation per bot:** straight, gay, lesbian or bi. It's set in the presets for named bots
  and random for generic ones. (Trans: only if the user decides later.)
- **Between bots only.**
- **Attraction needs:**
  - matching orientations;
  - friendship first (close friends);
  - personalities that fit;
  - time together.
- **Asking:** one bot asks the other out, who says yes or no based on their own feelings. Shown in
  what they do (and in `/jtg relationships`), not in chat.
  - **No:** awkward, with a small dip in feelings, but no hatred as a rule.
- **Partners:**
  - spend leisure time together (fishing, the campfire, the view);
  - walk together;
  - defend each other hard (they're who jumps into a fight first);
  - share food and items freely;
  - give gifts (flowers);
  - eventually **move in together** (section 7).
- **Jealousy** links into rivalries: a love triangle can make rivals.
- **Breakups are rare:** neglect, jealousy or betrayal. Exes are awkward around each other.
- **Never any sexual content.** Romance is behaviour only. Celebrity bots are included
  (fictionalized; this build is private).

## 7. Shared homes

- Partners (and later maybe roommates) share a house and its chests. Taking from your shared home
  isn't theft; taking from someone else's chest is.
- **Renovations** follow the rule from the 0.9.0 design doc: the house keeps an "as built" record,
  so a resident's deliberate change isn't treated as a missing block, and they don't undo each
  other's work.
- Moving in means one moving into the other's house, or building a bigger one together.

## 8. Seeing it in game
- `/jtg relationships <bot>`: how it feels about everyone it knows, you included (liking, trust,
  status).
- Diary entries in `/jtg memory`.
- The peace calls in chat.
- The debug log (testing jar) explains every decision: why it joined a fight, why it retreated, why
  it accepted or refused peace.

## 9. Safety rails
- Survival is always the hard override.
- Chat only for peace calls.
- Config toggles for rivalries, rivalries with players and fights to the death.
- No sexual content, ever. Romance is between bots only.

## 10. Build order (each step works and is tested on its own)
1. The feelings model (bots and you), meeting people. **Done (2026-10-12):** liking, trust,
   familiarity, a lasting impression that never fades, fading, `/jtg relationships`. (The Social
   need turns on with step 2, when there's something social to do.)
2. Friendship and reactions. **Done (2026-10-12, pre3):** the Social need and company, sharing
   food, defending friends, friends mucking in, keeping your distance from people you dislike.
3. Rivalries: fights, friends jumping in, retreating, peace calls (the chat), truces, looting,
   making up. **Done (2026-10-12, pre4)**, plus pets and John Wick. Not yet: blocking a narrow gap
   behind you when retreating, and a new pet softening John Wick into a truce.
4. Romance. **Done (2026-10-13, pre5):** identities (orientation rolled per world; Redlantern8808
   and cozmoiscool130 male straight, Duckatyt male bi, fixed), attraction, asking out with a
   flower, loyalty and jealousy, partner perks, breakups.
5. Shared homes. **Done (2026-10-13, 0.9.0):** moving in together, shared chests, moving out on a
   breakup. Plus saying hi (early-game bonding).

## 11. Decided (2026-10-12)
- **Chat:** only for calling peace and answering it. Nothing else.
- **Feelings about the player:** yes. Players can end up as friends or rivals.
- **Looting:** a winning rival loots the loser's stuff only above a set hatred.
- **Orientations:** straight, gay, lesbian and bi.
- **Not always 1v1:** close friends and partners can jump in. The outnumbered bot decides whether
  it's worth it, and can retreat (not by digging down: see "Retreating") or call peace.
- **Killing a pet only counts on purpose:** a direct hit or shot at it, not a sweep, an explosion,
  fire or lava. Self-defence (the pet was attacking you) is a big drop, not the full reaction.
- **Bots can kill pets too,** yours or other bots': out of spite (deep hatred of the owner) or
  because they're pranksters (a new *mischief* trait).
- **The owner's reaction depends on personality.** Peaceful bots grieve, like the killer much less
  and get a new pet. Middling ones become rivals. Vengeful, brave or reckless ones are likely to go
  John Wick, up to certain for the most extreme.
- **John Wick mode:** instant, permanent, maximum hatred. It hunts the killer on and off (not every
  moment: it still gets on with life), but **drops everything the moment the killer shows up**
  (except staying alive: on half a heart it heals first).
  Never accepts or calls peace, never retreats (survival aside), always loots, never makes up (a
  new pet might, very rarely, buy a truce). Works the same for players and bots.
