package myau.utility.backtrack;

public class Cold {
    private long lastMs;
    private long time;

    public Cold() {
        lastMs = System.currentTimeMillis();
    }

    public Cold(long lasts) {
        this.lastMs = lasts;
    }

    public void reset() {
        this.time = System.currentTimeMillis();
    }

    public long getTime() {
        return Math.max(0L, System.currentTimeMillis() - time);
    }

    public boolean getCum(long hentai) {
        return getTime() - lastMs >= hentai;
    }

    public boolean hasTimeElapsed(long owo, boolean reset) {
        if (getTime() >= owo) {
            if (reset) reset();
            return true;
        }
        return false;
    }
}