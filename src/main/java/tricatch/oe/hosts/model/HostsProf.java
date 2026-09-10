package tricatch.oe.hosts.model;

import tricatch.oe.hub.model.Auditable;

public class HostsProf extends Auditable {
    private String hostsId;
    private Long userNo;
    private String hostsProfile;
    private String hostsContent;
    private boolean selected;
    private int sortOrder;
    private String visibility;
    private String parentId;
    // DEK wrapped by the row's KEK (personal key for 'private', workspace key for
    // 'collabo'/'public') - null under standalone, where hostsContent stays plaintext
    // (e2eEncryption design doc §1's oe.mode=group-only scope, corrected during implementation).
    private String wrappedContentKey;
    // Snapshot ciphertext for the fully-public, no-login-required link (e2eEncryption design doc
    // §6's last item): encrypted with its OWN, separate DEK that's never wrapped by anything and
    // never stored anywhere - it lives only in the share URL's fragment. Null until a link has
    // been issued; issuing/revoking never touches hostsContent/wrappedContentKey. 'public' only.
    private String linkContent;
    private String userId;
    private String updatedByUserId;

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

    public String getWrappedContentKey() { return wrappedContentKey; }
    public void setWrappedContentKey(String wrappedContentKey) { this.wrappedContentKey = wrappedContentKey; }

    public String getLinkContent() { return linkContent; }
    public void setLinkContent(String linkContent) { this.linkContent = linkContent; }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
