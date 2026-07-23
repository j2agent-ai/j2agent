INSERT INTO ai_properties (property_name, property_value, description)
SELECT 'agent-global-config-json',
       '{"datasource":{"jdbcUrl":"jdbc:mysql://host.docker.internal:3306/?characterEncoding=UTF-8&useUnicode=true&useSSL=false&tinyInt1isBit=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","username":"","password":"","driverClassName":"com.mysql.cj.jdbc.Driver"},"service":{"baseUrl":""}}',
       '智能体全局配置JSON'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_properties WHERE property_name = 'agent-global-config-json'
);
