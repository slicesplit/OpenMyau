package myau.management;

import myau.event.EventManager;
import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.event.types.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global Transaction Manager - Foundation for all Grim bypasses
 * 
 * Tracks transaction packet timing to synchronize bypasses with Grim's validation windows.
 * This is CRITICAL for Backtrack, Velocity, NoSlow, and Timer bypasses.
 * 
 * How Grim Uses Transactions:
 * - Sends S32 (server) transaction before critical events
 * - Expects C0F (client) confirmation response
 * - Uses timing between transactions to detect cheats
 * - "Transaction sandwich" = S32 → Event → S32
 */
public class TransactionManager {
    private static final TransactionManager INSTANCE = new TransactionManager();
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Transaction tracking
    private final Map<Short, Long> sentTransactions = new ConcurrentHashMap<>();
    private final Map<Short, Long> confirmedTransactions = new ConcurrentHashMap<>();
    
    // Timing data
    private long lastConfirmTime = 0L;
    private long lastSendTime = 0L;
    private int averagePing = 50;
    private int transactionCount = 0;
    
    // Transaction window state
    private boolean inTransactionWindow = false;
    private short lastTransactionId = 0;
    
    // Ping calculation
    private static final int PING_SAMPLES = 10;
    private final long[] pingSamples = new long[PING_SAMPLES];
    private int pingSampleIndex = 0;
    
    private TransactionManager() {
        EventManager.register(this);
    }
    
    public static TransactionManager getInstance() {
        return INSTANCE;
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        
        if (event.getType() == EventType.SEND) {
            // Track outgoing transaction confirmations
            if (event.getPacket() instanceof C0FPacketConfirmTransaction) {
                C0FPacketConfirmTransaction packet = (C0FPacketConfirmTransaction) event.getPacket();
                short id = packet.getUid();
                long currentTime = System.currentTimeMillis();
                
                sentTransactions.put(id, currentTime);
                lastSendTime = currentTime;
                transactionCount++;
            }
        }
        
        if (event.getType() == EventType.RECEIVE) {
            // Track incoming transaction requests from server
            if (event.getPacket() instanceof S32PacketConfirmTransaction) {
                S32PacketConfirmTransaction packet = (S32PacketConfirmTransaction) event.getPacket();
                short id = packet.getActionNumber();
                long currentTime = System.currentTimeMillis();
                
                confirmedTransactions.put(id, currentTime);
                lastConfirmTime = currentTime;
                lastTransactionId = id;
                
                // Calculate ping from round-trip
                Long sentTime = sentTransactions.get(id);
                if (sentTime != null) {
                    long roundTrip = currentTime - sentTime;
                    updatePing(roundTrip);
                }
                
                // Enter transaction window
                inTransactionWindow = true;
            }
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        
        // Transaction window expires after 50ms (1 tick)
        if (System.currentTimeMillis() - lastConfirmTime > 50) {
            inTransactionWindow = false;
        }
        
        // Cleanup old transactions every 100 ticks
        if (transactionCount % 100 == 0) {
            cleanupOldTransactions();
        }
    }
    
    /**
     * Update ping with exponential moving average
     */
    private void updatePing(long roundTrip) {
        pingSamples[pingSampleIndex] = roundTrip;
        pingSampleIndex = (pingSampleIndex + 1) % PING_SAMPLES;
        
        // Calculate average from samples
        long sum = 0;
        int count = 0;
        for (long sample : pingSamples) {
            if (sample > 0) {
                sum += sample;
                count++;
            }
        }
        
        if (count > 0) {
            averagePing = (int) (sum / count);
        }
    }
    
    /**
     * Cleanup transactions older than 5 seconds
     */
    private void cleanupOldTransactions() {
        long cutoff = System.currentTimeMillis() - 5000;
        sentTransactions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        confirmedTransactions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
    
    // ==================== Public API ====================
    
    /**
     * Check if we're currently in a transaction confirmation window
     * This is the safest time to execute bypasses
     */
    public boolean isInTransactionWindow() {
        return inTransactionWindow;
    }
    
    /**
     * Get time since last transaction confirmation (ms)
     */
    public long getTimeSinceLastTransaction() {
        return System.currentTimeMillis() - lastConfirmTime;
    }
    
    /**
     * Get average ping to server (ms)
     */
    public int getPing() {
        return Math.max(1, averagePing); // Never return 0
    }
    
    /**
     * Get last transaction ID received
     */
    public short getLastTransactionId() {
        return lastTransactionId;
    }
    
    /**
     * Check if safe to execute time-sensitive bypass
     * Returns true if within transaction window OR recent transaction
     */
    public boolean isSafeForBypass() {
        return inTransactionWindow || getTimeSinceLastTransaction() < 100;
    }
    
    /**
     * Get recommended delay for next action based on ping
     */
    public long getRecommendedDelay() {
        // Add 20% buffer to ping for safety
        return (long) (averagePing * 1.2);
    }
    
    /**
     * Calculate Grim's allowed time window for an action
     * Based on ping + clockDrift (default 120ms in Grim)
     */
    public long getGrimTimeWindow() {
        return averagePing + 120; // Grim's default clockDrift
    }
}
