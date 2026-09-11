package tricatch.oe.hub.model;

import java.time.LocalDateTime;

public class WsInvite extends Auditable {
    private String inviteCode;
    private Long wsNo;
    private Long teamNo;
    private boolean used;
    private LocalDateTime expiresAt;
    // Display-only join field (like HostsProf.updatedByUserId) - populated only by
    // findOutstandingByWsNo's LEFT JOIN HUB_TEAM, for the invite-list screen.
    private String teamName;

    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public Long getTeamNo() { return teamNo; }
    public void setTeamNo(Long teamNo) { this.teamNo = teamNo; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
