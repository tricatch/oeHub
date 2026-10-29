package tricatch.oe.hub.model;

public class Workspace extends Auditable {
    /** Name of the workspace /setup creates for the instance admin. Reserved: registration refuses it. */
    public static final String SYSTEM_NAME = "SYSTEM";

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
