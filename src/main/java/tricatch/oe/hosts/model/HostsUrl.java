package tricatch.oe.hosts.model;

import tricatch.oe.hub.model.Auditable;

public class HostsUrl extends Auditable {
    private String urlId;
    private String urlName;
    private String urlValue;
    private int sortOrder;
    private Long userNo;
    private boolean mine;

    public String getUrlId() { return urlId; }
    public void setUrlId(String urlId) { this.urlId = urlId; }

    public String getUrlName() { return urlName; }
    public void setUrlName(String urlName) { this.urlName = urlName; }

    public String getUrlValue() { return urlValue; }
    public void setUrlValue(String urlValue) { this.urlValue = urlValue; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public boolean isMine() { return mine; }
    public void setMine(boolean mine) { this.mine = mine; }
}
