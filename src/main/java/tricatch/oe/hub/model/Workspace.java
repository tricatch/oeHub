package tricatch.oe.hub.model;

public class Workspace extends Auditable {
    private Long wsNo;
    private String wsName;
    private String status;

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public String getWsName() { return wsName; }
    public void setWsName(String wsName) { this.wsName = wsName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
