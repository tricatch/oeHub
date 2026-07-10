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
            var conf = userNo != null
                ? mapper.findByUserNoAndConfKey(userNo, confKey)
                : mapper.findGlobal(confKey);
            if (conf == null) {
                var newConf = new ProxyConf();
                newConf.setUserNo(userNo);
                newConf.setConfKey(confKey);
                newConf.setConfVal(value);
                newConf.setUpdatedAt(LocalDateTime.now());
                mapper.insert(newConf);
            } else {
                conf.setConfVal(value);
                conf.setUpdatedAt(LocalDateTime.now());
                mapper.update(conf);
            }
            session.commit();
        }
    }
}
