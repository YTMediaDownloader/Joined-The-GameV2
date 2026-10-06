package net.joinedthegame.ai;

import java.util.Random;

/**
 * Hidden quirks that make each bot play a little differently, rolled once from its name so
 * a bot keeps the same personality forever. Nothing is shown to the player: you just notice
 * that one bot is always off mining while another follows you everywhere and a third keeps
 * getting itself killed.
 *
 * <p>Every trait is 0..1.
 */
public record Personality(
        /** Likes digging; prefers mining jobs and goes underground more. */
        double miner,
        /** Wanders off to see what's over the hill. */
        double explorer,
        /** Tags along with players and copies what they're doing. */
        double social,
        /** Picks fights instead of avoiding them. */
        double brave,
        /** Takes risky drops, ignores low health. The bot that keeps dying. */
        double reckless,
        /** Gets on with progression instead of idling. */
        double diligent,
        /** Would rather be at home: projects, doing the place up, the farm. Raids less, roams less. */
        double homebody,
        /** Wants a dog. Keeps animals. */
        double animalLover,
        /** Into books and enchanting. */
        double scholar,
        /** Puts everything away, often. Never throws anything out it could store. */
        double hoarder,
        /** Loves building: builds its own house straight away, upgrades it sooner, does the place up. */
        double builder,
        /**
         * Doesn't settle anywhere: puts its bed down wherever night falls, sleeps, packs it up in
         * the morning and carries on. No house, no chests. Picks its fights carefully.
         */
        double nomad) {

    public static Personality roll(String name) {
        Random random = new Random(name.toLowerCase(java.util.Locale.ROOT).hashCode() * 31L + 7L);
        // The first six are rolled in the same order as always, so existing bots keep theirs.
        double miner = random.nextDouble(), explorer = random.nextDouble(), social = random.nextDouble();
        double brave = random.nextDouble(), reckless = Math.pow(random.nextDouble(), 2), diligent = 0.3 + random.nextDouble() * 0.7;
        // The newer ones are strong in a few bots and faint in most: the one bot that's always
        // fussing with its dogs, the one that's always enchanting.
        double homebody = Math.pow(random.nextDouble(), 1.5), animalLover = Math.pow(random.nextDouble(), 2);
        double scholar = Math.pow(random.nextDouble(), 2), hoarder = Math.pow(random.nextDouble(), 2);
        double builder = Math.pow(random.nextDouble(), 2);
        // A true nomad is rare: most bots like a home.
        double nomad = random.nextDouble() < 0.06 ? 0.7 + random.nextDouble() * 0.3 : 0;
        Personality preset = preset(name);
        if (preset != null) return preset;
        return new Personality(miner, explorer, social, brave, reckless, diligent, homebody, animalLover, scholar, hoarder, builder, nomad);
    }

    /**
     * Some names play the way the real people behind them do.
     * <ul>
     *   <li><b>Duckatyt</b>: reckless in a fight, loves enchanting his gear, and a builder.</li>
     *   <li><b>cozmoiscool130</b>: a pure builder. He'll mine and explore when he has to.</li>
     *   <li><b>Redlantern8808</b>: a hardened veteran. Smart, won't fight unless he has to, and a
     *       nomad: bed down wherever night falls, back to exploring in the morning.</li>
     *   <li><b>DanTDM</b>: a friendly adventurer, mad about pets, loves a lab and gadgets
     *       (enchanting), always off exploring.</li>
     *   <li><b>stampylongnose</b>: Stampy, a big builder of his lovely world: homely,
     *       sociable, gentle (avoids fights), loves his animals and his farm.</li>
     * </ul>
     */
    public static @org.jspecify.annotations.Nullable Personality preset(String name) {
        //                                miner explr social brave reckl dilig  home  animal schol hoard build nomad
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "duckatyt" -> new Personality(0.5, 0.5, 0.5, 0.85, 0.85, 0.8, 0.3, 0.3, 0.9, 0.3, 0.75, 0);
            case "cozmoiscool130" -> new Personality(0.2, 0.15, 0.6, 0.4, 0.1, 0.9, 0.9, 0.4, 0.3, 0.5, 1.0, 0);
            case "redlantern8808" -> new Personality(0.5, 0.9, 0.3, 0.35, 0.05, 0.85, 0.05, 0.2, 0.4, 0.1, 0.1, 1.0);
            case "dantdm" -> new Personality(0.4, 0.8, 0.9, 0.6, 0.3, 0.7, 0.4, 0.95, 0.9, 0.5, 0.6, 0);
            case "stampylongnose" -> new Personality(0.3, 0.5, 1.0, 0.3, 0.1, 0.9, 0.9, 0.9, 0.4, 0.6, 0.95, 0);
            default -> null;
        };
    }
}
