package tricatch.oe.hosts.model;

import tricatch.oe.hub.model.Auditable;

public class HostsUa extends Auditable {
    private String uaId;
    private String uaName;
    private String uaValue;
    private int sortOrder;
    private Long userNo;
    private Long wsNo;
    private boolean mine;

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

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public boolean isMine() { return mine; }
    public void setMine(boolean mine) { this.mine = mine; }
}
