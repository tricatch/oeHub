package tricatch.oe.hosts.model;

import java.time.LocalDateTime;

public class HostsProf {
    private String hostsId;
    private Long userNo;
    private String hostsProfile;
    private String hostsContent;
    private boolean selected;
    private int sortOrder;
    private String visibility;
    private String parentId;
    private LocalDateTime updatedAt;
    private String userId;

    public String getHostsId() { return hostsId; }
    public void setHostsId(String hostsId) { this.hostsId = hostsId; }

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getHostsProfile() { return hostsProfile; }
    public void setHostsProfile(String hostsProfile) { this.hostsProfile = hostsProfile; }

    public String getHostsContent() { return hostsContent; }
    public void setHostsContent(String hostsContent) { this.hostsContent = hostsContent; }

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
