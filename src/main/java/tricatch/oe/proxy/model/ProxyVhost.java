package tricatch.oe.proxy.model;

import tricatch.oe.hub.model.Auditable;

public class ProxyVhost extends Auditable {
    private String vhostId;
    private Long userNo;
    private String vhostProfile;
    private String vhostContent;
    private boolean selected;
    private int sortOrder;
    private String shareScope;
    private String parentId;
    private String userId;
    private String updatedByUserId;

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

    public String getShareScope() { return shareScope; }
    public void setShareScope(String shareScope) { this.shareScope = shareScope; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
