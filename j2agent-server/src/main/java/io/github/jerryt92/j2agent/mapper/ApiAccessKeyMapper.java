package io.github.jerryt92.j2agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * API 专用账户与长期访问密钥的数据访问层。完整密钥绝不落库。
 */
@Mapper
public interface ApiAccessKeyMapper {
    KeyRow findById(@Param("id") String id);

    KeyRow findByKeyPrefix(@Param("keyPrefix") String keyPrefix);

    List<KeyRow> list();

    int insert(KeyRow key);

    int updateLastUsedTime(@Param("id") String id, @Param("lastUsedTime") long lastUsedTime);

    int updateApiUserRole(@Param("userId") String userId, @Param("role") int role);

    int deleteApiUser(@Param("userId") String userId);

    boolean isApiUser(@Param("userId") String userId);

    Boolean isPasswordLoginEnabled(@Param("userId") String userId);

    record KeyRow(String id, String userId, String keyName, String keyPrefix, String secretHash, Long createTime,
                  Long lastUsedTime, String username, Integer role, String accountType,
                  Boolean passwordLoginEnabled) {
    }
}
