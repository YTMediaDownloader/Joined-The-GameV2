package net.joinedthegame.ai.project;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Chambers;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.goal.CombatGoal;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A proper go at a Trial Chamber, start to finish, over as many visits as it takes:
 * <ol>
 *   <li><b>Ready?</b> Armour, a real weapon, food, health, a shield or a bow; the trial keys
 *       and ominous bottle from home. Brave and reckless bots go in earlier, careful ones
 *       prepare more, and every death in there makes it more careful.</li>
 *   <li><b>There</b>, with the long-distance travel (across the map, then a staircase down).</li>
 *   <li><b>The trials</b>, one spawner at a time: get in its sight to start it, stay with it
 *       while its wave is fought (the fighting itself is the combat goal's job), and only when
 *       the game says the trial's beaten (its state, not "no mobs about for a bit") wait for the
 *       reward and pick it up.</li>
 *   <li><b>Vaults</b>: each one this bot hasn't been rewarded by (the vault keeps the list),
 *       opened with the right key.</li>
 *   <li><b>Ominous</b>, if it's well enough equipped and has a bottle: drink it at the chamber,
 *       go past the spawners again (they turn ominous), then the ominous vaults.</li>
 *   <li><b>Leave</b> when it's done, hurt, hungry or out of food, and come back another time.
 *       Progress is kept: the chamber's spawners and vaults say where things stand.</li>
 * </ol>
 * Once started, everyday gearing-up (a bit of iron, a tree) doesn't pull it away; fights,
 * eating and emergencies still come first.
 */
public final class TrialChamberProject implements Task {
    private enum Stage { PREPARE, TRAVEL, WORK }

    private Stage stage = Stage.PREPARE;
    private @Nullable GlobalPos target;
    private @Nullable Task sub;
    private String note = "Heading for a trial chamber";
    private int ticks;
    private int scanIn;
    private @Nullable BlockPos spawner;
    private @Nullable BlockPos vault;
    private int focusTicks;
    private int huntTicks;
    private int explores;
    private boolean ominousRound;
    private boolean fetchedKeys;
    private final java.util.Set<Long> skip = new java.util.HashSet<>();
    private final java.util.Set<java.util.UUID> triedDrops = new java.util.HashSet<>();

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : this.note;
    }

    // --- choosing and readiness -----------------------------------------------------------

    /** The nearest trial chamber we know of with work left, not one we backed off from lately. */
    public static @Nullable GlobalPos pick(BotPlayer bot, BotBrain brain) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        var list = brain.memory().places().get(Structures.Kind.TRIAL_CHAMBERS.placeKey());
        if (list == null) return null;
        // Nomads and explorers will go a long way for one.
        double range = 300 + brain.personality().explorer() * 200 + brain.personality().nomad() * 300;
        GlobalPos best = null;
        double bestDist = range * range;
        for (var place : list) {
            GlobalPos p = place.pos();
            if (p.dimension() != here.dimension()) continue;
            BotMemory.Chamber c = brain.memory().chamber(p);
            if ("raided".equals(c.status) || c.restUntil > brain.now()) continue;
            double d = p.pos().distSqr(here.pos());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Fit to take on a chamber? Null if so, otherwise what's missing. The bar's from real combat
     * power (armour, weapon, health, shield) rather than a fixed list, moved by personality and
     * by how it went last time. Ominous trials want a lot more.
     */
    public static @Nullable String notReady(BotPlayer bot, BotBrain brain, boolean ominous, int deaths) {
        var p = brain.personality();
        if (bot.getHealth() < bot.getMaxHealth() * 0.8f) return "hurt";
        int food = Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s));
        if (food < (ominous ? 16 : 10)) return "not enough food";
        if (Math.max(Inv.swordTier(bot), Inv.axeTier(bot)) < 4 && Inv.count(bot, Items.MACE) == 0
                && Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.SPEARS) && Inv.tier(s.getItem()) >= 4) == 0) return "no proper weapon";
        boolean shield = bot.getOffhandItem().is(Items.SHIELD) || Inv.count(bot, Items.SHIELD) > 0;
        boolean bow = (Inv.count(bot, Items.BOW) + Inv.count(bot, Items.CROSSBOW) > 0) && Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.ARROWS)) >= 16;
        if (!shield && !bow) return "no shield or bow";
        if (Inv.scaffoldCount(bot) < 16) return "no blocks";
        // Combat power (full iron, iron sword and a shield is about 4.5).
        double need = ominous ? 6.0 : 4.0;
        need -= p.brave() * 0.6 + p.reckless() * 0.8;     // the bold go in earlier
        need += (1 - p.reckless()) * (1 - p.brave()) * 0.6; // the careful prepare more
        need += Math.min(3, deaths) * 0.7;                  // it went badly last time
        double power = CombatGoal.power(bot);
        if (ominous) {
            // Diamond and good enchantments make the hard version tempting.
            if (Inv.fullDiamond(bot)) power += 1.0;
            if (Inv.count(bot, Items.TOTEM_OF_UNDYING) > 0 || bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) power += 0.8;
        }
        if (power < need) return "not geared up enough (" + String.format(java.util.Locale.ROOT, "%.1f/%.1f", power, need) + ")";
        return null;
    }

    // --- the run ------------------------------------------------------------------------

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        this.ticks++;
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            Task done = this.sub;
            this.sub = null;
            if (s == Status.FAILED && done instanceof TravelTask) return leave(bot, brain, "couldn't get there", 20 * 60 * 20);
        }
        if (this.target == null) {
            this.target = pick(bot, brain);
            if (this.target == null) return Status.FAILED;
        }
        BotMemory.Chamber notes = brain.memory().chamber(this.target);
        return switch (this.stage) {
            case PREPARE -> prepare(bot, brain, notes);
            case TRAVEL -> travel(bot, brain, notes);
            case WORK -> work(bot, brain, notes);
        };
    }

    private Status prepare(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        this.note = "Getting ready for the trial chamber";
        // Keys and an ominous bottle from the stash, if we've any put away.
        if (!this.fetchedKeys) {
            this.fetchedKeys = true;
            var keys = FetchTask.fromStash(bot, brain, s -> s.is(Items.TRIAL_KEY) || s.is(Items.OMINOUS_TRIAL_KEY) || s.is(Items.OMINOUS_BOTTLE),
                    16, "Getting my trial keys");
            if (keys != null) {
                this.sub = keys;
                return Status.RUNNING;
            }
        }
        String why = notReady(bot, brain, false, notes.deaths);
        if (why != null) {
            brain.log("trial chamber: not ready (" + why + "), another time");
            notes.restUntil = brain.now() + 20 * 60 * 10;
            return Status.FAILED;
        }
        brain.log("trial chamber: heading for the one at " + this.target.pos().toShortString());
        this.stage = Stage.TRAVEL;
        return Status.RUNNING;
    }

    private Status travel(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        if (Chambers.here(bot) != null) {
            this.stage = Stage.WORK;
            if ("discovered".equals(notes.status)) notes.status = "entered";
            notes.addEntrance(bot.blockPosition());
            brain.log("trial chamber: in");
            return Status.RUNNING;
        }
        this.note = "Heading for the trial chamber";
        this.sub = new TravelTask(this.target.pos(), 10, "Heading for the trial chamber");
        return Status.RUNNING;
    }

    /** Back off: hurt, hungry, or something's wrong. Progress is kept for next time. */
    private Status leave(BotPlayer bot, BotBrain brain, String why, long restTicks) {
        if (this.target != null) {
            BotMemory.Chamber notes = brain.memory().chamber(this.target);
            notes.restUntil = brain.now() + restTicks;
            notes.leftBecause = why;
        }
        brain.log("trial chamber: leaving (" + why + ")");
        return Status.FAILED;
    }

    private Status work(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        // Getting beaten: out, heal up, come back another time.
        float hp = bot.getHealth() / bot.getMaxHealth();
        if (hp < (this.ominousRound ? 0.45f : 0.35f) && !anySpawnerActive(bot)) return leave(bot, brain, "hurt", 20 * 60 * 5);
        if (Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s)) == 0 && bot.getFoodData().getFoodLevel() < 10) {
            return leave(bot, brain, "out of food", 20 * 60 * 10);
        }
        Structures.Known chamber = Chambers.here(bot);
        if (chamber == null) {
            // Wandered out (chased something, knocked off a ledge): back in.
            this.stage = Stage.TRAVEL;
            return Status.RUNNING;
        }
        // Mid-way through something: carry on with it.
        if (this.spawner != null) return runSpawner(bot, brain, notes);
        if (this.vault != null) return runVault(bot, brain, notes);

        if (--this.scanIn > 0) return Status.RUNNING;
        this.scanIn = 20;
        // Drops worth having (breeze rods, keys, a mob's armour): the everyday pickup can't
        // interrupt a run, so the run picks them up itself, between fights.
        var drops = net.joinedthegame.ai.Senses.itemsNear(bot, bot.blockPosition(), 10);
        drops.removeIf(e -> !e.isAlive() || e.getOwner() == bot || this.triedDrops.contains(e.getUUID())
                || !(net.joinedthegame.ai.Junk.isKeeper(e.getItem()) || net.joinedthegame.ai.Junk.isValuable(e.getItem())));
        if (!drops.isEmpty() && !anySpawnerActive(bot)) {
            for (var e : drops) this.triedDrops.add(e.getUUID());
            this.sub = new CollectItems(drops.getFirst().blockPosition(), 6, 100);
            return Status.RUNNING;
        }
        List<Chambers.Spawner> spawners = Chambers.spawners(bot.level(), chamber.box());
        List<Chambers.Vault> vaults = Chambers.vaults(bot, chamber.box());

        // 1. Trials to beat: the nearest one we haven't (ominous ones on the ominous round).
        java.util.Set<Long> done = this.ominousRound ? notes.ominousCleared : notes.cleared;
        Chambers.Spawner next = null;
        double best = Double.MAX_VALUE;
        for (Chambers.Spawner s : spawners) {
            long key = s.pos().asLong();
            if (done.contains(key) || this.skip.contains(key) || s.dead()) continue;
            // Beaten by someone else and cooling down: nothing to do there for now.
            if (s.beaten() && !this.ominousRound) {
                done.add(key);
                continue;
            }
            double d = s.pos().distSqr(bot.blockPosition());
            if (d < best) {
                best = d;
                next = s;
            }
        }
        if (next != null) {
            this.spawner = next.pos();
            this.focusTicks = 0;
            return Status.RUNNING;
        }
        // A spawner we had to give up on (couldn't reach the last of its wave, couldn't start it)
        // is NOT beaten: the chamber stays partly cleared, and we come back for it.
        boolean blocked = spawners.stream().anyMatch(sp -> this.skip.contains(sp.pos().asLong()) && !done.contains(sp.pos().asLong()));
        notes.status = blocked ? "partly cleared" : this.ominousRound ? "ominous cleared" : "trials cleared";

        // 2. Vaults we've a key for and haven't had from.
        for (Chambers.Vault v : vaults) {
            if (v.usedByUs() || this.skip.contains(v.pos().asLong())) continue;
            if (Inv.count(bot, v.key()) == 0) continue;
            this.vault = v.pos();
            this.focusTicks = 0;
            return Status.RUNNING;
        }

        // 3. The chamber's chests and barrels (supplies, corridors): the everyday looting can't
        //    interrupt a run either, so it's part of the run.
        BlockPos chest = nextChest(bot, brain, chamber);
        if (chest != null) {
            this.sub = new OpenChest(chest);
            return Status.RUNNING;
        }

        // 4. The ominous round, if we're up to it and have the means.
        boolean ominousVaultsLeft = vaults.stream().anyMatch(v -> v.ominous() && !v.usedByUs());
        if (!this.ominousRound && ominousVaultsLeft && wantsOminous(bot, brain, notes)) {
            return startOminous(bot, brain);
        }

        // 5. Bits we haven't seen yet (the chamber's bigger than what's loaded): look around.
        if (this.explores < 6 && spawners.size() < 2) {
            this.explores++;
            var box = chamber.box();
            BlockPos goal = new BlockPos(box.minX() + bot.getRandom().nextInt(Math.max(1, box.getXSpan())), bot.getBlockY(),
                    box.minZ() + bot.getRandom().nextInt(Math.max(1, box.getZSpan())));
            this.note = "Exploring the trial chamber";
            this.sub = new TravelTask(goal, 6, "Exploring the trial chamber");
            return Status.RUNNING;
        }

        // Nothing left we can do: done (for now).
        boolean vaultsLeft = vaults.stream().anyMatch(v -> !v.usedByUs() && (!v.ominous() || wantsOminous(bot, brain, notes)));
        notes.status = blocked ? "partly cleared" : vaultsLeft ? "vaults left" : "raided";
        brain.log("trial chamber: " + notes.status);
        if ("raided".equals(notes.status)) {
            brain.remember(BotMemory.EventType.LOOTED, "trial chambers", this.target, 0.9f);
        } else {
            notes.restUntil = brain.now() + 20 * 60 * 15; // come back when we've the keys
        }
        return Status.DONE;
    }

    private static boolean anySpawnerActive(BotPlayer bot) {
        Structures.Known chamber = Chambers.here(bot);
        if (chamber == null) return false;
        for (Chambers.Spawner s : Chambers.spawners(bot.level(), chamber.box())) {
            if (s.active() && s.pos().distSqr(bot.blockPosition()) < 20 * 20) return true;
        }
        return false;
    }

    // --- a trial ------------------------------------------------------------------------

    /**
     * One trial spawner: be where it can see us (that starts it), stay with it while its wave is
     * fought, then once the game says it's beaten, wait for the reward and pick it up.
     */
    private Status runSpawner(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        BlockPos pos = this.spawner;
        this.focusTicks++;
        Chambers.Spawner s = null;
        Structures.Known chamber = Chambers.here(bot);
        if (chamber != null) {
            for (Chambers.Spawner x : Chambers.spawners(bot.level(), chamber.box())) if (x.pos().equals(pos)) s = x;
        }
        if (s == null) {
            this.spawner = null;
            return Status.RUNNING;
        }
        java.util.Set<Long> done = this.ominousRound ? notes.ominousCleared : notes.cleared;
        boolean ominousNow = s.ominous();
        if (s.state() == net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.COOLDOWN
                && (!this.ominousRound || ominousNow)) {
            // Beaten and the reward's out: pick it up, and on to the next.
            brain.log("trial chamber: beat the " + (ominousNow ? "ominous " : "") + "trial at " + pos.toShortString());
            done.add(pos.asLong());
            if (!this.ominousRound) notes.status = "partly cleared";
            this.spawner = null;
            this.sub = new CollectItems(pos, 6, 120);
            return Status.RUNNING;
        }
        // Not starting (out of its sight, somewhere it can't see us): get closer; give up on it
        // after a while rather than stand there for ever.
        boolean waiting = s.state() == net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.WAITING_FOR_PLAYERS
                || this.ominousRound && !ominousNow;
        if (waiting && this.focusTicks > 20 * 60) {
            brain.log("trial chamber: the spawner at " + pos.toShortString() + " won't start for me, leaving it");
            this.skip.add(pos.asLong());
            this.spawner = null;
            return Status.RUNNING;
        }
        // Stay close, with it in sight: close enough that it sees us (14 blocks, line of sight)
        // and the reward lands near us, not so close we're standing on it.
        double close = waiting && this.focusTicks > 20 * 20 ? 3.5 : 7;
        boolean placed = bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= close
                && BlockBreaker.canSee(bot, bot.getEyePosition(), pos);
        Navigator nav = brain.navigator();
        if (!placed) {
            this.note = s.active() ? "Fighting the trial" : "Starting a trial";
            if (!nav.isMoving()) {
                double r = close - 0.5;
                nav.moveTo(bot, Vec3.atCenterOf(pos), p -> {
                    Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                    return !p.equals(pos) && !p.above().equals(pos) && eye.distanceTo(Vec3.atCenterOf(pos)) <= r
                            && BlockBreaker.canSee(bot, eye, pos);
                });
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    this.skip.add(pos.asLong());
                    this.spawner = null;
                    brain.log("trial chamber: can't get to the spawner at " + pos.toShortString());
                }
            }
            return Status.RUNNING;
        }
        // The wave's still going but none of it's here to fight (round a corner, off in another
        // room, stuck somewhere): the spawner knows which mobs are its own; go and find them.
        if (s.active()) {
            var wave = Chambers.waveMobs(bot.level(), pos);
            boolean inSight = wave.stream().anyMatch(m -> m.distanceToSqr(bot) < 8 * 8 && bot.hasLineOfSight(m));
            if (!wave.isEmpty() && !inSight) {
                var mob = wave.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).get();
                this.huntTicks++;
                if (this.huntTicks > 20 * 60) {
                    brain.log("trial chamber: can't get at the last of the wave at " + pos.toShortString() + ", leaving that one");
                    this.skip.add(pos.asLong());
                    this.spawner = null;
                    this.huntTicks = 0;
                    return Status.RUNNING;
                }
                this.note = "Hunting down the rest of the trial";
                if (!nav.isMoving() || this.ticks % 20 == 0) {
                    nav.moveNear(bot, mob.position(), 2.5);
                    if (nav.status() == Navigator.Status.FAILED) nav.stop(bot);
                }
                return Status.RUNNING;
            }
            this.huntTicks = 0;
        }
        nav.stop(bot);
        this.note = s.active() ? "Fighting the trial" : s.beaten() ? "Waiting for the trial's reward" : "Starting a trial";
        // Looking about (the fighting is the combat goal's job); the reward needs us here.
        if (this.ticks % 40 == 0) brain.controls().lookAt(bot, Vec3.atCenterOf(pos));
        return Status.RUNNING;
    }

    // --- a vault ------------------------------------------------------------------------

    /** Open a vault with its key (from where we can actually reach and see it), and take what comes out. */
    private Status runVault(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        BlockPos pos = this.vault;
        this.focusTicks++;
        if (!(bot.level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity be)) {
            this.vault = null;
            return Status.RUNNING;
        }
        boolean ominous = bot.level().getBlockState(pos).getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS);
        var state = bot.level().getBlockState(pos).getValue(net.minecraft.world.level.block.VaultBlock.STATE);
        if (Chambers.rewarded(be, bot.getUUID())) {
            // It's ours: wait for it to finish handing it out, then pick it all up.
            if (state == net.minecraft.world.level.block.entity.vault.VaultState.UNLOCKING
                    || state == net.minecraft.world.level.block.entity.vault.VaultState.EJECTING) {
                this.note = "Opening a vault";
                return Status.RUNNING;
            }
            brain.log("trial chamber: opened " + (ominous ? "an ominous" : "a") + " vault");
            this.vault = null;
            this.sub = new CollectItems(pos, 5, 100);
            return Status.RUNNING;
        }
        if (this.focusTicks > 20 * 45) {
            this.skip.add(pos.asLong());
            this.vault = null;
            return Status.RUNNING;
        }
        if (!BlockBreaker.canUse(bot, pos)) {
            this.note = "Going to a vault";
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, pos);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.skip.add(pos.asLong());
                    this.vault = null;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        if (this.ticks % 10 != 0) return Status.RUNNING;
        var key = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
        Direction face = Direction.getApproximateNearest(bot.getEyePosition().subtract(Vec3.atCenterOf(pos)));
        Placer.useOn(bot, pos, face, st -> st.is(key));
        return Status.RUNNING;
    }

    // --- ominous ------------------------------------------------------------------------

    /** Up for the hard version: well enough geared (much more than for a normal trial) and keen. */
    private static boolean wantsOminous(BotPlayer bot, BotBrain brain, BotMemory.Chamber notes) {
        boolean canTrigger = Inv.count(bot, Items.OMINOUS_BOTTLE) > 0 || bot.hasEffect(MobEffects.BAD_OMEN) || bot.hasEffect(MobEffects.TRIAL_OMEN);
        if (!canTrigger) return false;
        // Careful bots need the full kit; brave and reckless ones less so.
        return notReady(bot, brain, true, notes.deaths) == null;
    }

    /** Drink the ominous bottle here, at the chamber, and go round the spawners again. */
    private Status startOminous(BotPlayer bot, BotBrain brain) {
        if (!bot.hasEffect(MobEffects.BAD_OMEN) && !bot.hasEffect(MobEffects.TRIAL_OMEN)) {
            if (bot.isUsingItem()) return Status.RUNNING;
            if (!Inv.hold(bot, s -> s.is(Items.OMINOUS_BOTTLE))) return Status.RUNNING;
            this.note = "Drinking an ominous bottle";
            bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            return Status.RUNNING;
        }
        brain.log("trial chamber: going for the ominous trials");
        this.ominousRound = true;
        this.skip.clear();
        this.scanIn = 0;
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
        if (bot.isUsingItem() && bot.getUseItem().is(Items.OMINOUS_BOTTLE)) bot.stopUsingItem();
    }

    // --- chests ---------------------------------------------------------------------------

    /** The nearest chest or barrel in the chamber we haven't emptied. */
    private @Nullable BlockPos nextChest(BotPlayer bot, BotBrain brain, Structures.Known chamber) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        var level = bot.level();
        for (int cx = chamber.box().minX() >> 4; cx <= chamber.box().maxX() >> 4; cx++) {
            for (int cz = chamber.box().minZ() >> 4; cz <= chamber.box().maxZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (!chamber.box().isInside(p)) continue;
                    if (!(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                            || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) continue;
                    if (brain.recentlyLooted(p) || brain.isBlacklisted(p) || this.skip.contains(p.asLong())) continue;
                    if (be instanceof net.minecraft.world.Container c && c.isEmpty()) continue;
                    double d = p.distSqr(bot.blockPosition());
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** Walk to a chest (where we can really reach and see it), open it, take what's worth having. */
    private final class OpenChest implements Task {
        private final BlockPos pos;
        private int ticks;
        private int open;

        OpenChest(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public String activity() {
            return "Looting the trial chamber";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (++this.ticks > 20 * 40) {
                TrialChamberProject.this.skip.add(this.pos.asLong());
                return Status.FAILED;
            }
            if (!BlockBreaker.canUse(bot, this.pos)) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) {
                    nav.moveToUse(bot, this.pos);
                    if (nav.status() == Navigator.Status.FAILED) {
                        TrialChamberProject.this.skip.add(this.pos.asLong());
                        return Status.FAILED;
                    }
                }
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            brain.controls().lookAt(bot, Vec3.atCenterOf(this.pos));
            this.open++;
            if (this.open == 5 && net.joinedthegame.ai.skill.Containers.open(bot, this.pos) == null) {
                brain.markLooted(this.pos);
                return Status.DONE;
            }
            if (this.open == 20 && bot.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu) {
                net.joinedthegame.ai.skill.Containers.take(bot, menu.getContainer(),
                        st -> net.joinedthegame.ai.Junk.isKeeper(st) || net.joinedthegame.ai.Junk.isValuable(st));
            }
            if (this.open >= 30) {
                net.joinedthegame.ai.skill.Containers.close(bot);
                brain.markLooted(this.pos);
                return Status.DONE;
            }
            return Status.RUNNING;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            net.joinedthegame.ai.skill.Containers.close(bot);
        }
    }

    /** Stood by a trial spawner, waiting on it (its wave, its reward): that's on purpose. */
    public boolean waitingOnTrial() {
        return this.spawner != null || this.vault != null;
    }

    /** In (or right by) a chamber, on this project: sleep and digging in can wait. */
    public static boolean inChamber(BotPlayer bot) {
        return Chambers.here(bot) != null;
    }
}
