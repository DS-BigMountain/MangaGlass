package com.mangaglass.app;

/** Confines capture/translate/results to one user request. Access only on the UI thread. */
public final class SessionGate {
    public enum State { IDLE, WORKING, SHOWING, PEEKING, STOPPED }
    private State state = State.IDLE;
    private long generation;
    public State state() { return state; }
    public long begin() {
        if (state != State.IDLE) return -1;
        state = State.WORKING;
        return ++generation;
    }
    public boolean current(long token) { return token == generation && state == State.WORKING; }
    public boolean complete(long token) {
        if (!current(token)) return false;
        state = State.SHOWING;
        return true;
    }
    public void togglePreview() {
        if (state == State.SHOWING) state = State.PEEKING;
        else if (state == State.PEEKING) state = State.SHOWING;
    }
    public void reset() { if (state != State.STOPPED) { ++generation; state = State.IDLE; } }
    public void stop() { ++generation; state = State.STOPPED; }
}
