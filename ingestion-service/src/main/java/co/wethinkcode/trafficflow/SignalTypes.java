package co.wethinkcode.trafficflow;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical signal types.
 *
 * <p>The export writes the same control several ways — {@code 4-way},
 * {@code 4-Way}, {@code Roundabout}, {@code ROUNDABOUT} — and sometimes leaves
 * the column blank or says {@code unknown}. The variants collapse here, once,
 * so no other service has to know about them.
 *
 * <p>A value that is missing stays <b>null</b> rather than becoming a default.
 * "We were never told" and "this is a four-way" are different facts, and a
 * cleaner that erases the difference is lying to everything downstream.
 */
public final class SignalTypes {

    public static final String FOUR_WAY = "4-way";
    public static final String PEDESTRIAN = "pedestrian";
    public static final String ROUNDABOUT = "roundabout";
    public static final String STOP_SIGN = "stop-sign";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("4-way", FOUR_WAY),
            Map.entry("4way", FOUR_WAY),
            Map.entry("four-way", FOUR_WAY),
            Map.entry("fourway", FOUR_WAY),
            Map.entry("four_way", FOUR_WAY),
            Map.entry("traffic-light", FOUR_WAY),
            Map.entry("signal", FOUR_WAY),
            Map.entry("pedestrian", PEDESTRIAN),
            Map.entry("pedestrian-crossing", PEDESTRIAN),
            Map.entry("crossing", PEDESTRIAN),
            Map.entry("roundabout", ROUNDABOUT),
            Map.entry("traffic-circle", ROUNDABOUT),
            Map.entry("circle", ROUNDABOUT),
            Map.entry("stop-sign", STOP_SIGN),
            Map.entry("stop_sign", STOP_SIGN),
            Map.entry("stopsign", STOP_SIGN),
            Map.entry("stop", STOP_SIGN));

    private SignalTypes() {
    }

    /**
     * @return the canonical form, or null when the value is missing, a
     *         placeholder, or a variant nobody has taught us yet
     */
    public static String parse(String raw) {
        String value = Placeholders.clean(raw);
        if (value.isEmpty()) {
            return null;
        }
        return ALIASES.get(value.toLowerCase(Locale.ROOT).replace(' ', '-'));
    }
}
