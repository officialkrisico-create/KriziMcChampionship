package nl.kmc.game.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {

    @Test
    void valid_when_no_errors() {
        ValidationResult r = new ValidationResult();
        assertTrue(r.isValid());
        assertFalse(r.hasWarnings());
    }

    @Test
    void invalid_when_error_added() {
        ValidationResult r = new ValidationResult();
        r.addError("Spawn points not set");
        assertFalse(r.isValid());
        assertEquals(1, r.getErrors().size());
        assertTrue(r.getErrors().get(0).contains("Spawn points not set"));
    }

    @Test
    void warnings_do_not_affect_validity() {
        ValidationResult r = new ValidationResult();
        r.addWarning("Only 1 chest found — consider adding more");
        assertTrue(r.isValid());
        assertTrue(r.hasWarnings());
    }

    @Test
    void all_messages_combines_errors_and_warnings() {
        ValidationResult r = new ValidationResult();
        r.addError("Missing spawn");
        r.addWarning("Low chest count");
        assertEquals(2, r.getAllMessages().size());
    }
}
