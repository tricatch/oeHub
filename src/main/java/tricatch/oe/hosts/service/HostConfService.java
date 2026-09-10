package tricatch.oe.hosts.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.model.HostsConf;

import java.time.LocalDateTime;

public class HostConfService {

    private final SqlSessionFactory sqlSessionFactory;

    public HostConfService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public String get(Long userNo, String confKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsConfMapper.class);
            var hostsConf = mapper.findByUserNoAndConfKey(userNo, confKey);
            return hostsConf != null ? hostsConf.getConfVal() : null;
        }
    }

    public void set(Long userNo, String confKey, String confVal) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsConfMapper.class);
            // MERGE replaces the whole row, so re-fetch first to preserve the original
            // create_at/created_by across repeat saves of the same key instead of resetting
            // them to "now"/the current actor on every edit.
            var existing = mapper.findByUserNoAndConfKey(userNo, confKey);
            var now = LocalDateTime.now();

            var hostsConf = new HostsConf();
            hostsConf.setUserNo(userNo);
            hostsConf.setConfKey(confKey);
            hostsConf.setConfVal(confVal);
            hostsConf.setCreatedBy(existing != null ? existing.getCreatedBy() : userNo);
            hostsConf.setUpdatedBy(userNo);
            hostsConf.setCreateAt(existing != null ? existing.getCreateAt() : now);
            hostsConf.setUpdatedAt(now);

            mapper.upsert(hostsConf);

            session.commit();
        }
    }
}
