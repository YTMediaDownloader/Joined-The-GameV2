package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Containers;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * See a chest, open it, take the good stuff. Structures are the big one: a bot in a village,
 * temple, outpost, mineshaft or fortress goes through the chests (and the minecart chests)
 * properly, like any player would. Never its own home chests or another bot's.
 */
public final class LootGoal extends Goal {
    private @Nullable BlockPos chest;
    private @Nullable MinecartChest cart;
    private int ticks;
    private int openTicks;
    private long lastLook = -1000;
    private boolean inStructure;

    public LootGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (!JtgConfig.LOOT_CHESTS.get() || Inv.usedSlots(bot) > 30) return 0;
        if (this.chest == null && this.cart == null && this.brain.now() - this.lastLook >= 20) {
            this.lastLook = this.brain.now();
            find(bot);
        }
        if (this.chest == null && this.cart == null) return 0;
        // In a structure: that's what we came for (above everyday gearing up, 40 max).
        return this.inStructure ? 46 : 35;
    }

    private void find(BotPlayer bot) {
        Structures.Known here = this.brain.structureHere();
        this.inStructure = here != null && here.kind().loot;
        int radius = this.inStructure ? 24 : 12;
        int yRadius = this.inStructure ? 10 : 4;
        BotManager manager = BotManager.get(bot.level().getServer());
        this.chest = Senses.findBlock(bot, s -> s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(Blocks.TRAPPED_CHEST), radius, yRadius, false,
                p -> this.brain.recentlyLooted(p) || this.brain.isBlacklisted(p) || manager.isBotStorage(bot.level(), p)
                        // Bastion chests: every piglin around goes for you. Not without a plan.
                        || here != null && here.kind() == Structures.Kind.BASTION);
        this.cart = null;
        if (this.chest == null) {
            List<MinecartChest> carts = Senses.entitiesNear(bot, MinecartChest.class, radius,
                    c -> c.isAlive() && !c.isEmpty() && !this.brain.recentlyLooted(c.blockPosition()));
            if (!carts.isEmpty()) this.cart = carts.getFirst();
        }
    }

    /** Going for a chest in a structure (not just one lying about). */
    public boolean raidingStructure() {
        return this.inStructure && (this.chest != null || this.cart != null);
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.openTicks = 0;
    }

    /** Anything worth carrying: skip rotten flesh and the like, take the rest. */
    private static boolean wanted(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return !(stack.is(Items.ROTTEN_FLESH) || stack.is(Items.POISONOUS_POTATO) || stack.is(Items.SPIDER_EYE)
                || stack.is(Items.GUNPOWDER)
                || stack.is(Items.TNT) || stack.is(Items.SAND) || stack.is(Items.GRAVEL));
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 20 * 40) return false;
        if (this.cart != null) return lootCart(bot, this.cart);
        BlockPos chest = this.chest;
        if (chest == null) return false;
        if (!BlockBreaker.canUse(bot, chest)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, chest);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(chest);
                    this.chest = null;
                    return false;
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, Vec3.atCenterOf(chest));
        this.openTicks++;
        if (this.openTicks == 5 && Containers.open(bot, chest) == null) {
            this.brain.markLooted(chest);
            this.chest = null;
            return false;
        }
        if (this.openTicks == 25 && bot.containerMenu instanceof ChestMenu menu) {
            int took = Containers.take(bot, menu.getContainer(), LootGoal::wanted);
            if (took > 0) noteLoot(bot);
        }
        if (this.openTicks >= 40) {
            Containers.close(bot);
            this.brain.markLooted(chest);
            this.chest = null;
            noteLoot(bot);
            return false;
        }
        return true;
    }

    /** Minecart chests (mineshafts): walk up and empty it. */
    private boolean lootCart(BotPlayer bot, MinecartChest cart) {
        if (!cart.isAlive()) return false;
        if (bot.distanceToSqr(cart) > 3 * 3) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving() || this.ticks % 20 == 0) {
                nav.moveNear(bot, cart.position(), 1.5);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.markLooted(cart.blockPosition());
                    this.cart = null;
                    return false;
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, cart);
        if (++this.openTicks < 15) return true;
        Placer.lookExactly(bot, cart.getBoundingBox().getCenter());
        int took = Containers.take(bot, (Container) cart, LootGoal::wanted);
        if (took > 0) noteLoot(bot);
        this.brain.markLooted(cart.blockPosition());
        this.cart = null;
        return false;
    }

    /** Took something: is the whole structure done now? (Only then is it "raided".) */
    private void noteLoot(BotPlayer bot) {
        Structures.Known here = this.brain.structureHere();
        if (here != null) net.joinedthegame.ai.RaidProgress.checkComplete(bot, this.brain, here);
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        Containers.close(bot);
    }

    @Override
    public String activity() {
        Structures.Known here = this.brain.structureHere();
        return here != null && here.kind().loot ? "Raiding the " + here.kind().label : "Looting a chest";
    }
}
