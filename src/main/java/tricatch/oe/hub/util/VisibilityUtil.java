package tricatch.oe.hub.util;

/**
 * Visibility handling shared by every path that turns a file the user supplied (export/import,
 * full-account backup/restore) into rows.
 */
public final class VisibilityUtil {

    private VisibilityUtil() {}

    /**
     * The visibility a row gets when it comes from an import/restore file. Only an explicit
     * "public" stays public; a missing, null or unrecognised value becomes "private", so a file
     * that says nothing about visibility (hand-written, from another tool, or an older format) can
     * never widen who sees the data. "collabo" is the one deliberate exception: it needs a live
     * parent reference that an import cannot recreate, so it is imported as a standalone public row.
     */
    public static String forImport(Object raw) {
        return "public".equals(raw) || "collabo".equals(raw) ? "public" : "private";
    }
}
