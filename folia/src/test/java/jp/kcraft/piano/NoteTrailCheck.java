package jp.kcraft.piano;

/** Run with assertions enabled; no server or wall-clock sleeps required. */
public final class NoteTrailCheck {
    static final long SECOND = 1_000_000_000L;
    static void near(double a, double b) { if (Math.abs(a-b)>1e-8) throw new AssertionError(a+" != "+b); }
    public static void main(String[] args) {
        NoteTrail shortNote = new NoteTrail(0, 0);
        shortNote.release(SECOND/10);
        NoteTrail longNote = new NoteTrail(1, 0);
        longNote.release(SECOND*8/10);
        near(shortNote.frame(SECOND).length(), .04 + .1*NoteTrail.SPEED);
        near(longNote.frame(SECOND).length(), .04 + .8*NoteTrail.SPEED);
        near(shortNote.frame(SECOND).bottom(), .9*NoteTrail.SPEED);
        shortNote.release(SECOND); // Repeated release must not restart the tail.
        near(shortNote.frame(SECOND).bottom(), .9*NoteTrail.SPEED);
        NoteTrail held = new NoteTrail(2, 0);
        near(held.frame(SECOND/2).bottom(), 0);
        near(held.frame(SECOND/2).length(), .04+.5*NoteTrail.SPEED);
        near(held.frame(SECOND*5).length(), 3);
        if (held.frame(SECOND*5).finished()) throw new AssertionError("Held key disappeared");
        held.release(SECOND*5);
        if (!held.frame(SECOND*7).finished()) throw new AssertionError("Released key did not leave the window");
        NoteTrail instant = new NoteTrail(3, 0); instant.release(0);
        near(instant.frame(SECOND/2).length(), .04);
        System.out.println("NoteTrail: short/long hold, fixed released length, repeated release, long hold and instant tap passed");
    }
}
