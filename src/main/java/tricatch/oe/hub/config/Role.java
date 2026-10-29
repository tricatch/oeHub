package tricatch.oe.hub.config;

import tricatch.oe.hub.model.HubUser;

/**
 * The single definition of HUB_USR.role values and of who may reach which URL prefix.
 *
 * <p>Every role code is exactly three lowercase letters. Nothing outside this class (and the
 * {@code role} column itself) should spell a role literal - use these constants, or
 * {@link #ADM}-style values inlined into SQL via string concatenation (they are compile-time
 * constants).
 *
 * <p>URL prefixes follow the role that may use them, so a path alone tells who can call it:
 * <ul>
 *   <li>{@code /adm/**} and {@code /api/adm/**} - instance admin ({@link #ADM}) only;</li>
 *   <li>{@code /wsa/**} and {@code /api/wsa/**} - workspace admin, see
 *       {@link #isWorkspaceAdmin(HubUser)};</li>
 *   <li>{@code /oehub/**} and the remaining {@code /api/**} - any account that can log in.</li>
 * </ul>
 */
public final class Role {

    /** Instance admin: instance-wide settings, CA, global presets, the workspace console. */
    public static final String ADM = "adm";
    /** Workspace admin: members, invites, teams, audit log and key rotation of one workspace. */
    public static final String WSA = "wsa";
    /** Ordinary member. */
    public static final String USR = "usr";
    /** Signed up but not yet approved by a workspace admin; cannot log in. */
    public static final String PEN = "pen";
    /** Non-login, workspace-owned system account that inherits orphaned resources; cannot log in. */
    public static final String WSS = "wss";

    private Role() {}

    /** Whether an account holding this role may hold a session at all. */
    public static boolean canLogin(String role) {
        return ADM.equals(role) || WSA.equals(role) || USR.equals(role);
    }

    public static boolean isInstanceAdmin(HubUser user) {
        return user != null && ADM.equals(user.getRole());
    }

    /**
     * Whether the user may manage their own workspace's members. Holders of {@link #WSA} always
     * may. {@link #ADM} may only in self-hosted mode, where the single workspace <em>is</em> the
     * instance; in workspace mode the instance operator is deliberately excluded so it can never
     * reach tenant-internal data (tenant isolation).
     */
    public static boolean isWorkspaceAdmin(HubUser user) {
        if (user == null) return false;
        if (WSA.equals(user.getRole())) return true;
        return !AppHome.isWorkspaceMode() && ADM.equals(user.getRole());
    }

    /**
     * The role that the members screen grants/revokes as "admin": {@link #WSA} in workspace mode
     * (that screen is workspace-scoped and must never grant the instance-wide {@link #ADM}),
     * {@link #ADM} in self-hosted, which has no separate workspace-admin holders.
     */
    public static String grantableAdminRole() {
        return AppHome.isWorkspaceMode() ? WSA : ADM;
    }
}
