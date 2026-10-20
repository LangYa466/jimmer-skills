package com.example.mapping;
import org.babyfish.jimmer.sql.*;
import org.babyfish.jimmer.sql.meta.UUIDIdGenerator;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;
@Entity
public interface Document {
    @Id @GeneratedValue(generatorType = UUIDIdGenerator.class) UUID id();
    @LogicalDeleted @Nullable UUID deletedData();
}
