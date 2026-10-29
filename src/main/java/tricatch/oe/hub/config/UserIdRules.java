package tricatch.oe.hub.config;

/**
 * Rules for the user ids people can pick, as opposed to the ones oeHub creates itself.
 *
 * <p>The per-workspace system account is named {@code __wss_<wsNo>}, and its number is
 * predictable. The allowed-characters pattern includes {@code _}, so without a reserved prefix
 * someone could sign up under a future system account's name and make that workspace's creation
 * fail on the UNIQUE constraint.
 */
public final class UserIdRules {

    /** Prefix of every id oeHub generates; a chosen id may not start with it. */
    public static final String RESERVED_PREFIX = "__";

    private UserIdRules() {}

    /** The system account (role {@link Role#WSS}) that owns a workspace's orphaned resources. */
    public static String wsSystemUserId(long wsNo) {
        return RESERVED_PREFIX + "wss_" + wsNo;
    }

    public static boolean isReserved(String userId) {
        return userId != null && userId.startsWith(RESERVED_PREFIX);
    }
}
