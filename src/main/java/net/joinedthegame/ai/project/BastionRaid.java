package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Junk;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.task.LootChest;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Going through a bastion's chests for a netherite upgrade template (and the gold, debris and
 * other treasure that's in there with it). The treasure bastion always has a template in its
 * chest, so that's the one to go for when one's known; any bastion will do otherwise, chest by
 * chest, since the others only sometimes have one.
 * <p>
 * The everyday chest-looting leaves bastions alone (opening a chest in one sets every piglin
 * about on you, gold helmet or not); this is the deliberate, prepared version: in, chests, out.
 * The fighting is the combat goal's job as always. Hurt: out.
 */
public final class BastionRaid implements Task {
    private final GlobalPos bastion;
    private final boolean treasure;
    private @Nullable Task sub;
    private int ticks;
    private int chests;

    private BastionRaid(GlobalPos bastion, boolean treasure) {
        this.bastion = bastion;
        this.treasure = treasure;
    }

    /** The bastion to go for: a known treasure bastion first, any known bastion otherwise. */
    public static @Nullable BastionRaid pick(BotPlayer bot, BotBrain brain) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos treasure = brain.memory().nearest(Structures.TREASURE_BASTION, here, 1000);
        if (treasure != null && !brain.isBlacklisted(treasure.pos())) return new BastionRaid(treasure, true);
        GlobalPos any = brain.memory().nearest(Structures.Kind.BASTION.placeKey(), here, 1000);
        if (any != null && !brain.isBlacklisted(any.pos())) return new BastionRaid(any, false);
        return null;
    }

    public boolean treasure() {
        return this.treasure;
    }

    /** What's worth taking out of a bastion chest. */
    private static boolean wanted(ItemStack s) {
        return s.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) || Junk.isValuable(s) || Junk.isKeeper(s)
                || s.is(Items.GOLD_INGOT) || s.is(Items.GOLD_BLOCK) || s.is(Items.GOLD_NUGGET);
    }

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : "Raiding a " + (this.treasure ? "treasure " : "") + "bastion";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        // Got what we came for.
        if (Inv.count(bot, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) > 0) {
            brain.log("bastion: got a netherite upgrade template");
            return Status.DONE;
        }
        // Piglin brutes don't care about gold: hurt means out, while there's health to get out on.
        if (bot.getHealth() < 10) {
            brain.log("bastion: too hot in there, leaving");
            brain.blacklist(this.bastion.pos());
            return Status.FAILED;
        }
        if (++this.ticks > 20 * 60 * 8) return Status.FAILED;
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            if (this.sub instanceof TravelTask && s == Status.FAILED) {
                this.sub = null;
                brain.log("bastion: couldn't get there");
                brain.blacklist(this.bastion.pos());
                return Status.FAILED;
            }
            this.sub = null;
        }
        Structures.Known here = null;
        for (Structures.Known k : Structures.near(bot, 4)) {
            if (k.kind() == Structures.Kind.BASTION && k.contains(bot.blockPosition(), 4)) here = k;
        }
        if (here == null) {
            this.sub = new TravelTask(this.bastion.pos(), 16, "Heading to a bastion");
            return Status.RUNNING;
        }
        BlockPos chest = nextChest(bot, brain, here);
        if (chest == null) {
            brain.log("bastion: been through every chest I can get to (" + this.chests + "), no template");
            brain.blacklist(this.bastion.pos());
            return Status.FAILED;
        }
        this.chests++;
        this.sub = new LootChest(chest, BastionRaid::wanted, "Looting the bastion");
        return Status.RUNNING;
    }

    /** The nearest chest in the bastion we haven't been through. */
    private static @Nullable BlockPos nextChest(BotPlayer bot, BotBrain brain, Structures.Known bastion) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        var box = bastion.box();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                var chunk = bot.level().getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (!box.isInside(p) || !(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)) continue;
                    if (brain.recentlyLooted(p) || brain.isBlacklisted(p)) continue;
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

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
