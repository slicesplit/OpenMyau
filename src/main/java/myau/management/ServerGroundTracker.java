package myau.management;

/**
 * Tracks the last ground state that was sent to the server (serverOnGround).
 * Updated by MixinEntityPlayerSP when onUpdateWalkingPlayer fires.
 * Equivalent to FDPClient's MovementUtils.serverOnGround.
 */
public final class ServerGroundTracker {
    private ServerGroundTracker() {}

    /** The last onGround value sent to the server in a C03 packet. */
    public static volatile boolean serverOnGround = false;
}
