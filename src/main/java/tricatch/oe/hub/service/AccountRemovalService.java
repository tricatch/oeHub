package tricatch.oe.hub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.AuditLogger;
import tricatch.oe.hub.mapper.HubApiTokenMapper;
import tricatch.oe.hub.mapper.HubAuditLogMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.TeamMapper;
import tricatch.oe.hub.mapper.WorkspaceMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hub.proxy.mapper.ProxyConfMapper;
import tricatch.oe.hub.proxy.service.ProxyVhostService;
import tricatch.oe.proxy.ReverseProxyServer;

/**
 * The one place that knows everything an account (or a whole workspace) owns, so removing an
 * admin-deleted member, a self-withdrawing member and a wound-up workspace can never disagree
 * about what has to go.
 */
public class AccountRemovalService {

    private static final Logger logger = LoggerFactory.getLogger(AccountRemovalService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqlSessionFactory sqlSessionFactory;
    private final HostsProfService hostsProfService;
    private final ProxyVhostService proxyVhostService;

    public AccountRemovalService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.hostsProfService = new HostsProfService(sqlSessionFactory);
        this.proxyVhostService = new ProxyVhostService(sqlSessionFactory);
    }

    /**
     * Deletes one account and everything it owns. 'workspace'-shared hosts profiles and vhosts are
     * reassigned to the workspace's system account rather than deleted. Records auditAction in the
     * account's workspace log as performed by actorUserNo (the account itself for a withdrawal).
     */
    public void removeAccount(HubUser target, String auditAction, Long actorUserNo) {
        Long userNo = target.getUserNo();
        hostsProfService.deleteAllForAccountRemoval(userNo);
        proxyVhostService.deleteAllForAccountRemoval(userNo);
        try (var session = sqlSessionFactory.openSession()) {
            deletePerUserRows(session, userNo);
            // Must run before HUB_USR's own delete just below - HUB_WS_KEY has a real (not soft)
            // FK on user_no (e2eEncryption design doc §3/§9), so deleting the user first would
            // fail the constraint outright rather than leaving an orphaned row.
            session.getMapper(WsKeyMapper.class).deleteByUserNo(userNo);
            session.getMapper(HubUserMapper.class).deleteByUserNo(userNo);
            // Pseudonymize: earlier rows about this account lose their user ID string, and the
            // removal entry itself is written without one, leaving only the member number.
            stripUserId(session, target.getWsNo(), userNo, actorUserNo);
            AuditLogger.record(session, target.getWsNo(), auditAction, "user", String.valueOf(userNo),
                null, actorUserNo);
            session.commit();
        }
        // Their live oeProxy routing table and IP claim live in memory, not in the rows deleted
        // above; drop them so the removed member's routes stop serving immediately.
        ReverseProxyServer.forgetUser(userNo);
    }

    /**
     * Deletes one workspace completely - every account in it (pending applicants and the system
     * account included), their data, invites, teams, workspace key wraps and the audit log - and
     * nothing outside it. Nothing is reassigned (the workspace itself is going away) and no audit
     * entry is written, since the workspace's audit log goes with it.
     */
    public void removeWorkspace(Long wsNo) {
        var users = findAccounts(wsNo);
        for (var u : users) {
            hostsProfService.deleteAll(u.getUserNo());
            proxyVhostService.deleteAll(u.getUserNo());
        }
        try (var session = sqlSessionFactory.openSession()) {
            for (var u : users) {
                deletePerUserRows(session, u.getUserNo());
            }
            session.getMapper(WsKeyMapper.class).deleteByWsNo(wsNo);
            session.getMapper(WsInviteMapper.class).deleteByWsNo(wsNo);
            // Users before teams (HUB_USR.team_no FK), both before HUB_WS.
            session.getMapper(HubUserMapper.class).deleteByWsNo(wsNo);
            session.getMapper(TeamMapper.class).deleteByWsNo(wsNo);
            session.getMapper(HubAuditLogMapper.class).deleteByWsNo(wsNo);
            session.getMapper(HostsUaMapper.class).deleteAllByWsNo(wsNo);
            session.getMapper(HostsUrlMapper.class).deleteAllByWsNo(wsNo);
            session.getMapper(WorkspaceMapper.class).deleteByWsNo(wsNo);
            session.commit();
        }
        for (var u : users) {
            ReverseProxyServer.forgetUser(u.getUserNo());
        }
        logger.info("Workspace deleted: ws_no={} ({} account(s))", wsNo, users.size());
    }

    // Removes the "userId" key from the detail of every audit row targeting the account; a detail
    // left empty becomes NULL. Rows where the account was only the actor carry no ID string.
    private void stripUserId(org.apache.ibatis.session.SqlSession session, Long wsNo, Long userNo, Long actorUserNo) {
        var mapper = session.getMapper(HubAuditLogMapper.class);
        var now = java.time.LocalDateTime.now();
        for (var row : mapper.findUserTargetRows(wsNo, String.valueOf(userNo))) {
            try {
                var node = JSON.readTree(row.getDetail());
                if (!(node instanceof ObjectNode obj) || !obj.has("userId")) continue;
                obj.remove("userId");
                mapper.updateDetail(row.getAuditId(), obj.isEmpty() ? null : JSON.writeValueAsString(obj),
                    actorUserNo, now);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // Unparseable detail cannot be trusted to be free of the ID; drop it entirely.
                mapper.updateDetail(row.getAuditId(), null, actorUserNo, now);
            }
        }
    }

    // Rows keyed by user_no that reference HUB_USR (or are soft-referenced) and are not covered
    // by the hosts/proxy services above.
    private void deletePerUserRows(org.apache.ibatis.session.SqlSession session, Long userNo) {
        session.getMapper(HostsConfMapper.class).deleteAllByUserNo(userNo);
        session.getMapper(ProxyConfMapper.class).deleteAllByUserNo(userNo);
        session.getMapper(HostsUaMapper.class).deleteAllByUserNo(userNo);
        session.getMapper(HostsUrlMapper.class).deleteAllByUserNo(userNo);
        session.getMapper(HubApiTokenMapper.class).deleteAllByUserNo(userNo);
    }

    private java.util.List<HubUser> findAccounts(Long wsNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HubUserMapper.class).findAllByWsNo(wsNo);
        }
    }
}
