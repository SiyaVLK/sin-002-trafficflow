package co.wethinkcode.trafficflow;

import java.util.Locale;
import java.util.Set;

/**
 * The legacy export writes "there is no value here" in eight different ways.
 * They all mean the same thing and all become an empty value, so that no
 * downstream service ends up with a district literally named "N/A".
 */
public final class Placeholders {

    private static final Set<String> EMPTY_VALUES =
            Set.of("", "n/a", "na", "tbd", "unknown", "-", "--", "nan", "null", "?", "none");

    private Placeholders() {
    }

    /** @return the trimmed value, or "" if it is blank or a placeholder */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replaceAll("\\s+", " ").strip();
        return EMPTY_VALUES.contains(value.toLowerCase(Locale.ROOT)) ? "" : value;
    }
}
