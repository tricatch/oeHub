package tricatch.oe.hub.i18n;

public class LocaleContext {

    private static final ThreadLocal<String> current = new ThreadLocal<>();

    public static void set(String locale) { current.set(locale); }
    public static String get() { return current.get() != null ? current.get() : "en"; }
    public static void clear() { current.remove(); }
}
