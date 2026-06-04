package nl.kmc.kmccore.presentation.camera;

/**
 * Controls how the camera accelerates between two waypoints.
 * Applied to the normalised progress value t ∈ [0, 1].
 */
public enum InterpolationType {

    /** Constant speed — pure linear movement. */
    LINEAR {
        @Override public float apply(float t) { return t; }
    },

    /** Smooth start and end (classic smoothstep). Best for most shots. */
    SMOOTH {
        @Override public float apply(float t) { return t * t * (3 - 2 * t); }
    },

    /** Slow start, fast finish. */
    EASE_IN {
        @Override public float apply(float t) { return t * t * t; }
    },

    /** Fast start, slow finish. */
    EASE_OUT {
        @Override public float apply(float t) { float f = 1 - t; return 1 - f * f * f; }
    },

    /** Slow start, fast middle, slow finish (cubic ease-in-out). */
    EASE_IN_OUT {
        @Override public float apply(float t) {
            return t < 0.5f
                    ? 4 * t * t * t
                    : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
        }
    };

    /**
     * Maps a linear progress value to the eased value.
     *
     * @param t linear progress in [0, 1]
     * @return eased progress in [0, 1]
     */
    public abstract float apply(float t);

    /** Parses from a YAML string, falling back to SMOOTH on unknown values. */
    public static InterpolationType parse(String name) {
        if (name == null) return SMOOTH;
        try { return valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return SMOOTH; }
    }
}
