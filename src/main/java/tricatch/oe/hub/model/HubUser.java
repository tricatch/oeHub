package tricatch.oe.hub.model;

import tricatch.oe.proxy.util.OidUtil;

import java.time.LocalDateTime;

public class HubUser extends Auditable {
    private Long userNo;
    private String userId;
    private String password;
    private String role;
    private int tokenVersion;
    private LocalDateTime lastLoginAt;

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getOid() { return OidUtil.encode(userNo); }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
