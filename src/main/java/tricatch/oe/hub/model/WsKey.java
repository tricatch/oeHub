package tricatch.oe.hub.model;

public class WsKey extends Auditable {
    private Long wsNo;
    private Long userNo;
    private String wrappedWsKey;

    public Long getWsNo() { return wsNo; }
    public void setWsNo(Long wsNo) { this.wsNo = wsNo; }

    public Long getUserNo() { return userNo; }
    public void setUserNo(Long userNo) { this.userNo = userNo; }

    public String getWrappedWsKey() { return wrappedWsKey; }
    public void setWrappedWsKey(String wrappedWsKey) { this.wrappedWsKey = wrappedWsKey; }
}
