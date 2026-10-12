package nl.kmc.crafting;

/**
 * Progress of one delivery task of one team. Items can be handed in piece by piece; only the missing
 * amount is ever taken, and the task completes exactly once.
 */
public final class TaskProgress {

    private final int required;
    private int delivered;
    private boolean done;

    public TaskProgress(int required) { this.required = Math.max(1, required); }

    /**
     * @param available how many matching items the player holds
     * @return how many of them are taken (0 when done or nothing available)
     */
    public int offer(int available) {
        if (done || available <= 0) return 0;
        int take = Math.min(available, required - delivered);
        delivered += take;
        if (delivered >= required) done = true;
        return take;
    }

    public int required() { return required; }
    public int delivered() { return delivered; }
    public int missing() { return required - delivered; }
    public boolean isDone() { return done; }
}
