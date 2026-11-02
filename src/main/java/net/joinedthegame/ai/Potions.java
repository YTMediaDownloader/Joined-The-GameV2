package net.joinedthegame.ai;

import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Potions: knowing what one does by what's in it (home brews, a witch's drop and chest loot all
 * the same), and drinking the right one at the right moment, the way a player would.
 * <ul>
 *   <li><b>Healing</b> when the fight's going badly: a splash one at our feet if we've got it
 *       (instant), otherwise drunk with a bit of room (drinking takes a second and a half).</li>
 *   <li><b>Regeneration</b> when hurt and there's a moment.</li>
 *   <li><b>Strength</b> going into a hard fight: a crowd, something nasty, a duel.</li>
 *   <li><b>Swiftness</b> to get away.</li>
 *   <li><b>Fire Resistance</b> on fire or in lava (any time, not only fights), and before blazes.</li>
 *   <li><b>Water Breathing</b> running out of air.</li>
 * </ul>
 * Never anything harmful (poison, weakness, harming: someone's splash potion ammunition).
 */
public final class Potions {
    private Potions() {}

    /** Between one potion and the next (so a fight isn't a drinking contest). */
    private static final int GAP = 60;

    /** Does this potion (drunk or splashed, not lingering) give that effect, and nothing bad? */
    public static boolean gives(ItemStack s, Holder<MobEffect> effect) {
        if (!(s.is(Items.POTION) || s.is(Items.SPLASH_POTION))) return false;
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null) return false;
        boolean has = false;
        for (MobEffectInstance e : c.getAllEffects()) {
            if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return false;
            if (e.is(effect)) has = true;
        }
        return has;
    }

    /** A potion in the bag giving that effect (splash first if {@code splashFirst}), or -1. */
    public static int find(BotPlayer bot, Holder<MobEffect> effect, boolean splashFirst) {
        int drink = -1;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (!gives(s, effect)) continue;
            if (s.is(Items.SPLASH_POTION) == splashFirst) return i;
            if (drink < 0) drink = i;
        }
        return drink;
    }

    public static int count(BotPlayer bot, Holder<MobEffect> effect) {
        return Inv.count(bot, s -> gives(s, effect));
    }

    /** Mid-swig: don't swap hands or the potion's wasted. */
    public static boolean drinking(BotPlayer bot) {
        return bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND && bot.getMainHandItem().is(Items.POTION);
    }

    /** Drink it (or throw it down at our feet, if it's a splash one). */
    public static boolean use(BotPlayer bot, BotBrain brain, int slot, String why) {
        if (slot < 0 || drinking(bot)) return false;
        ItemStack stack = bot.getInventory().getItem(slot);
        boolean splash = stack.is(Items.SPLASH_POTION);
        String name = name(stack);
        if (bot.isUsingItem()) bot.stopUsingItem(); // shield, bow, food: put it down
        Inv.hold(bot, slot);
        if (splash) Placer.lookExactly(bot, bot.position().add(0, -1, 0));
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        brain.lastPotionAt = brain.now();
        brain.log((splash ? "threw a " : "drank a ") + name + " (" + why + ")");
        return true;
    }

    /** Use one giving this effect, if we've got one and don't have the effect already. */
    public static boolean useFor(BotPlayer bot, BotBrain brain, Holder<MobEffect> effect, boolean splashFirst, String why) {
        if (bot.hasEffect(effect) || brain.now() - brain.lastPotionAt < GAP) return false;
        return use(bot, brain, find(bot, effect, splashFirst), why);
    }

    /**
     * In a fight: anything worth drinking right now? True if we've started (or are still busy
     * with) one, and the fight should give us the moment.
     *
     * @param nearest how close the nearest enemy is
     * @param danger  how hard the fight is (about 1 per zombie; see CombatGoal)
     */
    public static boolean fight(BotPlayer bot, BotBrain brain, double nearest, double danger, boolean gettingAway) {
        if (drinking(bot)) return true;
        if (brain.now() - brain.lastPotionAt < GAP) return false;
        float hp = bot.getHealth() / bot.getMaxHealth();
        // Fire first: it kills whatever else is going on.
        if ((bot.isOnFire() || bot.isInLava()) && useFor(bot, brain, MobEffects.FIRE_RESISTANCE, false, "on fire")) return true;
        // Losing it: healing. A splash one is instant; drinking one wants a bit of room.
        if (hp <= 0.4f && !bot.hasEffect(MobEffects.INSTANT_HEALTH)) {
            int slot = find(bot, MobEffects.INSTANT_HEALTH, true);
            if (slot >= 0 && (bot.getInventory().getItem(slot).is(Items.SPLASH_POTION) || nearest > 2.5 || gettingAway)
                    && use(bot, brain, slot, String.format(java.util.Locale.ROOT, "down to %.0f health", bot.getHealth()))) return true;
        }
        if (hp <= 0.6f && nearest > 3 && useFor(bot, brain, MobEffects.REGENERATION, false, "hurt")) return true;
        // Going into a hard one: strength while there's a moment (not with it already on us).
        if (!gettingAway && danger >= 3.0 && hp > 0.5f && nearest > 3 && useFor(bot, brain, MobEffects.STRENGTH, false, "a hard fight")) return true;
        return gettingAway && nearest > 3 && useFor(bot, brain, MobEffects.SPEED, false, "getting away");
    }

    /** Any time, fight or not: burning, or running out of air. */
    public static void reflex(BotPlayer bot, BotBrain brain) {
        if (drinking(bot) || brain.now() % 5 != 0) return;
        if ((bot.isInLava() || bot.isOnFire() && bot.getHealth() < bot.getMaxHealth() * 0.7f)
                && useFor(bot, brain, MobEffects.FIRE_RESISTANCE, false, bot.isInLava() ? "in lava" : "on fire")) return;
        if (bot.isUnderWater() && bot.getAirSupply() < bot.getMaxAirSupply() / 3) {
            useFor(bot, brain, MobEffects.WATER_BREATHING, false, "running out of air");
        }
    }

    /** "potion of Strength", "splash potion of Healing II". */
    public static String name(ItemStack s) {
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        String kind = s.is(Items.SPLASH_POTION) ? "splash potion" : s.is(Items.LINGERING_POTION) ? "lingering potion" : "potion";
        if (c == null) return kind;
        for (MobEffectInstance e : c.getAllEffects()) {
            String effect = e.getEffect().value().getDisplayName().getString();
            return kind + " of " + effect + (e.getAmplifier() > 0 ? " II" : "") + (e.getDuration() > 20 * 60 * 4 ? " (long)" : "");
        }
        return c.potion().map(p -> kind + " (" + p.getRegisteredName() + ")").orElse(kind);
    }
}
