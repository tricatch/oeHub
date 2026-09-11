package tricatch.oe.hub.model;

import tricatch.oe.proxy.util.OidUtil;

import java.time.LocalDateTime;

public class HubUser extends Auditable {
    private Long userNo;
    private String userId;
    private String password;
    private String role;
    private Long wsNo;
    private Long teamNo;
    private int tokenVersion;
    private String publicKey;
    private String wrappedPrivateKey;
    private String wrappedPrivateKeyRecovery;
    private String recoveryVerifier;
    private LocalDateTime lastLoginAt;
    // Display-only join field (like HostsProf.updatedByUserId) - never selected outside the
    // findAll/searchByUserId member-list queries that LEFT JOIN HUB_TEAM.
    private String teamName;

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getOid() { return OidUtil.encode(userNo); }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public Long getTeamNo() { return teamNo; }
    public void setTeamNo(Long teamNo) { this.teamNo = teamNo; }

    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getWrappedPrivateKey() { return wrappedPrivateKey; }
    public void setWrappedPrivateKey(String wrappedPrivateKey) { this.wrappedPrivateKey = wrappedPrivateKey; }

    public String getWrappedPrivateKeyRecovery() { return wrappedPrivateKeyRecovery; }
    public void setWrappedPrivateKeyRecovery(String wrappedPrivateKeyRecovery) { this.wrappedPrivateKeyRecovery = wrappedPrivateKeyRecovery; }

    public String getRecoveryVerifier() { return recoveryVerifier; }
    public void setRecoveryVerifier(String recoveryVerifier) { this.recoveryVerifier = recoveryVerifier; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
}
