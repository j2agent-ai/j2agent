package io.github.jerryt92.j2agent.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** API 专用用户与其长期访问密钥的数据访问层。 */
@Mapper
public interface ApiAccessKeyMapper {

    @Select("""
            select k.id, k.user_id as userId, k.key_name as keyName, k.key_prefix as keyPrefix,
                   k.secret_hash as secretHash, k.create_time as createTime, k.last_used_time as lastUsedTime,
                   u.username, u.role, u.account_type as accountType, u.password_login_enabled as passwordLoginEnabled
              from api_access_key k join app_user u on u.id = k.user_id
             where k.id = #{id}
            """)
    KeyRow findById(@Param("id") String id);

    @Select("""
            select k.id, k.user_id as userId, k.key_name as keyName, k.key_prefix as keyPrefix,
                   k.secret_hash as secretHash, k.create_time as createTime, k.last_used_time as lastUsedTime,
                   u.username, u.role, u.account_type as accountType, u.password_login_enabled as passwordLoginEnabled
              from api_access_key k join app_user u on u.id = k.user_id
             where k.key_prefix = #{keyPrefix}
            """)
    KeyRow findByKeyPrefix(@Param("keyPrefix") String keyPrefix);

    @Select("""
            select k.id, k.user_id as userId, k.key_name as keyName, k.key_prefix as keyPrefix,
                   k.secret_hash as secretHash, k.create_time as createTime, k.last_used_time as lastUsedTime,
                   u.username, u.role, u.account_type as accountType, u.password_login_enabled as passwordLoginEnabled
              from api_access_key k join app_user u on u.id = k.user_id
             order by k.create_time desc
            """)
    List<KeyRow> list();

    @Insert("""
            insert into api_access_key (id, user_id, key_name, key_prefix, secret_hash, create_time)
            values (#{id}, #{userId}, #{keyName}, #{keyPrefix}, #{secretHash}, #{createTime})
            """)
    int insert(KeyRow key);

    @Update("update api_access_key set last_used_time = #{lastUsedTime} where id = #{id}")
    int updateLastUsedTime(@Param("id") String id, @Param("lastUsedTime") long lastUsedTime);

    /** 更新 API 专用用户角色。 */
    @Update("""
            update app_user set role = #{role}
             where id = #{userId} and account_type = 'API'
            """)
    int updateApiUserRole(@Param("userId") String userId, @Param("role") int role);

    /** 删除 API 专用用户（级联删除其 api_access_key）。 */
    @Delete("delete from app_user where id = #{userId} and account_type = 'API'")
    int deleteApiUser(@Param("userId") String userId);

    @Select("select exists(select 1 from app_user where id = #{userId} and account_type = 'API')")
    boolean isApiUser(@Param("userId") String userId);

    @Select("select password_login_enabled from app_user where id = #{userId}")
    Boolean isPasswordLoginEnabled(@Param("userId") String userId);

    record KeyRow(String id, String userId, String keyName, String keyPrefix, String secretHash,
                  Long createTime, Long lastUsedTime, String username, Integer role,
                  String accountType, Boolean passwordLoginEnabled) {
    }
}
