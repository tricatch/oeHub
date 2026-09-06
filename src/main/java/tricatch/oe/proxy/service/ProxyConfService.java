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

    public void set(String confKey, Long userNo, String value) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            var conf = new ProxyConf();
            conf.setUserNo(userNo);
            conf.setConfKey(confKey);
            conf.setConfVal(value);
            conf.setUpdatedAt(LocalDateTime.now());
            mapper.upsert(conf);
            session.commit();
        }
    }
}
