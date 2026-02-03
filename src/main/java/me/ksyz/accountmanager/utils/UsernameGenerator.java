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

    public static String generateUnique() {
        String username;
        int attempts = 0;
        
        do {
            username = generate();
            attempts++;
            
            if (attempts > 100) {
                username = generateComplex();
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

    private static String generate() {
        int strategy = random.nextInt(10);
        
        switch (strategy) {
            case 0:
            case 1:
                return generatePrefixSuffix();
            case 2:
            case 3:
                return generatePrefixSuffixNumbers();
            case 4:
                return generateStandalone();
            case 5:
                return generateStandaloneNumbers();
            case 6:
                return generateLeetSpeak();
            case 7:
                return generateUnderscoreStyle();
            case 8:
                return generateMixedCase();
            default:
                return generateComplex();
        }
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
