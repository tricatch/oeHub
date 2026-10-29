package tricatch.oe.hub.util;

/**
 * The user already has a sidebar entry (hosts profile, virtual host, folder) with that name,
 * compared case-insensitively. Controllers answer it with 409 so the page can say so.
 */
public class NameTakenException extends RuntimeException {
    public NameTakenException() { super("name taken"); }
}
