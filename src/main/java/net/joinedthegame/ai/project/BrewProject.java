package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Potions;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Brewer;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: brewing. What the blaze rods were for.
 * <ol>
 *   <li>A brewing stand (a blaze rod and three cobblestone) by home, and blaze powder to run it.</li>
 *   <li>Three glass bottles (sand, smelted to glass), filled at the nearest water.</li>
 *   <li>Nether wart (from a fortress) makes them awkward potions; then the ingredient makes the
 *       real thing: healing (glistering melon), strength (blaze powder), fire resistance (magma
 *       cream), regeneration (ghast tear), swiftness (sugar), night vision (golden carrot), water
 *       breathing (pufferfish). Whatever we're short of and can make.</li>
 *   <li>Then, with them to hand: glowstone for a stronger one, redstone for a longer one,
 *       gunpowder to make healing a splash potion (thrown, it's instant).</li>
 *   <li>Nether wart grows on soul sand: a couple brought home make a patch by the stand, so it's
 *       not a nether trip every time.</li>
 * </ol>
 * The potions are for {@link Potions}: drunk in fights, in fire, under water.
 */
public final class BrewProject implements Task {
    /** One batch is three bottles; a stock of three of each is plenty. */
    static final int WANT_EACH = 3;

    /** Something worth brewing, and what goes in it. */
    public record Brew(String name, Holder<MobEffect> effect, Item ingredient, boolean amplifies, boolean extends_) {}

    /** In order of how much they're wanted. */
    public static final List<Brew> MENU = List.of(
            new Brew("healing", MobEffects.INSTANT_HEALTH, Items.GLISTERING_MELON_SLICE, true, false),
            new Brew("strength", MobEffects.STRENGTH, Items.BLAZE_POWDER, true, true),
            new Brew("fire resistance", MobEffects.FIRE_RESISTANCE, Items.MAGMA_CREAM, false, true),
            new Brew("regeneration", MobEffects.REGENERATION, Items.GHAST_TEAR, true, true),
            new Brew("swiftness", MobEffects.SPEED, Items.SUGAR, true, true),
            new Brew("night vision", MobEffects.NIGHT_VISION, Items.GOLDEN_CARROT, false, true),
            new Brew("water breathing", MobEffects.WATER_BREATHING, Items.PUFFERFISH, false, true));

    private @Nullable Task sub;
    private int steps;
    private int ticks;
    private String note = "Getting into brewing";
    private boolean noCane;
    private boolean noBottles;
    private boolean patchTried;
    private boolean harvestTried;
    /** Brews whose ingredient couldn't be made this go. */
    private final java.util.Set<String> cantMake = new java.util.HashSet<>();
    private int batches;

    // The batch in the stand
    private @Nullable Brew brewing;
    private final List<Item> queue = new ArrayList<>();
    private int waited;

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : this.note;
    }

    // --- what's there ------------------------------------------------------------------------

    public static @Nullable BlockPos stand(BotPlayer bot, BotBrain brain) {
        return remembered(bot, brain, "brewing_stand", Blocks.BREWING_STAND);
    }

    /**
     * Our wart patch: wart growing, or just the soul sand bed with nothing in it (picked and not
     * replanted yet: a fight came along). Only gone if the soul sand's gone.
     */
    public static @Nullable BlockPos patch(BotPlayer bot, BotBrain brain) {
        GlobalPos known = brain.memory().nearest("wart_patch", homeOrHere(bot, brain), 64);
        if (known == null) return null;
        if (known.dimension() == bot.level().dimension() && bot.level().isLoaded(known.pos())
                && !bot.level().getBlockState(known.pos().below()).is(Blocks.SOUL_SAND)) {
            brain.memory().forget("wart_patch", known);
            return null;
        }
        return known.pos();
    }

    /** The bed's empty (picked, never replanted): wart back in it. */
    private static boolean patchEmpty(BotPlayer bot, @Nullable BlockPos patch) {
        return patch != null && bot.level().isLoaded(patch) && bot.level().getBlockState(patch).isAir();
    }

    private static @Nullable BlockPos remembered(BotPlayer bot, BotBrain brain, String kind, net.minecraft.world.level.block.Block block) {
        GlobalPos known = brain.memory().nearest(kind, homeOrHere(bot, brain), 64);
        if (known == null) return null;
        if (known.dimension() == bot.level().dimension() && bot.level().isLoaded(known.pos())
                && !bot.level().getBlockState(known.pos()).is(block)) {
            brain.memory().forget(kind, known);
            return null;
        }
        return known.pos();
    }

    /**
     * Where to look for the stand and the patch: round home, where they're set up. (Looked for
     * round the bot, a bot off mining 900 blocks away "had no stand" and gave the project up.)
     */
    private static GlobalPos homeOrHere(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        return home != null ? home : GlobalPos.of(bot.level().dimension(), bot.blockPosition());
    }

    private static boolean patchRipe(BotPlayer bot, @Nullable BlockPos patch) {
        if (patch == null || !bot.level().isLoaded(patch)) return false;
        var s = bot.level().getBlockState(patch);
        return s.is(Blocks.NETHER_WART) && s.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
    }

    /** In the bag, or put away in the chests at home (the project fetches it when it starts). */
    static int have(BotPlayer bot, BotBrain brain, Item item) {
        // (The remembered list: it works from anywhere, not just within sight of the chests.)
        // (Not the secret stash: opening chests here, forty times over for every look at the
        // menu, every half second near home, was far too dear. The stash is gone through once,
        // when the project starts and fetches its things.)
        return Inv.count(bot, item) + net.joinedthegame.ai.Storage.stored(brain, item);
    }

    /**
     * What to take out of the chests for a brewing session: the basics (wart, fuel, bottles, the
     * stand, soul sand for a patch) and what the brew we'd make needs, not every ingredient there
     * is (a bag of seventeen kinds of brewing things and no room for a sword).
     */
    public static boolean fetchFor(BotPlayer bot, BotBrain brain, net.minecraft.world.item.ItemStack s) {
        if (s.is(Items.NETHER_WART) || s.is(Items.BLAZE_ROD) || s.is(Items.BLAZE_POWDER) || s.is(Items.BREWING_STAND)
                || s.is(Items.GLASS_BOTTLE) || Brewer.isWater(s) || s.is(Items.SOUL_SAND)) return Inv.count(bot, s.getItem()) < 8;
        Brew b = pick(bot, brain, false);
        if (b == null) return false;
        if (s.is(b.ingredient())) return Inv.count(bot, s.getItem()) < 2;
        boolean makings = switch (b.name()) {
            case "healing" -> s.is(Items.MELON_SLICE) || s.is(Items.GOLD_NUGGET) || s.is(Items.GOLD_INGOT);
            case "fire resistance" -> s.is(Items.SLIME_BALL);
            case "swiftness" -> s.is(Items.SUGAR_CANE);
            case "night vision" -> s.is(Items.CARROT) || s.is(Items.GOLD_NUGGET) || s.is(Items.GOLD_INGOT);
            default -> false;
        };
        boolean better = b.amplifies() && s.is(Items.GLOWSTONE_DUST) || b.extends_() && s.is(Items.REDSTONE)
                || b.effect() == MobEffects.INSTANT_HEALTH && s.is(Items.GUNPOWDER);
        return (makings || better) && Inv.count(bot, s.getItem()) < 4;
    }

    /** Could we get hold of one of these right now, from what we've got (or a bit of cane)? */
    static boolean obtainable(BotPlayer bot, BotBrain brain, Brew b, boolean noCane) {
        if (have(bot, brain, b.ingredient()) > 0 && !(b.ingredient() == Items.BLAZE_POWDER && have(bot, brain, Items.BLAZE_POWDER) < 2)) return true;
        int gold = have(bot, brain, Items.GOLD_NUGGET) + have(bot, brain, Items.GOLD_INGOT) * 9;
        int powderish = have(bot, brain, Items.BLAZE_POWDER) + have(bot, brain, Items.BLAZE_ROD) * 2;
        return switch (b.name()) {
            case "healing" -> have(bot, brain, Items.MELON_SLICE) > 0 && gold >= 8;
            case "strength" -> powderish >= 3; // one for fuel, one to brew with, to spare
            case "fire resistance" -> have(bot, brain, Items.SLIME_BALL) > 0 && powderish >= 2;
            case "swiftness" -> have(bot, brain, Items.SUGAR_CANE) > 0 || !noCane;
            case "night vision" -> have(bot, brain, Items.CARROT) > 0 && gold >= 8;
            default -> false;
        };
    }

    /** The next thing to brew: one we're short of and can make. */
    static @Nullable Brew pick(BotPlayer bot, BotBrain brain, boolean noCane) {
        for (Brew b : MENU) {
            if (Potions.count(bot, b.effect()) >= WANT_EACH) continue;
            if (obtainable(bot, brain, b, noCane)) return b;
        }
        return null;
    }

    /** Worth starting: a home, nether wart (or a ripe patch), a stand (or the makings), and something to brew. */
    public static boolean worthIt(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        net.joinedthegame.ai.Storage.refreshIfHome(bot, brain); // (at home: what's really in the chests now)
        boolean wart = have(bot, brain, Items.NETHER_WART) > 0 || patchRipe(bot, patch(bot, brain));
        if (!wart) return false;
        boolean stand = stand(bot, brain) != null || have(bot, brain, Items.BREWING_STAND) > 0 || have(bot, brain, Items.BLAZE_ROD) > 0;
        if (!stand) return false;
        boolean fuel = have(bot, brain, Items.BLAZE_POWDER) + have(bot, brain, Items.BLAZE_ROD) > 0 || stand(bot, brain) != null;
        return fuel && pick(bot, brain, false) != null;
    }

    /**
     * Set up for brewing but out of wart, with nothing growing: one reason to go to the nether.
     * A patch with wart in it (ripe or not) will do, given time; an empty bed (picked, and nothing
     * left to replant it with) won't, so that counts as no patch.
     */
    public static boolean wantsWart(BotPlayer bot, BotBrain brain) {
        if (have(bot, brain, Items.NETHER_WART) > 0) return false;
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        if (brain.memory().nearest("brewing_stand", here, 100000) == null) return false;
        GlobalPos known = brain.memory().nearest("wart_patch", here, 100000);
        if (known == null) return true;
        // Too far off to look at: trust it's growing. In sight and bare: it's no use without wart.
        return known.dimension() == bot.level().dimension() && bot.level().isLoaded(known.pos())
                && !bot.level().getBlockState(known.pos()).is(Blocks.NETHER_WART);
    }

    // --- the project ---------------------------------------------------------------------

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            Task done = this.sub;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED) {
                if (done instanceof MineTask m && m.activity().contains("sugar cane")) this.noCane = true;
                else if (this.note.contains("bottles")) this.noBottles = true;
                else if (stand(bot, brain) == null) return Status.FAILED;
            }
        }
        this.ticks++;
        if (++this.steps > 120) return Status.DONE;

        BlockPos stand = stand(bot, brain);
        if (stand == null) return makeStand(bot, brain);
        BrewingStandBlockEntity be = Brewer.at(bot, stand);

        // Mid-batch: see it through.
        if (this.brewing != null) return brew(bot, brain, stand, be);

        // Things left in the stand from before: a batch that got interrupted (finished potions
        // sitting there), or one that got stuck (an ingredient on top that isn't brewing in: out of
        // fuel, or it doesn't go). Take it all back out first, whether or not we can brew now:
        // otherwise it sits in there for good.
        if (be != null && Brewer.state(be) != Brewer.State.BREWING && (Brewer.bottles(be) > 0 || Brewer.state(be) == Brewer.State.STUCK)) {
            this.note = "Clearing out my brewing stand";
            if (!atStand(bot, brain, stand)) return Status.RUNNING;
            boolean stuck = Brewer.state(be) == Brewer.State.STUCK;
            int n = Brewer.takeAll(bot, be);
            brain.log("brewing: took " + n + " things back out of the stand" + (stuck ? " (it was stuck, not brewing)" : ""));
            return Status.RUNNING;
        }

        // A ripe wart patch: harvest and replant before anything else.
        BlockPos patch = patch(bot, brain);
        // Once a go: if it didn't work, brew anyway rather than try again and again.
        if (patchRipe(bot, patch) && !this.harvestTried) {
            this.harvestTried = true;
            return harvestPatch(bot, brain, patch);
        }
        if (patchEmpty(bot, patch) && Inv.count(bot, Items.NETHER_WART) > 0) {
            this.note = "Replanting nether wart";
            this.sub = new BuildTask(List.of(BuildTask.Step.placeEssential(patch, s -> s.is(Items.NETHER_WART), null)), "Replanting nether wart");
            return Status.RUNNING;
        }
        // Soul sand and spare wart brought home: start a patch by the stand.
        if (patch == null && !this.patchTried && Inv.count(bot, Items.SOUL_SAND) > 0 && Inv.count(bot, Items.NETHER_WART) >= 2) {
            this.patchTried = true;
            Task t = plantPatch(bot, brain, stand);
            if (t != null) {
                this.sub = t;
                return Status.RUNNING;
            }
        }

        if (this.batches >= 2) return Status.DONE; // that'll do for one go
        if (Inv.count(bot, Items.NETHER_WART) == 0) {
            brain.log("brewing: out of nether wart");
            return Status.DONE;
        }
        net.joinedthegame.ai.Storage.refreshIfHome(bot, brain);
        Brew next = null;
        for (Brew b : MENU) {
            if (this.cantMake.contains(b.name()) || Potions.count(bot, b.effect()) >= WANT_EACH) continue;
            if (obtainable(bot, brain, b, this.noCane)) {
                next = b;
                break;
            }
        }
        if (next == null) return Status.DONE;

        // Fuel: blaze powder, made from a rod.
        if (Inv.count(bot, Items.BLAZE_POWDER) < (next.ingredient() == Items.BLAZE_POWDER ? 2 : 1)
                && (be == null || Brewer.fuelInside(be) == 0 && be.getItem(Brewer.FUEL).isEmpty() || next.ingredient() == Items.BLAZE_POWDER)) {
            if (Inv.count(bot, Items.BLAZE_ROD) == 0) {
                brain.log("brewing: no blaze powder left to run the stand");
                return Status.DONE;
            }
            this.note = "Making blaze powder";
            return toward(bot, "blaze powder", Items.BLAZE_POWDER, Inv.count(bot, Items.BLAZE_POWDER) + 2);
        }
        // The ingredient.
        if (Inv.count(bot, next.ingredient()) == 0) {
            if (next.ingredient() == Items.SUGAR && Inv.count(bot, Items.SUGAR_CANE) == 0) {
                this.note = "Getting sugar cane for sugar";
                this.sub = new MineTask(bot, Sources.SUGAR_CANE, 3, "Cutting sugar cane");
                return Status.RUNNING;
            }
            this.note = "Making " + net.joinedthegame.ai.Planner.name(next.ingredient());
            boolean[] failed = {false};
            Item ing = next.ingredient();
            Task t = Tasks.toward(bot, Tasks.Need.of(net.joinedthegame.ai.Planner.name(ing), s -> s.is(ing), 1, b -> ing), failed);
            if (failed[0] || t == null) {
                // Can't make that one (the planner found no way): say so, and brew something else.
                brain.log("brewing: can't make " + net.joinedthegame.ai.Planner.name(ing) + " for " + next.name() + ", brewing something else");
                this.cantMake.add(next.name());
                return Status.RUNNING;
            }
            this.sub = t;
            return Status.RUNNING;
        }
        // Three bottles of water.
        int water = Inv.count(bot, Brewer::isWater);
        if (water < 3) {
            if (Inv.count(bot, Items.GLASS_BOTTLE) < 3 - water) {
                if (this.noBottles) {
                    brain.log("brewing: can't get glass bottles together");
                    return Status.DONE;
                }
                this.note = "Making glass bottles";
                return toward(bot, "glass bottles", Items.GLASS_BOTTLE, 3 - water);
            }
            return fillBottles(bot, brain, stand);
        }
        // All set: into the stand.
        return start(bot, brain, stand, be, next);
    }

    private Status toward(BotPlayer bot, String label, Item item, int count) {
        boolean[] failed = {false};
        Task t = Tasks.toward(bot, Tasks.Need.of(label, s -> s.is(item), count, b -> item), failed);
        if (failed[0] || t == null) {
            if (item == Items.GLASS_BOTTLE) {
                this.noBottles = true;
                return Status.RUNNING;
            }
            return Status.DONE;
        }
        this.sub = t;
        return Status.RUNNING;
    }

    // --- the stand -------------------------------------------------------------------------

    private Status makeStand(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, Items.BREWING_STAND) > 0) return placeStand(bot, brain);
        if (Inv.count(bot, Items.BLAZE_ROD) == 0) {
            brain.log("brewing: need a blaze rod for a brewing stand");
            return Status.FAILED;
        }
        this.note = "Making a brewing stand";
        return toward(bot, "a brewing stand", Items.BREWING_STAND, 1);
    }

    private Status placeStand(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        if (home.pos().distSqr(bot.blockPosition()) > 24 * 24) {
            this.sub = new TravelTask(home.pos(), 6, "Heading home with the brewing stand");
            return Status.RUNNING;
        }
        BlockPos spot = spotNear((ServerLevel) bot.level(), home.pos(), null);
        if (spot == null) {
            brain.log("brewing: no room by home for a brewing stand");
            return Status.FAILED;
        }
        brain.log("brewing: stand going at " + spot.toShortString());
        this.note = "Setting up the brewing stand";
        this.sub = Tasks.sequence(new BuildTask(List.of(BuildTask.Step.placeEssential(spot, s -> s.is(Items.BREWING_STAND), null)),
                "Setting up the brewing stand"), new Remember(spot, "brewing_stand", Blocks.BREWING_STAND, "a brewing stand"));
        return Status.RUNNING;
    }

    /**
     * A spot by home: open, two high, floor under it, not on the bed, not in a doorway or a
     * one-wide passage (it'd block it), against a wall if there's one. Indoors first.
     */
    static @Nullable BlockPos spotNear(ServerLevel level, BlockPos home, @Nullable BlockPos besides) {
        WorldView w = new WorldView(level);
        BlockPos center = besides != null ? besides : home;
        int r = besides != null ? 3 : 6;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, 2, r))) {
            if (c.closerThan(home, 1.9) || besides != null && c.closerThan(besides, 0.9)) continue;
            if (!w.state(c).isAir() || !w.state(c.above()).isAir() || !w.solidFloor(c.below())) continue;
            if (WorldView.isDangerous(w.state(c.below()))) continue;
            int open = 0, solid = 0;
            boolean door = false;
            boolean[] openDir = new boolean[4];
            int i = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                var n = w.state(c.relative(d));
                if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) door = true;
                if (n.isAir()) {
                    open++;
                    openDir[i] = true;
                } else if (!n.canBeReplaced()) solid++;
                i++;
            }
            if (door || open == 0) continue;
            // A passage: open only on two opposite sides. Never plug one.
            if (open == 2 && (openDir[0] && openDir[2] || openDir[1] && openDir[3])) continue;
            double score = Math.sqrt(c.distSqr(center)) + (level.canSeeSky(c) ? 3 : 0) - (solid > 0 ? 1 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = c.immutable();
            }
        }
        return best;
    }

    /** Marks a thing placed (in memory and the diary). */
    private record Remember(BlockPos spot, String kind, net.minecraft.world.level.block.Block block, String what) implements Task {
        @Override
        public String activity() {
            return "Setting up " + this.what;
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (!bot.level().getBlockState(this.spot).is(this.block)) return Status.FAILED;
            brain.rememberPlace(bot, this.kind, this.spot);
            brain.remember(BotMemory.EventType.BUILT, this.what, GlobalPos.of(bot.level().dimension(), this.spot), 0.7f);
            return Status.DONE;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {}
    }

    // --- water -------------------------------------------------------------------------------

    private @Nullable BlockPos water;

    /** To the nearest open water (by the stand, preferably), and fill the bottles there. */
    private Status fillBottles(BotPlayer bot, BotBrain brain, BlockPos stand) {
        this.note = "Filling bottles with water";
        if (this.water == null || !bot.level().getFluidState(this.water).is(Fluids.WATER)) {
            this.water = Senses.findBlock(bot, s -> s.getFluidState().is(Fluids.WATER) && s.is(Blocks.WATER), 40, 12, true,
                    p -> brain.isBlacklisted(p) || !bot.level().getBlockState(p.above()).isAir());
            if (this.water == null) {
                if (stand.distSqr(bot.blockPosition()) > 30 * 30) {
                    this.sub = new TravelTask(stand, 4, "Heading back to my brewing stand");
                    return Status.RUNNING;
                }
                brain.log("brewing: no water anywhere near to fill bottles from");
                return Status.DONE;
            }
        }
        if (!BlockBreaker.inReach(bot, this.water)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToReach(bot, this.water);
                if (nav.status() == Navigator.Status.FAILED) {
                    brain.blacklist(this.water);
                    this.water = null;
                }
            }
            this.steps--;
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        if (this.ticks % 6 != 0) {
            this.steps--;
            return Status.RUNNING;
        }
        if (!Brewer.fillBottle(bot, this.water)) {
            brain.blacklist(this.water);
            this.water = null;
        }
        return Status.RUNNING;
    }

    // --- brewing ------------------------------------------------------------------------------

    /** Load the stand and start: wart first, then the ingredient, then whatever improves it. */
    private Status start(BotPlayer bot, BotBrain brain, BlockPos stand, @Nullable BrewingStandBlockEntity be, Brew what) {
        if (!atStand(bot, brain, stand)) return Status.RUNNING;
        if (be == null) return Status.FAILED;
        // Anything left in it from before (interrupted last time): let a brew that's really under
        // way finish, then everything out first (a stuck ingredient too).
        if (Brewer.state(be) == Brewer.State.BREWING) return Status.RUNNING;
        if (Brewer.bottles(be) > 0 || Brewer.state(be) == Brewer.State.STUCK) Brewer.takeAll(bot, be);
        if (!Brewer.fuel(bot, be)) {
            brain.log("brewing: no blaze powder for the stand");
            return Status.DONE;
        }
        if (Brewer.loadWater(bot, be) == 0) return Status.RUNNING;
        this.queue.clear();
        this.queue.add(Items.NETHER_WART);
        this.queue.add(what.ingredient());
        if (what.amplifies() && Inv.count(bot, Items.GLOWSTONE_DUST) > 0) this.queue.add(Items.GLOWSTONE_DUST);
        else if (what.extends_() && Inv.count(bot, Items.REDSTONE) > 0) this.queue.add(Items.REDSTONE);
        if (what.effect() == MobEffects.INSTANT_HEALTH && Inv.count(bot, Items.GUNPOWDER) > 0) this.queue.add(Items.GUNPOWDER);
        this.brewing = what;
        this.waited = 0;
        brain.log("brewing: " + what.name() + " (" + this.queue.size() + " steps)");
        return Status.RUNNING;
    }

    private Status brew(BotPlayer bot, BotBrain brain, BlockPos stand, @Nullable BrewingStandBlockEntity be) {
        Brew what = this.brewing;
        this.note = "Brewing " + what.name() + " potions";
        if (be == null) {
            this.brewing = null;
            return Status.FAILED;
        }
        if (!atStand(bot, brain, stand)) return Status.RUNNING;
        this.steps--; // waiting by the stand isn't a step
        switch (Brewer.state(be)) {
            case BREWING -> {
                // Twenty seconds a step. (A safety net only: the clock's running.)
                if (++this.waited > 20 * 45) return abandon(bot, brain, be, "the brew's taking for ever");
                return Status.RUNNING;
            }
            case STUCK -> {
                // Something on top that isn't brewing in. Out of fuel: top it up. Still stuck after
                // that, or it just doesn't go with these bottles: everything out, try again later.
                if (++this.waited == 20) Brewer.fuel(bot, be);
                if (this.waited > 20 * 5) {
                    boolean noFuel = Brewer.fuelInside(be) == 0 && be.getItem(Brewer.FUEL).isEmpty();
                    return abandon(bot, brain, be, noFuel ? "out of blaze powder" : "that doesn't brew with these");
                }
                return Status.RUNNING;
            }
            case IDLE -> {}
        }
        this.waited = 0;
        // Next step (skipping any that wouldn't do anything, or we've run out of).
        while (!this.queue.isEmpty()) {
            Item next = this.queue.removeFirst();
            if (Inv.count(bot, next) == 0 || !Brewer.works(bot, be, next)) continue;
            Brewer.fuel(bot, be);
            Brewer.addIngredient(bot, be, next);
            return Status.RUNNING;
        }
        // Done: out they come.
        String made = Potions.name(Brewer.first(be));
        int n = Brewer.bottles(be);
        Brewer.takeAll(bot, be);
        this.brewing = null;
        this.batches++;
        brain.log("brewed " + n + " x " + made);
        brain.remember(BotMemory.EventType.MADE, "potions of " + what.name(), null, 0.5f);
        return Status.RUNNING;
    }

    private Status abandon(BotPlayer bot, BotBrain brain, BrewingStandBlockEntity be, String why) {
        brain.log("brewing: " + why + ", giving up on this batch");
        Brewer.takeAll(bot, be);
        this.brewing = null;
        this.queue.clear();
        return Status.DONE;
    }

    private boolean atStand(BotPlayer bot, BotBrain brain, BlockPos stand) {
        if (BlockBreaker.canUse(bot, stand)) {
            brain.navigator().stop(bot);
            brain.controls().lookAt(bot, Vec3.atCenterOf(stand));
            return true;
        }
        Navigator nav = brain.navigator();
        if (!nav.isMoving()) {
            if (stand.distSqr(bot.blockPosition()) > 40 * 40) {
                this.sub = new TravelTask(stand, 3, "Heading to my brewing stand");
                return false;
            }
            nav.moveToUse(bot, stand);
            if (nav.status() == Navigator.Status.FAILED) {
                brain.log("brewing: can't get to my brewing stand");
                brain.memory().forget("brewing_stand", GlobalPos.of(bot.level().dimension(), stand));
            }
        }
        this.steps--;
        return false;
    }

    // --- the wart patch ---------------------------------------------------------------------

    private @Nullable Task plantPatch(BotPlayer bot, BotBrain brain, BlockPos stand) {
        GlobalPos home = brain.record().home();
        if (home == null) return null;
        BlockPos spot = spotNear((ServerLevel) bot.level(), home.pos(), stand);
        if (spot == null) return null;
        // The soul sand goes in the floor, the wart on top of it.
        BlockPos sand = spot.below();
        var floor = bot.level().getBlockState(sand);
        if (floor.hasBlockEntity() || floor.is(net.minecraft.tags.BlockTags.BEDS) || !new WorldView(bot.level()).breakable(sand)) return null;
        brain.log("brewing: planting nether wart at " + spot.toShortString());
        this.note = "Planting nether wart";
        return Tasks.sequence(new BuildTask(List.of(BuildTask.Step.digEssential(sand),
                        BuildTask.Step.placeEssential(sand, s -> s.is(Items.SOUL_SAND), null),
                        BuildTask.Step.placeEssential(spot, s -> s.is(Items.NETHER_WART), null)), "Planting nether wart"),
                new Remember(spot, "wart_patch", Blocks.NETHER_WART, "a nether wart patch"));
    }

    private Status harvestPatch(BotPlayer bot, BotBrain brain, BlockPos patch) {
        this.note = "Picking nether wart";
        brain.log("brewing: picking the nether wart");
        // (clearOut: wart has nothing to bump into, so a plain dig counted it "dug" already and
        // never broke it: "picking the nether wart" over and over.)
        this.sub = Tasks.sequence(new BuildTask(List.of(BuildTask.Step.clearOut(patch)), "Picking nether wart"),
                new CollectItems(patch, 4, 60),
                new BuildTask(List.of(BuildTask.Step.placeEssential(patch, s -> s.is(Items.NETHER_WART), null)), "Replanting nether wart"),
                new Remember(patch, "wart_patch", Blocks.NETHER_WART, "a nether wart patch"));
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
    }
}
