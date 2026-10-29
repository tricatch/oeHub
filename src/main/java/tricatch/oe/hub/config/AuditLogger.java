package tricatch.oe.hub.config;

import org.apache.ibatis.session.SqlSession;
import tricatch.oe.hub.mapper.HubAuditLogMapper;
import tricatch.oe.hub.model.HubAuditLog;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

// Records a Tier-1 (security/access-control) action to HUB_AUDIT_LOG. Callers pass the same
// SqlSession their main action is already using and commit once, so the action and its log entry
// can never diverge (one succeeding without the other).
public class AuditLogger {

    private AuditLogger() {
    }

    public static void record(SqlSession session, Long wsNo, String action, String targetType,
                               String targetId, String detail, Long actorUserNo) {
        var now = LocalDateTime.now();
        var row = new HubAuditLog();
        row.setWsNo(wsNo);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail);
        row.setCreatedBy(actorUserNo);
        row.setUpdatedBy(actorUserNo);
        row.setCreateAt(now);
        row.setUpdatedAt(now);
        session.getMapper(HubAuditLogMapper.class).insert(row);
    }

    /** Builds a small flat JSON object for the `detail` column - insertion order preserved. */
    public static String detail(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("detail() requires an even number of key/value arguments");
        }
        var fields = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            fields.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return toJson(fields);
    }

    private static String toJson(Map<String, Object> fields) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            var v = e.getValue();
            sb.append(v == null ? "null" : '"' + escape(String.valueOf(v)) + '"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
