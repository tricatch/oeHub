package tricatch.oe.hub.model;

public class HubAuditLog extends Auditable {
    private Long auditId;
    private Long wsNo;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    // Display-only join field (like HostsProf.updatedByUserId), populated only by
    // HubAuditLogMapper.findByWsNo's LEFT JOIN HUB_USR on created_by.
    private String actorUserId;

    public Long getAuditId() { return auditId; }
    public void setAuditId(Long auditId) { this.auditId = auditId; }

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
}
