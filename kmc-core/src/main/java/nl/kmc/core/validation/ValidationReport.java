package nl.kmc.core.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of running one {@link Validator}: a list of individual checks,
 * each green (READY), yellow (PLAYABLE) or red (NOT_READY), plus an aggregate
 * status and a readiness score.
 */
public final class ValidationReport {

    public enum Status { READY, PLAYABLE, NOT_READY }

    /** One check line. {@code fixHint} is an optional "how to fix" message. */
    public record Check(String name, Status status, String message, String fixHint) {}

    private final List<Check> checks = new ArrayList<>();

    public void ok(String name)               { checks.add(new Check(name, Status.READY, "OK", null)); }
    public void ok(String name, String msg)   { checks.add(new Check(name, Status.READY, msg, null)); }
    public void warn(String name, String msg, String fix)  { checks.add(new Check(name, Status.PLAYABLE, msg, fix)); }
    public void error(String name, String msg, String fix) { checks.add(new Check(name, Status.NOT_READY, msg, fix)); }

    public List<Check> getChecks() { return Collections.unmodifiableList(checks); }
    public boolean isEmpty()       { return checks.isEmpty(); }

    public Status overall() {
        boolean err = false, warn = false;
        for (Check c : checks) {
            if (c.status() == Status.NOT_READY) err = true;
            else if (c.status() == Status.PLAYABLE) warn = true;
        }
        return err ? Status.NOT_READY : warn ? Status.PLAYABLE : Status.READY;
    }

    /** 0–100 readiness: READY=1, PLAYABLE=0.5, NOT_READY=0, averaged. */
    public int scorePercent() {
        if (checks.isEmpty()) return 100;
        double sum = 0;
        for (Check c : checks) {
            sum += switch (c.status()) { case READY -> 1.0; case PLAYABLE -> 0.5; case NOT_READY -> 0.0; };
        }
        return (int) Math.round(sum / checks.size() * 100);
    }

    public long countErrors()   { return checks.stream().filter(c -> c.status() == Status.NOT_READY).count(); }
    public long countWarnings() { return checks.stream().filter(c -> c.status() == Status.PLAYABLE).count(); }
}
