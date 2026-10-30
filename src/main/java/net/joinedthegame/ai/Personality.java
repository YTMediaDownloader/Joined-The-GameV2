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
     * The roster's bots play the way their fictional Minecraft selves would (not claims about
     * the real people). Traits: miner, explorer, social, brave, reckless, diligent, homebody,
     * animal lover, scholar, hoarder, builder, nomad.
     * <ul>
     *   <li><b>madisonbeer</b>: sociable homebody, detailed builder, animal lover</li>
     *   <li><b>milliebobbybrown</b>: adventurous, loyal, brave, protective of friends</li>
     *   <li><b>sadiesink</b>: independent explorer, determined, self-sufficient</li>
     *   <li><b>jennaortega</b>: mysterious, explores by night, gothic builder</li>
     *   <li><b>mayahawke</b>: creative, curious, unconventional builder</li>
     *   <li><b>nataliadyer</b>: cautious, observant, thoughtful explorer</li>
     *   <li><b>emmamyers</b>: friendly, energetic, loves animals</li>
     *   <li><b>sabrinacarpenter</b>: sociable, decorative builder, collector</li>
     *   <li><b>oliviarodrigo</b>: independent, adventurous, sentimental</li>
     *   <li><b>billieeilish</b>: quiet, unusual architecture, night owl</li>
     *   <li><b>arianagrande</b>: cosy homes, decorations, company</li>
     *   <li><b>dualipa</b>: confident adventurer, ambitious projects</li>
     *   <li><b>selenagomez</b>: peaceful farmer, sociable, looks after everyone</li>
     *   <li><b>tatemcrae</b>: active explorer, competitive, restless</li>
     *   <li><b>haileesteinfeld</b>: brave fighter, team player, protector</li>
     *   <li><b>zendaya</b>: independent, versatile, ambitious builder</li>
     *   <li><b>florencepugh</b>: bold, resourceful, adventurous</li>
     *   <li><b>ldshadowlady</b>: fantasy cottages, colourful builds, pets, collecting</li>
     *   <li><b>geminitay</b>: forest bases, nature builds, big creative projects</li>
     *   <li><b>pearlescentmoon</b>: massive projects, strange architecture, adventurous</li>
     *   <li><b>falsesymmetry</b>: combat, exploration, disciplined survival</li>
     *   <li><b>shubble</b>: social and collaborative</li>
     *   <li><b>ihascupquake</b>: colourful builds and pets</li>
     *   <li><b>aphmau</b>: villages, community, relationships</li>
     *   <li><b>katherineeliza</b>: fantasy towns and decoration</li>
     *   <li><b>grian</b>: creative builder, playful troublemaker</li>
     *   <li><b>mumbo</b>: engineer, all about the redstone</li>
     *   <li><b>smallishbeans</b>: ambitious builder and explorer</li>
     *   <li><b>captainsparklez</b>: experienced adventurer, ambitious progression</li>
     *   <li><b>markiplier</b>: brave, experimental, a risk-taker</li>
     *   <li><b>stephencurry</b>: team player, competitive, persistent</li>
     *   <li><b>lebronjames</b>: leader, strategist, big ambitions</li>
     *   <li><b>finnwolfhard</b>: explorer, curious, independent</li>
     *   <li><b>joekeery</b>: sociable, loyal, protective</li>
     *   <li><b>jackblack</b>: reckless fun, enthusiastic explorer</li>
     *   <li><b>duckatyt</b>: reckless in a fight, loves enchanting his gear, a builder</li>
     *   <li><b>cozmoiscool130</b>: a pure builder; mines and explores when he has to</li>
     *   <li><b>redlantern8808</b>: hardened veteran and nomad: beds down wherever night falls</li>
     *   <li><b>dantdm</b>: friendly adventurer, mad about pets, loves a lab</li>
     *   <li><b>stampylongnose</b>: a big builder of his lovely world, gentle, loves his animals</li>
     *   <li><b>torchbearer99</b>: persistent miner and explorer (survivor of the Great Obsidian Crisis)</li>
     *   <li><b>bedrockbenny</b>: tough miner, gear collector, lives underground</li>
     *   <li><b>pixelpickle</b>: cautious survivor, practical adventurer (the skeleton experiments)</li>
     *   <li><b>axolotlandy</b>: easy-going, loves water and animals</li>
     *   <li><b>mudmuncher</b>: earthy digger and builder</li>
     *   <li><b>pentester</b>: mad about animals, forgetful, easily distracted (the buggy idiot)</li>
     * </ul>
     */
    public static @org.jspecify.annotations.Nullable Personality preset(String name) {
        //                                miner explr social brave reckl dilig  home  animal schol hoard build nomad
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "madisonbeer" -> new Personality(0.25, 0.5, 0.85, 0.3, 0.15, 0.8, 0.75, 0.9, 0.45, 0.7, 0.9, 0);
            case "milliebobbybrown" -> new Personality(0.4, 0.75, 0.8, 0.85, 0.35, 0.7, 0.35, 0.7, 0.3, 0.4, 0.4, 0);
            case "sadiesink" -> new Personality(0.55, 0.85, 0.35, 0.6, 0.25, 0.85, 0.25, 0.4, 0.35, 0.5, 0.35, 0.3);
            case "jennaortega" -> new Personality(0.6, 0.7, 0.3, 0.7, 0.4, 0.7, 0.35, 0.3, 0.6, 0.4, 0.75, 0);
            case "mayahawke" -> new Personality(0.3, 0.7, 0.5, 0.4, 0.3, 0.55, 0.5, 0.5, 0.6, 0.3, 0.85, 0);
            case "nataliadyer" -> new Personality(0.4, 0.7, 0.4, 0.25, 0.05, 0.75, 0.45, 0.5, 0.7, 0.6, 0.4, 0);
            case "emmamyers" -> new Personality(0.35, 0.6, 0.9, 0.5, 0.3, 0.65, 0.5, 0.95, 0.3, 0.4, 0.45, 0);
            case "sabrinacarpenter" -> new Personality(0.25, 0.45, 0.95, 0.35, 0.2, 0.65, 0.65, 0.6, 0.3, 0.9, 0.8, 0);
            case "oliviarodrigo" -> new Personality(0.45, 0.8, 0.5, 0.55, 0.35, 0.6, 0.4, 0.6, 0.5, 0.6, 0.5, 0.15);
            case "billieeilish" -> new Personality(0.5, 0.55, 0.25, 0.5, 0.35, 0.6, 0.6, 0.7, 0.5, 0.4, 0.85, 0);
            case "arianagrande" -> new Personality(0.2, 0.35, 0.9, 0.25, 0.1, 0.65, 0.9, 0.75, 0.35, 0.7, 0.85, 0);
            case "dualipa" -> new Personality(0.5, 0.75, 0.75, 0.75, 0.4, 0.85, 0.3, 0.4, 0.4, 0.5, 0.75, 0);
            case "selenagomez" -> new Personality(0.25, 0.35, 0.9, 0.3, 0.1, 0.8, 0.85, 0.8, 0.4, 0.6, 0.55, 0);
            case "tatemcrae" -> new Personality(0.5, 0.9, 0.55, 0.7, 0.5, 0.75, 0.15, 0.35, 0.25, 0.3, 0.35, 0.4);
            case "haileesteinfeld" -> new Personality(0.5, 0.55, 0.8, 0.95, 0.3, 0.8, 0.4, 0.5, 0.35, 0.4, 0.4, 0);
            case "zendaya" -> new Personality(0.55, 0.6, 0.55, 0.6, 0.2, 0.9, 0.45, 0.5, 0.55, 0.55, 0.85, 0);
            case "florencepugh" -> new Personality(0.6, 0.8, 0.6, 0.8, 0.45, 0.75, 0.35, 0.55, 0.4, 0.6, 0.5, 0.1);
            case "ldshadowlady" -> new Personality(0.3, 0.5, 0.6, 0.35, 0.15, 0.75, 0.8, 0.95, 0.4, 0.85, 0.95, 0);
            case "geminitay" -> new Personality(0.45, 0.6, 0.6, 0.45, 0.2, 0.9, 0.6, 0.7, 0.5, 0.6, 0.95, 0);
            case "pearlescentmoon" -> new Personality(0.55, 0.75, 0.6, 0.6, 0.35, 0.85, 0.4, 0.5, 0.4, 0.5, 0.95, 0);
            case "falsesymmetry" -> new Personality(0.6, 0.8, 0.5, 0.85, 0.25, 0.9, 0.3, 0.4, 0.5, 0.6, 0.7, 0);
            case "shubble" -> new Personality(0.35, 0.55, 0.95, 0.45, 0.3, 0.6, 0.6, 0.7, 0.35, 0.5, 0.6, 0);
            case "ihascupquake" -> new Personality(0.25, 0.45, 0.75, 0.35, 0.2, 0.7, 0.75, 0.95, 0.3, 0.6, 0.85, 0);
            case "aphmau" -> new Personality(0.3, 0.5, 1.0, 0.55, 0.25, 0.75, 0.7, 0.8, 0.4, 0.6, 0.7, 0);
            case "katherineeliza" -> new Personality(0.35, 0.5, 0.7, 0.4, 0.15, 0.8, 0.7, 0.6, 0.45, 0.6, 0.95, 0);
            case "grian" -> new Personality(0.4, 0.6, 0.85, 0.5, 0.6, 0.7, 0.45, 0.5, 0.5, 0.4, 1.0, 0);
            case "mumbo" -> new Personality(0.6, 0.4, 0.6, 0.4, 0.25, 0.75, 0.6, 0.4, 0.95, 0.7, 0.7, 0);
            case "smallishbeans" -> new Personality(0.5, 0.75, 0.75, 0.55, 0.35, 0.75, 0.45, 0.6, 0.4, 0.6, 0.8, 0);
            case "captainsparklez" -> new Personality(0.7, 0.75, 0.5, 0.75, 0.3, 0.95, 0.3, 0.4, 0.5, 0.6, 0.5, 0);
            case "markiplier" -> new Personality(0.55, 0.75, 0.6, 0.85, 0.75, 0.65, 0.35, 0.6, 0.6, 0.4, 0.5, 0);
            case "stephencurry" -> new Personality(0.5, 0.55, 0.9, 0.65, 0.25, 0.95, 0.45, 0.5, 0.35, 0.5, 0.45, 0);
            case "lebronjames" -> new Personality(0.55, 0.6, 0.9, 0.75, 0.25, 0.9, 0.4, 0.4, 0.6, 0.6, 0.7, 0);
            case "finnwolfhard" -> new Personality(0.45, 0.9, 0.5, 0.55, 0.4, 0.55, 0.3, 0.6, 0.55, 0.4, 0.35, 0.2);
            case "joekeery" -> new Personality(0.4, 0.5, 0.95, 0.75, 0.3, 0.65, 0.55, 0.6, 0.35, 0.5, 0.5, 0);
            case "jackblack" -> new Personality(0.5, 0.85, 0.9, 0.8, 0.9, 0.5, 0.3, 0.6, 0.3, 0.4, 0.45, 0);
            case "duckatyt" -> new Personality(0.5, 0.5, 0.5, 0.85, 0.85, 0.8, 0.3, 0.3, 0.9, 0.3, 0.75, 0);
            case "cozmoiscool130" -> new Personality(0.2, 0.15, 0.6, 0.4, 0.1, 0.9, 0.9, 0.4, 0.3, 0.5, 1.0, 0);
            case "redlantern8808" -> new Personality(0.5, 0.9, 0.3, 0.35, 0.05, 0.85, 0.05, 0.2, 0.4, 0.1, 0.1, 1.0);
            case "dantdm" -> new Personality(0.4, 0.8, 0.9, 0.6, 0.3, 0.7, 0.4, 0.95, 0.9, 0.5, 0.6, 0);
            case "stampylongnose" -> new Personality(0.3, 0.5, 1.0, 0.3, 0.1, 0.9, 0.9, 0.9, 0.4, 0.6, 0.95, 0);
            case "torchbearer99" -> new Personality(0.95, 0.7, 0.35, 0.55, 0.3, 0.9, 0.3, 0.3, 0.4, 0.6, 0.3, 0);
            case "bedrockbenny" -> new Personality(0.95, 0.4, 0.3, 0.7, 0.35, 0.85, 0.4, 0.25, 0.4, 0.9, 0.35, 0);
            case "pixelpickle" -> new Personality(0.45, 0.6, 0.5, 0.3, 0.05, 0.8, 0.5, 0.5, 0.4, 0.6, 0.45, 0);
            case "axolotlandy" -> new Personality(0.3, 0.6, 0.6, 0.45, 0.3, 0.6, 0.5, 0.95, 0.4, 0.5, 0.5, 0);
            case "mudmuncher" -> new Personality(0.7, 0.4, 0.5, 0.45, 0.35, 0.6, 0.6, 0.5, 0.3, 0.7, 0.6, 0);
            case "pentester" -> new Personality(0.4, 0.85, 0.7, 0.6, 0.6, 0.25, 0.4, 0.95, 0.2, 0.1, 0.5, 0);
            default -> null;
        };
    }

    /**
     * Mischief, 0..1: a taste for cruel "jokes" (like killing the pet of someone it doesn't care
     * for). Most bots have little; a few are known pranksters. Set here by name (the same bot every
     * time); everyone else's comes from who they are (reckless, not one for company, restless).
     */
    public static double mischief(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "pentester" -> 0.95;     // the buggy idiot
            case "grian" -> 0.85;         // the prankster
            case "duckatyt" -> 0.7;
            case "markiplier" -> 0.65;
            default -> {
                Personality p = preset(name);
                yield p == null ? 0.2 : Math.clamp(p.reckless() * 0.35 + (1 - p.social()) * 0.2 + p.explorer() * 0.1, 0.0, 0.55);
            }
        };
    }
}
