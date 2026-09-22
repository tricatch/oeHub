package tricatch.oe.hosts.model;

import tricatch.oe.hub.model.Auditable;

public class HostsProf extends Auditable {
    private String hostsId;
    private Long userNo;
    private String hostsProfile;
    private String hostsContent;
    private boolean selected;
    private int sortOrder;
    private String shareScope;
    private String parentId;
    // DEK wrapped by the row's KEK (personal key for 'private', workspace key for
    // 'collabo'/'workspace') - null under self-hosted, where hostsContent stays plaintext
    // (e2eEncryption design doc §1's oe.mode=workspace-only scope, corrected during implementation).
    private String wrappedContentKey;
    // Ciphertext for the fully-public, no-login-required link (e2eEncryption design doc §6 "living
    // link" redesign): encrypted with its own DEK, independent of wrappedContentKey, kept in sync
    // with hostsContent on every save via wrappedLinkKey below so the link never goes stale. The
    // raw key still only ever appears in the share URL's fragment. Null until a link has been
    // issued; issuing/revoking never touches hostsContent/wrappedContentKey. 'workspace' scope only.
    private String linkContent;
    // Wraps the link's own DEK with the workspace key (e2eEncryption design doc §6 "living link"
    // redesign) so any member's browser can re-encrypt linkContent on every save - null until a
    // link has been issued, paired 1:1 with linkContent (both null or both set).
    private String wrappedLinkKey;
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

    public String getShareScope() { return shareScope; }
    public void setShareScope(String shareScope) { this.shareScope = shareScope; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getWrappedContentKey() { return wrappedContentKey; }
    public void setWrappedContentKey(String wrappedContentKey) { this.wrappedContentKey = wrappedContentKey; }

    public String getLinkContent() { return linkContent; }
    public void setLinkContent(String linkContent) { this.linkContent = linkContent; }

    public String getWrappedLinkKey() { return wrappedLinkKey; }
    public void setWrappedLinkKey(String wrappedLinkKey) { this.wrappedLinkKey = wrappedLinkKey; }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
