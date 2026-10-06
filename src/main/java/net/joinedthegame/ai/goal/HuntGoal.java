package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Recipes;
import net.joinedthegame.ai.Roam;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.SmeltTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * Keeping a proper food supply, the way a player stocks up: 10-16 meals on hand (the exact
 * number depends on the bot). Restocks when it runs low rather than when it's starving.
 * <ul>
 *   <li><b>Hunt in batches:</b> keep going until there's enough meat, but leave the last
 *       couple of animals of each kind alone so they can breed.</li>
 *   <li><b>Cook it all at once</b> in a furnace.</li>
 *   <li><b>Raid villages:</b> hay bales become wheat become bread; ripe carrots and potatoes
 *       get picked.</li>
 * </ul>
 */
public final class HuntGoal extends Goal {
    private @Nullable Animal prey;
    private @Nullable Task task;
    private int ticks;
    private int repathIn;
    private boolean hunted;
    private boolean roaming;
    /** Animals we couldn't get to, and until when to leave them be. */
    private final java.util.Map<java.util.UUID, Long> unreachable = new java.util.HashMap<>();
    private boolean baking;
    private boolean cooking;
    private long cookRestUntil;

    public HuntGoal(BotBrain brain) {
        super(brain);
    }

    // --- what counts -----------------------------------------------------------------

    private static boolean isMeatAnimal(Animal a) {
        // Land animals on land: one paddling in a river isn't worth swimming after.
        return (a instanceof Cow || a instanceof Pig || a instanceof Sheep || a instanceof Chicken || a instanceof Rabbit) && !a.isBaby()
                && !a.isInWater();
    }

    /** Ready-to-eat, decent food (not raw meat, not rotten flesh). */
    private static int meals(BotPlayer bot) {
        return Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s) && !Inv.isRawMeat(s));
    }

    /** Bread we could bake from what we're carrying (3 wheat each; a hay bale is 3 bread). */
    private static int potentialBread(BotPlayer bot) {
        return Inv.count(bot, Items.WHEAT) / 3 + Inv.count(bot, Items.HAY_BLOCK) * 3;
    }

    private static int raw(BotPlayer bot) {
        return Inv.count(bot, Inv::isRawMeat);
    }

    /** How many meals this bot likes to carry: 10 to 16. */
    private int stockTarget() {
        return 10 + (int) (this.brain.personality().diligent() * 6);
    }

    private static boolean canCook(BotPlayer bot) {
        if (new Planner(bot, false).fuelValue() <= 0) return false;
        return Inv.count(bot, Items.FURNACE) > 0 || Inv.count(bot, Items.COBBLESTONE) + Inv.count(bot, Items.COBBLED_DEEPSLATE) >= 8
                || Senses.findBlock(bot, s -> s.is(net.minecraft.world.level.block.Blocks.FURNACE), 24, 8, false, p -> false) != null;
    }

    /** An animal we can hunt without wiping out the local population (leave two to breed). */
    private @Nullable Animal huntable(BotPlayer bot, boolean desperate) {
        long now = this.brain.now();
        this.unreachable.values().removeIf(until -> until < now);
        List<Animal> animals = Senses.entitiesNear(bot, Animal.class, 28,
                a -> isMeatAnimal(a) && !this.unreachable.containsKey(a.getUUID()));
        for (Animal a : animals) {
            if (desperate) return a;
            long sameKind = animals.stream().filter(o -> o.getType() == a.getType()).count();
            if (sameKind >= 3) return a;
        }
        return null;
    }

    private boolean harvestNearby(BotPlayer bot) {
        return Senses.findBlock(bot, Sources.HAY.blocks().or(Sources.CROPS.blocks()), 24, 6, true, this::ownFarm) != null;
    }

    /** Inside our own farm: leave it to FarmGoal, which replants. */
    private boolean ownFarm(net.minecraft.core.BlockPos pos) {
        var farm = this.brain.memory().nearest("farm", net.minecraft.core.GlobalPos.of(this.brain.dimension(), pos), 6);
        return farm != null;
    }

    // --- priority -------------------------------------------------------------------------

    @Override
    public int priority(BotPlayer bot) {
        int food = bot.getFoodData().getFoodLevel();
        int meals = meals(bot);
        int raw = raw(bot);
        boolean day = !bot.level().isDarkOutside();

        // Raw meat waiting and somewhere to cook it: get it done.
        if (raw > 0 && meals < stockTarget() && this.brain.now() > this.cookRestUntil && canCook(bot) && (raw >= 4 || meals < 3)) return 45;
        // Bake the wheat/hay we're carrying before going looking for more food.
        if (potentialBread(bot) > 0) return meals == 0 && food <= 8 ? 65 : 46;

        // Night and not geared for it: no wandering about the surface for food. Eat what we've
        // got (raw if need be) and go hunting in the morning, unless there's nothing at all.
        if (!day && !ShelterGoal.nightReady(bot)) {
            return meals == 0 && raw == 0 && food <= 6 ? 65 : 0;
        }
        if (meals == 0 && raw == 0 && food <= 8) return 65; // starving: drop everything
        // Running out (raw meat counts: it gets cooked, or eaten raw at a push). Not hungry yet?
        // Only bother if food's right there; the long search can wait till we're peckish.
        if (meals + raw < 3 && (food <= 14 || huntable(bot, false) != null || harvestNearby(bot))) return 48;
        if (meals + raw < stockTarget() && day) {        // topping up: routine, daytime only
            if (huntable(bot, false) != null || harvestNearby(bot)) return 36;
        }
        return 0;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.task = null;
        this.prey = null;
        this.hunted = false;
        this.roaming = false;
    }

    // --- tick ----------------------------------------------------------------------------

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 20 * 60 * 4) return false;
        if (this.task != null) {
            Task.Status status = this.task.tick(bot, this.brain);
            if (status == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            this.task = null;
            if (status == Task.Status.FAILED && this.baking) {
                // Couldn't bake (no wood for a table, say): try again later, not right away.
                this.baking = false;
                cooldown(this.brain.now(), 20 * 60 * 3);
                return false;
            }
            this.baking = false;
            if (status == Task.Status.FAILED && this.cooking) {
                // Couldn't cook (no furnace we can get to): leave it a while instead of looping.
                this.cooking = false;
                this.cookRestUntil = this.brain.now() + 20 * 60 * 2;
                return false;
            }
            this.cooking = false;
            return status != Task.Status.FAILED || decide(bot);
        }
        if (this.prey != null) return huntStep(bot);
        return decide(bot);
    }

    /** Pick the next food job. Returns false when there's nothing left to do. */
    private boolean decide(BotPlayer bot) {
        int meals = meals(bot);
        int raw = raw(bot);
        int target = stockTarget();
        boolean desperate = meals == 0 && bot.getFoodData().getFoodLevel() <= 8;

        if (meals + potentialBread(bot) >= target && raw == 0 && potentialBread(bot) == 0) return false;

        // Bread from wheat or hay: whatever that takes (a table, chopping wood for the table...).
        if (potentialBread(bot) > 0) {
            int bread = Inv.count(bot, Items.BREAD) + potentialBread(bot);
            Planner.Step step = new Planner(bot).next(Items.BREAD, bread);
            Task t = switch (step) {
                case null -> null;
                case Planner.Craft craft -> new CraftTask(craft);
                case Planner.Mine mine -> new MineTask(bot, mine.source(), mine.count(), mine.label());
                case Planner.Smelt smelt -> new SmeltTask(bot, smelt.input(), smelt.count(), smelt.output(), smelt.label());
                case Planner.Fetch fetch -> new net.joinedthegame.ai.task.FetchFromHome(fetch);
                case Planner.Fail fail -> null;
            };
            if (t != null) {
                this.task = t;
                this.baking = true;
                return true;
            }
        }

        // Cook all the meat once hunting's done (or we're about to run out).
        Animal next = huntable(bot, desperate);
        if (raw > 0 && canCook(bot) && (meals + raw >= target || next == null || meals < 3)) {
            if (startCooking(bot)) return true;
        }

        // Village hay bales and ripe crops: free food.
        if (harvestNearby(bot)) {
            boolean hay = Senses.findBlock(bot, Sources.HAY.blocks(), 24, 6, true, p -> false) != null;
            this.task = new MineTask(bot, hay ? Sources.HAY : Sources.CROPS, hay ? 2 : 6, hay ? "Taking hay bales" : "Raiding a farm").avoiding(this::ownFarm);
            return true;
        }

        // Hunt.
        if (next != null && meals + raw < target) {
            this.prey = next;
            this.hunted = false;
            return true;
        }

        // Nothing around and we really need food: go look (wheat on us counts).
        if (meals + raw + potentialBread(bot) < 3 && bot.getFoodData().getFoodLevel() <= 14) {
            this.roaming = true;
            Roam.tick(bot, this.brain.navigator());
            if (this.ticks < 20 * 90) return true;
            // Searched and found nothing: the area's hunted out. Get on with other things for
            // a while instead of wandering forever (unless actually starving).
            if (bot.getFoodData().getFoodLevel() > 8) cooldown(this.brain.now(), 20 * 60 * 3);
            return false;
        }
        return false;
    }

    private boolean startCooking(BotPlayer bot) {
        int slot = Inv.find(bot, Inv::isRawMeat);
        if (slot < 0) return false;
        ItemStack raw = bot.getInventory().getItem(slot);
        ItemStack cooked = Recipes.smeltResult(bot.level().getServer(), raw);
        if (cooked.isEmpty()) return false;
        Item rawItem = raw.getItem();
        this.task = new SmeltTask(bot, rawItem, Inv.count(bot, rawItem), cooked.getItem(), "Cooking food");
        this.cooking = true;
        return true;
    }

    private boolean huntStep(BotPlayer bot) {
        Animal prey = this.prey;
        // It went into the water: let it go and find another.
        if (prey != null && prey.isAlive() && prey.isInWater() && !this.hunted) {
            this.brain.navigator().stop(bot);
            this.prey = null;
            return true;
        }
        if (prey == null || !prey.isAlive()) {
            if (this.hunted && prey != null) this.task = new CollectItems(prey.blockPosition(), 6, 80);
            this.prey = null;
            return true;
        }
        this.hunted = true;
        Navigator nav = this.brain.navigator();
        double dist = Math.sqrt(prey.distanceToSqr(bot));
        if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        this.brain.controls().lookAt(bot, prey.getBoundingBox().getCenter());
        if (dist > 2.6) {
            if (--this.repathIn <= 0) {
                nav.sprint = dist > 6;
                nav.moveNear(bot, prey.position(), 1.5);
                this.repathIn = 15;
                if (nav.status() == Navigator.Status.FAILED) {
                    // Can't get to it (up a cliff, across a ravine): forget that one for a bit,
                    // or we'd pick it again straight away and stand here forever.
                    this.unreachable.put(prey.getUUID(), this.brain.now() + 20 * 60);
                    this.prey = null;
                    this.hunted = false;
                    nav.stop(bot);
                }
            }
        } else {
            nav.stop(bot);
            if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
                bot.attack(prey);
                bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        }
        return true;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null;
    }

    @Override
    public String activity() {
        Task task = this.task;
        if (task != null) return task.activity();
        if (this.prey != null) return "Hunting";
        return this.roaming ? "Looking for food" : "Getting food";
    }
}
