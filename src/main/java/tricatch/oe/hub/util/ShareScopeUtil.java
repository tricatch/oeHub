package tricatch.oe.hub.util;

/**
 * Share-scope handling shared by every path that turns a file the user supplied (export/import,
 * full-account backup/restore) into HOSTS_PFILE rows.
 */
public final class ShareScopeUtil {

    private ShareScopeUtil() {}

    /**
     * The share scope a row gets when it comes from an import/restore file. Only an explicit
     * "workspace" stays workspace-scoped; a missing, null or unrecognised value becomes "private", so
     * a file that says nothing about share scope (hand-written, from another tool, or an older format)
     * can never widen who sees the data. "collabo" is the one deliberate exception: it needs a live
     * parent reference that an import cannot recreate, so it is imported as a standalone
     * workspace-scoped row.
     */
    public static String forImport(Object raw) {
        return "workspace".equals(raw) || "collabo".equals(raw) ? "workspace" : "private";
    }
}
