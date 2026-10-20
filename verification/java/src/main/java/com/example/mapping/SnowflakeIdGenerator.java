package com.example.mapping;
import org.babyfish.jimmer.sql.meta.UserIdGenerator;
public class SnowflakeIdGenerator implements UserIdGenerator<Long> {
    @Override public Long generate(Class<?> entityType) { return System.currentTimeMillis(); }
}
