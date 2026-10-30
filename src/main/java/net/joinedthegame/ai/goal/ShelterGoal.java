package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.minecraft.core.BlockPos;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.task.HideTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * Night falls, no bed to get to, and the bot isn't geared for it? Do what every new player
 * does: dig a hole, cover it up and wait for morning. Bots in iron with a shield are tough
 * enough to keep working through the night.
 */
public final class ShelterGoal extends Goal {
    private HideTask hide = new HideTask();

    public ShelterGoal(BotBrain brain) {
        super(brain);
    }

    /** Full iron (or better) and a shield: can handle a night out. */
    public static boolean nightReady(BotPlayer bot) {
        int armor = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (Inv.armorTier(bot, slot) >= 4) armor++;
        }
        return armor >= 4 && bot.getOffhandItem().is(Items.SHIELD) && bot.getHealth() >= 14;
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.inTrialChamber(bot)) return 0; // underground on a chamber run: no need to dig in
        if (!bot.level().isDarkOutside()) return 0;
        if (nightReady(bot)) return 0;
        // Out in open water, at the top or under it: under the surface the sky's "hidden", but
        // that's no shelter (drowned come up from below, and there's no digging in here). Make for
        // land, then dig in. (Running out of air is BreatheGoal's, above everything.)
        if (afloat(bot) || this.waterPlan != null && !onDryLand(bot)) return 68;
        // From here on: dry, or in a flooded cave (where only the air matters).
        // Underground already (caves are dark anyway); only the surface is a death trap at night.
        if (!bot.level().canSeeSky(bot.blockPosition()) && !HideTask.isHidden(bot)) return 0;
        // The reckless ones push their luck.
        // (Reckless isn't suicidal: some protection first, a shield or a bit of armour.)
        if (this.brain.personality().reckless() > 0.7 && bot.getHealth() > 16 && someProtection(bot)) return 0;
        // Safely dug in with a pickaxe: night is mining time, like any player. Drop below
        // gearing up so it carries on digging down from the hole (it won't go back outside
        // while it's dark); if there's nothing to mine, sit tight.
        if ((HideTask.isHidden(bot) || downAHole(bot)) && Inv.pickaxeTier(bot) >= 1) return 25;
        return 68; // well below going to bed (75), so a bot in a hole still gets up for a bed
    }

    /** In water that's open to the sky (a lake, the sea): not in a flooded cave. */
    public static boolean afloat(BotPlayer bot) {
        BlockPos feet = net.joinedthegame.ai.nav.Navigator.feet(bot);
        var level = bot.level();
        if (!bot.isInWater() && level.getFluidState(feet).isEmpty()) return false;
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, feet.getX(), feet.getZ());
        return top - feet.getY() <= 6 && !level.getFluidState(new BlockPos(feet.getX(), top - 1, feet.getZ())).isEmpty();
    }

    /**
     * The nearest dry ground a swimmer can climb out onto: solid, not under water, and not a cliff
     * (within a few blocks of the water's height). Null if there's none in sight.
     */
    private static @org.jspecify.annotations.Nullable BlockPos shore(BotPlayer bot, java.util.Set<BlockPos> tried) {
        BlockPos feet = net.joinedthegame.ai.nav.Navigator.feet(bot);
        var level = bot.level();
        var w = new net.joinedthegame.ai.nav.WorldView(level);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -64; dx <= 64; dx += 2) {
            for (int dz = -64; dz <= 64; dz += 2) {
                double d = dx * dx + dz * dz;
                if (d >= bestD || d > 64 * 64) continue;
                int x = feet.getX() + dx, z = feet.getZ() + dz;
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos stand = new BlockPos(x, y, z);
                if (y < feet.getY() - 2 || y > feet.getY() + 5) continue;
                if (!level.getFluidState(stand.below()).isEmpty() || !w.solidFloor(stand.below())) continue;
                if (tried.contains(stand)) continue;
                best = stand;
                bestD = d;
            }
        }
        return best;
    }

    private static boolean someProtection(BotPlayer bot) {
        if (bot.getOffhandItem().is(Items.SHIELD)) return true;
        int pieces = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!bot.getItemBySlot(slot).isEmpty()) pieces++;
        }
        return pieces >= 2;
    }

    /**
     * Safe enough to mine from, even if the hole isn't perfectly sealed (a lid that wouldn't go
     * on, a gap on one side): a couple of blocks below the surface with walls most of the way
     * round. Night-time mining only goes down and sideways from here, never up into the open.
     */
    private static boolean downAHole(BotPlayer bot) {
        BlockPos feet = net.joinedthegame.ai.nav.Navigator.feet(bot);
        int surface = bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        if (feet.getY() > surface - 2) return false;
        var w = new net.joinedthegame.ai.nav.WorldView(bot.level());
        int walls = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (w.cover(feet.relative(d))) walls++;
        return walls >= 3;
    }

    /**
     * Caught in open water after dark, what it's going for. Whichever it is, Shelter keeps hold of
     * the bot all night: a plan it sticks to, not a dig that fails in the water, a wander, and back.
     * <ul>
     *   <li>SHORE: dry ground in sight it can climb out onto. Swim there, then dig in.</li>
     *   <li>TOWARD_LAND: none in sight, but it knows where it last stood on dry ground. Head that
     *       way a stretch at a time, looking out for any land closer.</li>
     *   <li>TREAD: no idea where land is. Stay at the surface, head out of the water, keep a look
     *       out (drowned are a fight, and the fight goal takes those), and keep looking for land.</li>
     * </ul>
     * Under the surface it swims up as it goes; if the air runs low, coming up for air
     * (BreatheGoal) takes over outright. A dry cave is none of this: see {@link #priority}.
     */
    private enum WaterPlan { SHORE, TOWARD_LAND, TREAD }

    private @org.jspecify.annotations.Nullable WaterPlan waterPlan;
    private @org.jspecify.annotations.Nullable BlockPos shore;
    private final java.util.Set<BlockPos> badShores = new java.util.HashSet<>();
    private int waterTicks;
    private int climbTicks;
    /**
     * Standing on solid ground with no water at its feet or under them. Bobbing at the surface
     * isn't this, even on the ticks it's briefly "out of the water": only climbing out ends a swim.
     */
    public static boolean onDryLand(BotPlayer bot) {
        if (!bot.onGround() || bot.isInWater()) return false;
        BlockPos feet = net.joinedthegame.ai.nav.Navigator.feet(bot);
        var level = bot.level();
        return level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty()
                && new net.joinedthegame.ai.nav.WorldView(level).solidFloor(feet.below());
    }

    @Override
    public void start(BotPlayer bot) {
        this.hide = new HideTask();
        this.waterPlan = null;
        this.shore = null;
        this.badShores.clear();
        this.waterTicks = 0;
        this.climbTicks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (!bot.level().isDarkOutside()) {
            this.waterPlan = null;
            return false; // morning: back to work
        }
        if (afloat(bot) || this.waterPlan != null && !onDryLand(bot)) return inWater(bot);
        if (this.waterPlan != null) {
            // Climbed out: dig in from here (any hole started in the water is no use).
            this.waterPlan = null;
            this.brain.navigator().stop(bot);
            this.hide = new HideTask();
            this.brain.log("made it to land, digging in here");
        }
        Task.Status status = this.hide.tick(bot, this.brain);
        if (status == Task.Status.FAILED) {
            cooldown(this.brain.now(), 20 * 30);
            return false;
        }
        return true;
    }

    private boolean inWater(BotPlayer bot) {
        var nav = this.brain.navigator();
        ++this.waterTicks;
        if (this.waterPlan == null) {
            this.shore = shore(bot, this.badShores);
            this.waterPlan = this.shore != null ? WaterPlan.SHORE : withoutShore(bot);
            this.brain.log("in the water after dark: " + describe());
        }
        // Not heading for land yet? Every few seconds, look again (it's drifted, or more's loaded).
        if (this.waterPlan != WaterPlan.SHORE && this.waterTicks % 100 == 0) {
            BlockPos s = shore(bot, this.badShores);
            if (s != null) {
                nav.stop(bot);
                this.shore = s;
                this.waterPlan = WaterPlan.SHORE;
                this.brain.log("land in sight: " + describe());
            }
        }
        nav.sprint = this.waterPlan != WaterPlan.TREAD;
        switch (this.waterPlan) {
            case SHORE -> {
                BlockPos bank = this.shore;
                if (bank != null && !nav.isMoving() && flatDistSqr(bot, bank) < 3 * 3) {
                    // Right by the bank but still in the water ("near enough" for the navigator):
                    // climb out like a player, facing it, pushing forward with jump held.
                    nav.stop(bot);
                    var c = this.brain.controls();
                    c.lookAt(bot, net.minecraft.world.phys.Vec3.atCenterOf(bank));
                    c.forward = 1f;
                    c.jump = true;
                    if (++this.climbTicks > 20 * 5) {
                        // This bit of bank won't have it (too high, something in the way): another.
                        this.climbTicks = 0;
                        this.badShores.add(bank);
                        this.shore = this.badShores.size() > 6 ? null : shore(bot, this.badShores);
                        if (this.shore == null) {
                            this.waterPlan = withoutShore(bot);
                            this.brain.log("can't climb out here: " + describe());
                        }
                    }
                    return true;
                }
                if (!nav.isMoving() && this.shore != null) {
                    nav.moveNear(bot, net.minecraft.world.phys.Vec3.atBottomCenterOf(this.shore), 1.5);
                    if (nav.status() == net.joinedthegame.ai.nav.Navigator.Status.FAILED) {
                        // Can't get out there (a cliff, a gap it can't swim through): the next one.
                        nav.stop(bot);
                        this.badShores.add(this.shore);
                        this.shore = this.badShores.size() > 6 ? null : shore(bot, this.badShores);
                        if (this.shore == null) {
                            this.waterPlan = withoutShore(bot);
                            this.brain.log("can't reach that shore: " + describe());
                        }
                    }
                }
            }
            case TOWARD_LAND -> {
                if (!nav.isMoving()) {
                    BlockPos wp = this.brain.lastDryLand() == null ? null : waypoint(bot, this.brain.lastDryLand());
                    if (wp != null) nav.moveNear(bot, net.minecraft.world.phys.Vec3.atBottomCenterOf(wp), 3);
                    if (wp == null || nav.status() == net.joinedthegame.ai.nav.Navigator.Status.FAILED) {
                        nav.stop(bot);
                        this.waterPlan = WaterPlan.TREAD;
                        this.brain.log("can't make headway: " + describe());
                    }
                }
            }
            case TREAD -> {
                nav.stop(bot);
                if (this.waterTicks % 60 == 0) {
                    float yaw = bot.getYRot() + (bot.getRandom().nextFloat() - 0.5f) * 140f;
                    this.brain.controls().face(yaw, 0f);
                }
            }
        }
        // Head under: swim up as we go. Treading water: keep bobbing at the top.
        if (bot.isUnderWater() || this.waterPlan == WaterPlan.TREAD) this.brain.controls().jump = true;
        return true;
    }

    private static double flatDistSqr(BotPlayer bot, BlockPos p) {
        double dx = p.getX() + 0.5 - bot.getX(), dz = p.getZ() + 0.5 - bot.getZ();
        return dx * dx + dz * dz;
    }

    /** No dry ground in sight: back the way it came if it knows, otherwise stay afloat. */
    private WaterPlan withoutShore(BotPlayer bot) {
        return this.brain.lastDryLand() != null && this.brain.lastDryLand().distSqr(bot.blockPosition()) < 400 * 400 ? WaterPlan.TOWARD_LAND : WaterPlan.TREAD;
    }

    /** A point on the water some way towards {@code land} (a long swim goes a stretch at a time). */
    private static @org.jspecify.annotations.Nullable BlockPos waypoint(BotPlayer bot, BlockPos land) {
        double dx = land.getX() - bot.getX(), dz = land.getZ() - bot.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 2) return null;
        double step = Math.min(len, 40);
        int x = (int) Math.floor(bot.getX() + dx / len * step), z = (int) Math.floor(bot.getZ() + dz / len * step);
        if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
        int y = bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        return new BlockPos(x, y, z);
    }

    private String describe() {
        WaterPlan plan = this.waterPlan;
        if (plan == null) return "on land";
        return switch (plan) {
            case SHORE -> "swimming for the shore at " + (this.shore == null ? "?" : this.shore.toShortString());
            case TOWARD_LAND -> "no land in sight, heading back towards " + (this.brain.lastDryLand() == null ? "?" : this.brain.lastDryLand().toShortString());
            case TREAD -> "no land in sight, treading water till I spot some";
        };
    }

    @Override
    public void onOwnerDeath(BotPlayer bot) {
        this.waterPlan = null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.hide.stop(bot, this.brain);
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true; // dug in for the night: waiting for morning is the point
    }

    @Override
    public String activity() {
        WaterPlan plan = this.waterPlan;
        if (plan != null) return plan == WaterPlan.TREAD ? "Treading water" : "Swimming for land";
        return this.hide.activity() + " for the night";
    }
}
