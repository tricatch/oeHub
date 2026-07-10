package tricatch.oe.hub.model;

import java.time.LocalDateTime;

public class HubConf {
    private String confKey;
    private String confVal;
    private LocalDateTime updatedAt;

    public String getConfKey() { return confKey; }
    public void setConfKey(String confKey) { this.confKey = confKey; }

    public String getConfVal() { return confVal; }
    public void setConfVal(String confVal) { this.confVal = confVal; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
