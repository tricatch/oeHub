package tricatch.oe.proxy.model;

import java.time.LocalDateTime;

public class ProxyConf {
    private Long userNo;
    private String confKey;
    private String confVal;
    private LocalDateTime updatedAt;

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getConfKey() { return confKey; }
    public void setConfKey(String confKey) { this.confKey = confKey; }

    public String getConfVal() { return confVal; }
    public void setConfVal(String confVal) { this.confVal = confVal; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
