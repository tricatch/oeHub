package tricatch.oe.hosts.model;

import java.time.LocalDateTime;

public class HostsUa {
    private String uaId;
    private String uaName;
    private String uaValue;
    private int sortOrder;
    private Long userNo;
    private boolean mine;
    private LocalDateTime createAt;
    private LocalDateTime updatedAt;

    public String getUaId() { return uaId; }
    public void setUaId(String uaId) { this.uaId = uaId; }

    public String getUaName() { return uaName; }
    public void setUaName(String uaName) { this.uaName = uaName; }

    public String getUaValue() { return uaValue; }
    public void setUaValue(String uaValue) { this.uaValue = uaValue; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public boolean isMine() { return mine; }
    public void setMine(boolean mine) { this.mine = mine; }

    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
