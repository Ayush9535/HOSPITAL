package com.hms.entity;

/**
 * When a dose is taken relative to food.
 *
 * <p>Deliberately a small closed vocabulary held as strings on {@link Prescription}, not a JPA
 * enum. Orders written before this existed carry nothing, and orders written by an older client
 * could carry anything; a strict enum column would fail to deserialise those rows rather than
 * showing them, which is the opposite of what a clinical record should do. Validation happens on
 * the way in, and reading is permissive.
 */
public final class FoodTiming {

    public static final String BEFORE_FOOD = "BEFORE_FOOD";
    public static final String AFTER_FOOD = "AFTER_FOOD";
    public static final String WITH_FOOD = "WITH_FOOD";
    public static final String NOT_SPECIFIED = "NOT_SPECIFIED";

    private static final java.util.Set<String> VALUES =
            java.util.Set.of(BEFORE_FOOD, AFTER_FOOD, WITH_FOOD, NOT_SPECIFIED);

    /**
     * Accept a recognised value, reject anything else, and treat blank as "not stated".
     *
     * <p>Blank returns null rather than NOT_SPECIFIED: an order where nobody answered the question
     * and one where somebody answered "it does not matter" are different facts, and only the
     * second is a clinical statement.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase();
        if (!VALUES.contains(value)) {
            throw new IllegalArgumentException(
                    "Food timing must be one of BEFORE_FOOD, AFTER_FOOD, WITH_FOOD or NOT_SPECIFIED");
        }
        return value;
    }

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private FoodTiming() {
    }
}
