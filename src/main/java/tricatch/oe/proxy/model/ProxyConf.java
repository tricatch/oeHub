package tricatch.oe.proxy.model;

import tricatch.oe.hub.model.Auditable;

public class ProxyConf extends Auditable {
    private Long userNo;
    private String confKey;
    private String confVal;

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getConfKey() { return confKey; }
    public void setConfKey(String confKey) { this.confKey = confKey; }

    public String getConfVal() { return confVal; }
    public void setConfVal(String confVal) { this.confVal = confVal; }
}
