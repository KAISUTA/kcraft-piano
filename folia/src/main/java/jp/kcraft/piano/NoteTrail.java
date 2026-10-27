package jp.kcraft.piano;

/** A physical key's held interval, clipped to the three-block display window. */
final class NoteTrail {
    static final double SPEED = 3d / 1.6d, WINDOW = 3, MIN_LENGTH = .04;
    final int key;
    final long started;
    private volatile long released = Long.MAX_VALUE;

    NoteTrail(int key, long started) { this.key = key; this.started = started; }
    synchronized void release(long now) { if (released == Long.MAX_VALUE) released = now; }
    record Frame(double bottom, double length, boolean finished) {}
    Frame frame(long now) {
        long off = released;
        double head = Math.min(WINDOW, MIN_LENGTH + Math.max(0, (now - started) * 1e-9) * SPEED);
        double bottom = off == Long.MAX_VALUE ? 0 : Math.max(0, (now - off) * 1e-9) * SPEED;
        return new Frame(Math.min(WINDOW, bottom), Math.max(0, head - bottom), bottom >= WINDOW);
    }
}
