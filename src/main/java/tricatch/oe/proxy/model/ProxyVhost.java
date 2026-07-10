package tricatch.oe.proxy.model;

import java.time.LocalDateTime;

public class ProxyVhost {
    private String vhostId;
    private Long userNo;
    private String vhostProfile;
    private String vhostContent;
    private boolean selected;
    private int sortOrder;
    private String visibility;
    private String parentId;
    private LocalDateTime updatedAt;
    private String userId;

    public String getVhostId() { return vhostId; }
    public void setVhostId(String vhostId) { this.vhostId = vhostId; }

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getVhostProfile() { return vhostProfile; }
    public void setVhostProfile(String vhostProfile) { this.vhostProfile = vhostProfile; }

    public String getVhostContent() { return vhostContent; }
    public void setVhostContent(String vhostContent) { this.vhostContent = vhostContent; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
