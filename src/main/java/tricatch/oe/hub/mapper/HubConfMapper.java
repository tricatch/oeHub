package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import tricatch.oe.hub.model.HubConf;

public interface HubConfMapper {

    @Select("SELECT conf_key, conf_val, created_by, updated_by, create_at, updated_at FROM HUB_CONF WHERE conf_key = #{confKey}")
    HubConf findByConfKey(String confKey);

    @Insert("MERGE INTO HUB_CONF (conf_key, conf_val, created_by, updated_by, create_at, updated_at) KEY(conf_key) VALUES (#{confKey}, #{confVal}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})")
    void upsert(HubConf hubConf);
}
