package tricatch.oe.hub.model;

import java.time.LocalDateTime;

public class WsInvite extends Auditable {
    private String inviteCode;
    private Long wsNo;
    private boolean used;
    private LocalDateTime expiresAt;

    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
