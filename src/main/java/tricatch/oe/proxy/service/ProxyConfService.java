package tricatch.oe.proxy.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.model.ProxyConf;

import java.time.LocalDateTime;

public class ProxyConfService {

    private final SqlSessionFactory sqlSessionFactory;

    public ProxyConfService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public String get(String confKey, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            var conf = userNo != null
                ? mapper.findByUserNoAndConfKey(userNo, confKey)
                : mapper.findGlobal(confKey);
            return conf != null ? conf.getConfVal() : null;
        }
    }

    // actorUserNo is who is performing this save (a logged-in user for their own config, or the
    // admin saving a global config with userNo == null) - only used for created_by/updated_by,
    // never confused with the config's own (possibly null / global) userNo.
    public void set(String confKey, Long userNo, String value, Long actorUserNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            var now = LocalDateTime.now();
            var conf = new ProxyConf();
            conf.setUserNo(userNo);
            conf.setConfKey(confKey);
            conf.setConfVal(value);
            conf.setCreatedBy(actorUserNo);
            conf.setUpdatedBy(actorUserNo);
            conf.setCreateAt(now);
            conf.setUpdatedAt(now);
            mapper.upsert(conf);
            session.commit();
        }
    }
}
