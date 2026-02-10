package me.ksyz.accountmanager.utils;

import java.security.SecureRandom;
import java.util.*;

public class UsernameGenerator {
    private static final SecureRandom random = new SecureRandom();
    
    private static final String[] WORD_PREFIXES = {
        "Dark", "Shadow", "Night", "Moon", "Sun", "Star", "Sky", "Cloud", "Storm", "Thunder",
        "Fire", "Ice", "Water", "Earth", "Wind", "Light", "Void", "Chaos", "Order", "Dream",
        "Soul", "Spirit", "Ghost", "Phantom", "Demon", "Angel", "Dragon", "Wolf", "Fox", "Bear",
        "Lion", "Tiger", "Eagle", "Hawk", "Raven", "Crow", "Snake", "Spider", "Frost", "Blaze",
        "Crystal", "Diamond", "Gold", "Silver", "Iron", "Steel", "Stone", "Rock", "Ocean", "River",
        "Lake", "Forest", "Mountain", "Desert", "Jungle", "Arctic", "Toxic", "Neon", "Cyber", "Nova",
        "Astro", "Cosmic", "Quantum", "Atomic", "Plasma", "Laser", "Nitro", "Turbo", "Hyper", "Ultra",
        "Mega", "Giga", "Meta", "Alpha", "Beta", "Omega", "Prime", "Elite", "Master", "Legend",
        "Mystic", "Enigma", "Cipher", "Stealth", "Silent", "Swift", "Rapid", "Flash", "Sonic", "Vortex",
        "Nexus", "Apex", "Zero", "Infinity", "Eclipse", "Nebula", "Galaxy", "Comet", "Meteor", "Lunar"
    };
    
    private static final String[] WORD_SUFFIXES = {
        "Hunter", "Slayer", "Killer", "Warrior", "Knight", "Mage", "Wizard", "Sorcerer", "Sage", "Monk",
        "Rogue", "Thief", "Ninja", "Samurai", "Shogun", "Lord", "King", "Queen", "Prince", "Emperor",
        "Master", "Legend", "Hero", "Champion", "Victor", "Conqueror", "Destroyer", "Creator", "Builder", "Maker",
        "Seeker", "Finder", "Walker", "Runner", "Jumper", "Flyer", "Swimmer", "Diver", "Climber", "Raider",
        "Defender", "Guardian", "Protector", "Keeper", "Watcher", "Scout", "Spy", "Agent", "Operative", "Ghost",
        "Phantom", "Wraith", "Specter", "Shadow", "Shade", "Spirit", "Soul", "Mind", "Heart", "Core",
        "Blade", "Sword", "Axe", "Hammer", "Spear", "Arrow", "Bow", "Shield", "Armor", "Crown",
        "Storm", "Tempest", "Hurricane", "Cyclone", "Tornado", "Tsunami", "Avalanche", "Earthquake", "Volcano", "Blizzard",
        "Forge", "Smith", "Craft", "Tech", "Byte", "Code", "Hack", "Glitch", "Pixel", "Bot",
        "Strike", "Blast", "Crash", "Crush", "Break", "Shatter", "Rift", "Breach", "Edge", "Claw"
    };
    
    private static final String[] STANDALONE_WORDS = {
        "Xenon", "Zephyr", "Onyx", "Orion", "Phoenix", "Titan", "Atlas", "Zeus", "Thor", "Odin",
        "Loki", "Ares", "Mars", "Venus", "Saturn", "Jupiter", "Neptune", "Pluto", "Mercury", "Apollo",
        "Artemis", "Athena", "Hades", "Poseidon", "Chronos", "Chaos", "Erebus", "Nyx", "Eos", "Helios",
        "Solaris", "Lunaris", "Stellaris", "Aquarius", "Scorpio", "Taurus", "Gemini", "Cancer", "Virgo", "Libra",
        "Sagittarius", "Capricorn", "Pisces", "Aries", "Leo", "Draco", "Hydra", "Chimera", "Griffin", "Kraken",
        "Leviathan", "Behemoth", "Colossus", "Goliath", "Juggernaut", "Sentinel", "Vanguard", "Harbinger", "Reaper", "Revenant",
        "Specter", "Phantom", "Wraith", "Banshee", "Valkyrie", "Ronin", "Shinobi", "Templar", "Crusader", "Paladin"
    };
    
    private static final String[] NUMBER_STYLES = {
        "", "x", "X", "_", "v", "V", "i", "I"
    };
    
    private static final String[] LEETSPEAK_REPLACEMENTS = {
        "a:4", "e:3", "i:1", "o:0", "s:5", "t:7", "l:1", "g:9", "b:8"
    };
    
    private static final Set<String> recentlyGenerated = Collections.synchronizedSet(new LinkedHashSet<String>());

    /**
     * Generate username in MILITARY-GRADE format matching: ythz_ieYai_01_21
     * Format: prefix_random_month_day
     * - prefix: 4 random lowercase letters
     * - random: 5 random mixed-case letters
     * - month: 2-digit month (01-12)
     * - day: 2-digit day (01-31)
     * Total: 4 + 1 + 5 + 1 + 2 + 1 + 2 = 16 characters (Mojang limit)
     */
    public static String generateUnique() {
        String username;
        int attempts = 0;
        
        do {
            username = generateFormatted();
            attempts++;
            
            if (attempts > 100) {
                break;
            }
        } while (recentlyGenerated.contains(username.toLowerCase()));
        
        recentlyGenerated.add(username.toLowerCase());
        
        if (recentlyGenerated.size() > 10000) {
            Iterator<String> iterator = recentlyGenerated.iterator();
            for (int i = 0; i < 5000 && iterator.hasNext(); i++) {
                iterator.next();
                iterator.remove();
            }
        }
        
        return username;
    }

    /**
     * Generate username in format: prefix_random_month_day
     * Examples: ythz_ieYai_01_21, xqpw_KmNaB_07_15, mnjk_QwErT_12_31
     * 
     * Format breakdown:
     * - prefix: 4 random lowercase letters (a-z)
     * - _: underscore separator
     * - random: 5 random mixed-case letters (A-Z, a-z)
     * - _: underscore separator
     * - month: 2-digit month (01-12)
     * - _: underscore separator
     * - day: 2-digit day (01-31)
     * 
     * Total: 4 + 1 + 5 + 1 + 2 + 1 + 2 = 16 characters (Mojang username limit)
     */
    private static String generateFormatted() {
        StringBuilder username = new StringBuilder();
        
        // Part 1: prefix (4 lowercase letters: a-z)
        for (int i = 0; i < 4; i++) {
            username.append(randomLowercaseLetter());
        }
        
        // Underscore separator
        username.append('_');
        
        // Part 2: random (5 mixed-case letters: A-Z, a-z)
        for (int i = 0; i < 5; i++) {
            username.append(randomLetter());
        }
        
        // Underscore separator
        username.append('_');
        
        // Part 3: month (01-12, zero-padded 2 digits)
        int month = random.nextInt(12) + 1; // 1-12
        username.append(String.format("%02d", month));
        
        // Underscore separator
        username.append('_');
        
        // Part 4: day (01-31, zero-padded 2 digits)
        int day = random.nextInt(31) + 1; // 1-31
        username.append(String.format("%02d", day));
        
        // Result: prefix_random_month_day = 16 characters exactly
        // Example: ythz_ieYai_01_21
        return username.toString();
    }
    
    /**
     * Generate random uppercase letter (A-Z)
     */
    private static char randomUppercaseLetter() {
        return (char) ('A' + random.nextInt(26));
    }
    
    /**
     * Generate random lowercase letter (a-z)
     */
    private static char randomLowercaseLetter() {
        return (char) ('a' + random.nextInt(26));
    }
    
    /**
     * Generate random letter (either uppercase or lowercase)
     */
    private static char randomLetter() {
        return random.nextBoolean() ? randomUppercaseLetter() : randomLowercaseLetter();
    }
    
    /**
     * Generate random digit (0-9)
     */
    private static char randomDigit() {
        return (char) ('0' + random.nextInt(10));
    }

    private static String generate() {
        return generateFormatted();
    }

    private static String generatePrefixSuffix() {
        String prefix = WORD_PREFIXES[random.nextInt(WORD_PREFIXES.length)];
        String suffix = WORD_SUFFIXES[random.nextInt(WORD_SUFFIXES.length)];
        return prefix + suffix;
    }

    private static String generatePrefixSuffixNumbers() {
        String base = generatePrefixSuffix();
        String style = NUMBER_STYLES[random.nextInt(NUMBER_STYLES.length)];
        int number = random.nextInt(9999) + 1;
        
        int position = random.nextInt(3);
        switch (position) {
            case 0:
                return style + number + base;
            case 1:
                return base + style + number;
            default:
                return base + number;
        }
    }

    private static String generateStandalone() {
        return STANDALONE_WORDS[random.nextInt(STANDALONE_WORDS.length)];
    }

    private static String generateStandaloneNumbers() {
        String word = STANDALONE_WORDS[random.nextInt(STANDALONE_WORDS.length)];
        int number = random.nextInt(9999) + 1;
        return random.nextBoolean() ? word + number : number + word;
    }

    private static String generateLeetSpeak() {
        String base = random.nextBoolean() ? generatePrefixSuffix() : generateStandalone();
        
        for (String replacement : LEETSPEAK_REPLACEMENTS) {
            if (random.nextDouble() < 0.4) {
                String[] parts = replacement.split(":");
                base = base.replace(parts[0], parts[1]);
            }
        }
        
        return base;
    }

    private static String generateUnderscoreStyle() {
        String[] parts = new String[random.nextInt(2) + 2];
        
        for (int i = 0; i < parts.length; i++) {
            if (random.nextBoolean()) {
                parts[i] = WORD_PREFIXES[random.nextInt(WORD_PREFIXES.length)];
            } else {
                parts[i] = WORD_SUFFIXES[random.nextInt(WORD_SUFFIXES.length)];
            }
        }
        
        return String.join("_", parts);
    }

    private static String generateMixedCase() {
        String base = generatePrefixSuffix();
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (random.nextBoolean()) {
                result.append(Character.toLowerCase(c));
            } else {
                result.append(Character.toUpperCase(c));
            }
        }
        
        return result.toString();
    }

    private static String generateComplex() {
        StringBuilder username = new StringBuilder();
        
        int wordCount = random.nextInt(2) + 2;
        for (int i = 0; i < wordCount; i++) {
            if (i > 0 && random.nextDouble() < 0.3) {
                username.append("_");
            }
            
            String word;
            if (random.nextBoolean()) {
                word = WORD_PREFIXES[random.nextInt(WORD_PREFIXES.length)];
            } else {
                word = WORD_SUFFIXES[random.nextInt(WORD_SUFFIXES.length)];
            }
            
            username.append(word);
        }
        
        if (random.nextDouble() < 0.6) {
            int number = random.nextInt(999) + 1;
            if (random.nextBoolean()) {
                username.append(number);
            } else {
                username.insert(0, number);
            }
        }
        
        String result = username.toString();
        if (result.length() > 16) {
            result = result.substring(0, 16);
        }
        
        return result;
    }

    public static String generateWithEntropy() {
        long timestamp = System.currentTimeMillis();
        int entropy = (int) (timestamp % 10000);
        
        String base = generate();
        String entropyStr = String.valueOf(entropy);
        
        if (base.length() + entropyStr.length() > 16) {
            base = base.substring(0, 16 - entropyStr.length());
        }
        
        return base + entropyStr;
    }

    public static String generateShort() {
        String prefix = WORD_PREFIXES[random.nextInt(WORD_PREFIXES.length)];
        int number = random.nextInt(999) + 1;
        return prefix + number;
    }

    public static String generateLong() {
        StringBuilder username = new StringBuilder();
        int parts = random.nextInt(3) + 3;
        
        for (int i = 0; i < parts; i++) {
            if (i > 0 && random.nextDouble() < 0.4) {
                username.append(random.nextBoolean() ? "_" : "");
            }
            
            if (random.nextBoolean()) {
                username.append(WORD_PREFIXES[random.nextInt(WORD_PREFIXES.length)]);
            } else {
                username.append(WORD_SUFFIXES[random.nextInt(WORD_SUFFIXES.length)]);
            }
        }
        
        String result = username.toString();
        if (result.length() > 16) {
            result = result.substring(0, 16);
        }
        
        return result;
    }

    public static void clearCache() {
        recentlyGenerated.clear();
    }

    public static int getCacheSize() {
        return recentlyGenerated.size();
    }
}
