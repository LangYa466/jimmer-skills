package com.example.mapping;
import org.babyfish.jimmer.jackson.JsonConverter;
import org.babyfish.jimmer.jackson.LongToStringConverter;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
@Entity
@KeyUniqueConstraint(noMoreUniqueConstraints = true, isNullNotDistinct = true)
public interface UserAccount {
    @Id @GeneratedValue(generatorType = SnowflakeIdGenerator.class)
    @JsonConverter(LongToStringConverter.class)
    long id();
    @Key String email();
    @Key(group = "byCode") String code();
    @JsonConverter(PhoneMaskConverter.class) String phone();
    @Default("0") int state();
    @DatabaseDefault Instant createdAt();
    @Version int version();
    @ExcludeFromAllScalars String passwordHash();
    @Serialized Map<String, List<Integer>> attributes();
    Settings settings();
    @Column(sqlElementType = "text") String[] tags();
    @LogicalDeleted("now") @Nullable LocalDateTime deletedTime();
}
