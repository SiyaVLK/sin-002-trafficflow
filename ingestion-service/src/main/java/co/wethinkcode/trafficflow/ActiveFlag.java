package co.wethinkcode.trafficflow;

import java.util.Locale;
import java.util.Set;

/**
 * Reads the legacy {@code active_flag} column, which encodes one boolean in
 * every way a decade of different spreadsheets could manage:
 * {@code Y y yes YES 1 true TRUE t} and {@code N n no NO 0 false FALSE f},
 * plus {@code unknown}, {@code TBD} and blanks.
 *
 * <p>Anything not recognised returns null rather than defaulting. "We do not
 * know whether this signal is live" is a real state: collapsing it into false
 * would quietly remove working intersections from the network, and collapsing
 * it into true would route traffic through signals that may be dark. Routing
 * surfaces the uncertainty instead of inheriting a guess.
 */
public final class ActiveFlag {

    private static final Set<String> TRUE_VALUES = Set.of("y", "yes", "1", "true", "t", "active", "on");
    private static final Set<String> FALSE_VALUES = Set.of("n", "no", "0", "false", "f", "inactive", "off");

    private ActiveFlag() {
    }

    /** @return TRUE, FALSE, or null when blank, a placeholder, or unrecognised */
    public static Boolean parse(String raw) {
        String value = Placeholders.clean(raw).toLowerCase(Locale.ROOT);
        if (TRUE_VALUES.contains(value)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
