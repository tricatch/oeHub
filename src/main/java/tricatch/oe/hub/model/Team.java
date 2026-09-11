package tricatch.oe.hub.model;

// Workspace -> team is one level only, no nesting (cloudGroupService design doc §2.9). Teams are
// a pure organizational label/filter - they carry no search or sharing scope of their own, and
// HOSTS_PFILE.visibility is untouched by this table.
public class Team extends Auditable {
    private Long teamNo;
    private Long wsNo;
    private String teamName;

    public Long getTeamNo() { return teamNo; }
    public void setTeamNo(Long teamNo) { this.teamNo = teamNo; }

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
}
