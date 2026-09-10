package tricatch.oe.hub.model;

public class HubConf extends Auditable {
    private String confKey;
    private String confVal;

    public String getConfKey() { return confKey; }
    public void setConfKey(String confKey) { this.confKey = confKey; }

    public String getConfVal() { return confVal; }
    public void setConfVal(String confVal) { this.confVal = confVal; }
}
