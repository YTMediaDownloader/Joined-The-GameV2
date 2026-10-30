# Joined the Game 0.9.0: player's guide

Everything 0.9.0 adds, and the numbers behind it. "Liking", "trust" and so on are what
`/jtg relationships <bot>` shows.

---

## 1. Bots live like people

- **Needs, not a ladder.** Gear, food, a home, wealth, curiosity, creativity, comfort, adventure,
  knowledge, rest and company. Each one rises and falls with the bot's situation. Personality decides
  how much each matters. `/jtg debug <bot>` shows them.
- **Big goals by appeal.** A bot picks what to do next from what it needs most, with some chance
  involved, so two bots in the same spot go different ways. Things it just did appeal less for a
  while, and things that just went wrong are put off.
- **A plan for the day.** Each morning, two or three things it means to get done that day.
- **Taking it easy.** Fishing, sitting by a campfire at home, climbing somewhere high for the view,
  or a stroll round its place. Only when it's safe: healthy, nothing hostile around, nobody attacked
  it in the last minute, and none of its stuff lying where it died.
- **Not interrupting itself.** The longer it's been at something, the more it takes to pull it
  away. Emergencies (air, fire, mobs, starving) always cut through.

## 2. Building

- **New houses:** pitched roofs, plus a farmhouse, a bungalow and a big lodge.
- **New projects:** an animal pen (it leads animals in with food), a tree farm, a sugar cane patch,
  a flower garden, and paths with lamp posts.
- **Wood comes from trees only.** Bots never chop a house's logs, theirs or anyone's. Fallen logs
  count as trees.

---

## 3. How a bot feels about someone

Every bot keeps its own feelings about everyone it's met, other bots **and you**. It's one-sided:
what one bot thinks of you is only in that bot's memory. It's saved with the bot.

| Value | Range | Meaning |
|---|---|---|
| Liking | -100 to +100 | Do I like them? |
| Trust | 0 to 100 (starts at 50) | Will they do right by me? |
| Familiarity | 0 to 100 | How well do I know them? |
| Attraction | 0 to 100 | Romance only, bots only |
| Lasting | -100 to +100 | The part of liking that never fades |

### "Knows them…" (familiarity)

| Shows | Familiarity |
|---|---|
| knows them **barely** | under 8 |
| knows them **a little** | 8 to 24 |
| knows them **fairly well** | 25 to 59 |
| knows them **well** | 60 and up |

How familiarity grows:
- **Meeting** (first time within 8 blocks): up to 3.
- **Saying hi:** +6. One hello takes a new acquaintance from "barely" to "a little".
- **Time around each other** (within 24 blocks, in sight): about +0.9 a minute.
  - "a little" takes about 9 minutes from scratch;
  - "fairly well" takes about 30 minutes in total;
  - "well" takes about an hour and a half in total.
- **Anything that happens between you** (a gift, help, a hit…): +1.5 each.
- **Out of touch:** after 3 in-game days without seeing someone, familiarity slowly fades
  (1% a day). They half forget the person, but never what the person did.

### The status word

Checked in this order: the first one that fits is shown.

| Status | When |
|---|---|
| partner / partner (rocky) | together (rocky: liking under 20) |
| wants them dead | John Wick |
| rival | sworn enemies |
| barely knows them (…but liked them / …but remembers what they did) | familiarity under 8 (the add-on shows if liking is +10 or more / -10 or less) |
| ex / ex (still close) / ex (bitter) | broke up (still close: liking 60+; bitter: under 0) |
| has a crush on them | attraction 50+ and liking 40+ |
| close friend | liking 60+ |
| friend | liking 30 to 59 |
| likes them | liking 10 to 29 |
| neutral | liking -9 to 9 |
| not keen | liking -29 to -10 |
| dislikes them | liking -69 to -30 |
| hates them | liking -70 or lower |

### What changes liking and trust

Base values below. Personality scales them:
- **bad things** count from ×0.6 (forgiving bots) to ×1.4 (vengeful bots);
- **good things** count from ×0.7 (loners) to ×1.3 (sociable bots).

| What happened | Liking | Trust |
|---|---|---|
| Being around them (only while liking is -5 to +25; it stops at +25) | about +0.6 a minute | 0 |
| They said hi | +2 | +1 |
| Spent time together (a visit) | +3 | +2 |
| They waited their turn for my tree | +2 | +3 |
| They gave me something: useful 2, food when I'm hungry 4, valuable 6, a flower from my partner 3 (up to ×2 for a big stack) | +1 per point (+2 to +12) | +1 per point |
| They helped me in a fight (killed what was attacking me) | +8 | +6 |
| We made peace | +5 | +6 |
| They piled in on my tree | -4 | -3 |
| They hit me (a friendly empty-hand tap doesn't count; scaled by damage; in a fight we're both in, a third) | about -4 per hit | -4 |
| They took what I'd chopped (and I saw) | -10 | -12 |
| They jumped into a fight against me | -10 | -8 |
| They took my stuff after killing me | -12 | -10 |
| They went after my partner (and I saw) | -20 | -15 |
| They broke our truce | -25 | -40 |
| They killed me | -40 | -30 |
| They killed me after I called peace | another -30 | -30 |
| I saw them kill someone who'd called peace | -8 | -20 |
| They killed my pet on purpose | -60 | -50 |
| …while it was attacking them | -20 | -15 |
| …by accident (a sweep, a stray arrow) | -5 | -4 |

**Bots only remember what they notice.** Crowding, theft and jealousy only count if the bot sees
them happen: within 24 blocks, nothing in the way.

### Remembering and fading

- **Part of everything sticks for good** (the "lasting" value): 20% of good things, 30% of bad
  things, and half of being killed, a killed pet or a broken truce.
- **Liking drifts back toward that lasting value each in-game day,** never back to zero:
  - the good part fades 4% a day;
  - the bad part fades 1% a day (vengeful bots) to 4% a day (forgiving bots).
- **Trust drifts back toward 50** at 2% a day.
- **John Wick never fades.**

---

## 4. Friends

| Liking | What it means |
|---|---|
| 5+ and familiarity 5+ (very sociable bots: liking 0+) | someone it might visit for company |
| 30+ (friend) | shares food when they're hungry, defends them from mobs, works the same tree with them, lets it go if they take its logs |
| 60+ (close friend) | jumps into their fights |
| -30 or lower | keeps its distance: won't chop near them, won't visit, won't defend them (that includes you: bots defend players by default, but not one they dislike) |

- **Saying hi.** A sociable bot passing someone it barely knows (within 12 blocks, daytime, safe)
  goes over and crouches hello, and the other stops and says it back.
  - At most once per person every 10 minutes, and one hello every 3 minutes.
  - Chance each time: 15% plus 70% × how sociable the bot is.
- **Company.** The Social need builds up over about 30 minutes without company (18 for partners).
  The bot then goes to see someone it likes, up to 250 blocks away, and spends 2 to 4 minutes with
  them (tagging along if they're busy). Visits are counted on both sides.
- **Sharing food.** A bot with at least 6 spare food throws some to a hungry friend.
  - Hungry means food 8 or less for a bot, or 12 or less for you or a partner.
  - It won't give the same friend food again for 3 minutes.

### Crouching at a bot (you)
- **Crouch near it:** it crouches back, and it counts as a hello (familiarity +6, liking +2), once
  every 10 minutes. A bot that dislikes you just looks at you.
- **Crouch while looking right at it ("come with me?"):**

| How it feels about you | Answer |
|---|---|
| partner or close friend (60+) | comes along for the full time (`followSeconds`, 5 min by default) |
| friend (30+) | 60% of that (3 min) |
| likes you (10+) | 35% of that, at least 1 min |
| neutral or stranger | sociable bots (0.6+) come for 1 min; others say no |
| dislikes you (-30 or lower), rival, John Wick | no |

## 5. Sharing trees, and stealing

- **A claim is the whole tree.** A bot chopping a tree claims every log joined to it (or a whole
  ore vein), and others can see that.
- **First come, first served.** The claim lasts 12 seconds after they stop working it, so stepping
  away to pick up drops doesn't lose it.
- **If it's the only tree about,** a bot decides once, for about 3 minutes:
  - **take it** (selfish bots), and the owner remembers being crowded;
  - **wait its turn**, standing back (patient bots, if there's no other tree in sight); it gives up
    after 90 seconds;
  - **leave it** and look elsewhere.
- **Theft** is picking up logs someone chopped **off their own tree.** It's rarer than competing.
  A bot that chose to wait or leave never does it, and friends just share.

## 6. Rivals

- **Becoming rivals.** Rivalry starts when liking reaches somewhere between -60 (vengeful bots) and
  -90 (forgiving bots). The first fight makes it mutual. You can become a bot's rival too.
- **Meeting a rival.** Once per meeting (re-rolled after 2 minutes) a bot decides whether to fight:
  - 30% at -70;
  - 92% at -100.
  - During a truce it's almost never, unless it's a vengeful bot breaking the truce.
- **No fight starts** while the bot is below 60% health, in water, out at night without armour,
  or with mobs within 10 blocks.
- **Self-defence.** Hit a bot for real and it fights back until you've stopped hitting it for 6
  seconds. A close friend's hit is taken as an accident.
- **Friends jump in.** A close friend (60+) or a partner jumps in on its friend's side, as long as
  it doesn't like the other fighter (under 10; under 30 for partners).
- **Losing or outnumbered,** a bot may call peace, run, or (if angry enough) fight on.
- **Running away** never means digging in:
  - out of sight, home (shutting the door) or to friends;
  - shield up when they're close behind;
  - an ender pearl, if it has one.
  - Chasers give up after 8 to 33 seconds out of sight (longer the more they hate you; a minute
    for John Wick).
- **Looting.** The winner takes the loser's stuff only if its liking is -85 or lower, or it's John
  Wick.

### Peace (the only time bots talk in chat)

- **Calling it.** The caller backs off with its shield up, and the other holds off while deciding.
- **The chance of yes:** 45%, then:
  - + forgiving × 40%;
  - − grudge × 60% (the grudge is mostly the long-term "lasting" value, not the heat of the fight);
  - − winning × 25%;
  - − 15% if trust is under 10.
  - It always stays between 3% and 90%.
- **Yes:** a truce for one in-game day, and both like each other a bit more.
- **No, then the caller dies:** "killed me after I called peace". That's remembered forever, and
  any bot watching thinks less of the killer.
- **With you:** when a bot calls peace on you, type **peace / truce / ok / fine** to accept or
  **no** to refuse. Saying nothing for 10 seconds is a no. You can also type **peace** or **truce**
  near a bot that's fighting you.
- **Making up.** During a truce, each good thing between them has a 4% × forgiving chance of ending
  the rivalry. They become neutral, not friends.

### Pets and John Wick

- **On purpose only.** A pet kill counts if the killer hit the pet directly, or shot it, within 3
  seconds of it dying, and the pet didn't go for them first.
- **Why a bot would do it:**
  - **spite:** it hates the owner (-85 or lower), with a 20% chance per meeting with the pet;
  - **pranks:** high mischief and no love for the owner, with a small chance. The pranksters are
    PenTester (0.95), Grian (0.85), Duckatyt (0.7) and markiplier (0.65).
- **The owner's reaction depends on its vengeance score:**

| Vengeance | Reaction |
|---|---|
| 0.70 or more | **John Wick**, certain (JackBlack, Duckatyt, markiplier) |
| 0.45 to 0.70 | a chance of John Wick, otherwise a rivalry |
| 0.30 to 0.45 | the killer becomes a rival |
| under 0.30 | grieves, and goes and finds a new pet (stampylongnose, SelenaGomez, ArianaGrande…) |

- **John Wick:**
  - hatred at -100, forever;
  - hunts in 4-minute spells every 6 to 10 minutes, not at night unless armoured;
  - drops everything the moment the killer is in sight, but survival still comes first;
  - never calls or accepts peace, never retreats, always loots.

## 7. Romance (bots only, never sexual)

- **Who's into whom.** Gender follows the roster. Orientation (straight, gay, lesbian or bi) is
  rolled once per world. Fixed picks: Redlantern8808 and cozmoiscool130 are male and straight,
  Duckatyt is male and bi. `/jtg debug <bot>` shows it.
- **Attraction** grows only between compatible bots, once liking is 40+ and familiarity 30+:
  - a visit together: +4;
  - help in a fight: +3;
  - gifts: up to +3;
  - being around each other: a little.

  It grows faster for a romantic sort and when personalities fit. Bad things knock it down.
- **Asking out.** At the end of a visit, a bot with **attraction 60+ and liking 50+**, single and
  not turned down in the last 3 in-game days, asks, giving a flower if it has one.
  - The other's chance of yes: 30% + 60% × their attraction, if their attraction is 35+ and their
    liking 40+. Otherwise it's 3%.
  - Someone who's taken says no.
- **Partners:**
  - see each other most, and want company more often;
  - bring each other along to leisure;
  - share food sooner and give flowers;
  - jump into each other's fights.
- **Moving in.** After half an in-game day together, the one with the lesser home moves in with
  the other.
- **Breakups:**
  - betrayal: being hit for real, killed, a pet killed, stuff taken, a broken truce;
  - falling out: liking drops below 0, or they become rivals;
  - drifting apart: 4 in-game days without seeing each other while attraction falls under 25.

  Exes don't visit each other for 2 in-game days, and the one who'd moved in moves out.

---

## 8. Commands

| Command | What it shows or does |
|---|---|
| `/jtg relationships <bot>` | how the bot feels about everyone it knows (status, liking, lasting, trust, how well it knows them) |
| `/jtg memory <bot>` | its diary: met, crowded my tree, made peace, broke up… |
| `/jtg debug <bot>` | what it's doing, its scores, its needs, gender and orientation, its partner |
| `/jtg focus <bot> social` (or `leisure`) | sends it off for company (or to take it easy) |

**Debug jar only:**
- `/jtg debug <bot> feel <other> <liking> <familiarity> [attraction]`
- `/jtg debug <bot> partner <other>`
- `/jtg debug <bot> spite`
- `/jtg debug <bot> tree <pos>`
- `/jtg debug <bot> home <pos>`

## 9. Config

| Setting | Effect |
|---|---|
| `rivalries` | rivalries and fights between bots |
| `playerRivalries` | whether bots can become your rival and come after you |
| `fightsToTheDeath` | off: fights stop once someone's badly hurt |
