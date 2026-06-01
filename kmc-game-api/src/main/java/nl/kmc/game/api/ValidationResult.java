package nl.kmc.game.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of an arena validation check. */
public final class ValidationResult {

    private final List<String> errors   = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void addError(String message)   { errors.add("[ERROR] " + message); }
    public void addWarning(String message) { warnings.add("[WARN]  " + message); }

    public boolean isValid()             { return errors.isEmpty(); }
    public boolean hasWarnings()         { return !warnings.isEmpty(); }

    public List<String> getErrors()      { return Collections.unmodifiableList(errors); }
    public List<String> getWarnings()    { return Collections.unmodifiableList(warnings); }

    public List<String> getAllMessages() {
        List<String> all = new ArrayList<>(errors);
        all.addAll(warnings);
        return all;
    }

    @Override public String toString() {
        return "ValidationResult{valid=" + isValid() + ", errors=" + errors.size()
                + ", warnings=" + warnings.size() + "}";
    }
}
