package com.example.mapping
import org.babyfish.jimmer.Formula
import org.babyfish.jimmer.jackson.Converter
import org.babyfish.jimmer.jackson.JsonConverter
import org.babyfish.jimmer.jackson.LongToStringConverter
import org.babyfish.jimmer.sql.*
import org.babyfish.jimmer.sql.meta.UUIDIdGenerator
import org.babyfish.jimmer.sql.meta.UserIdGenerator
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Entity
interface UserAccount {
    @Id @GeneratedValue(generatorType = SnowflakeIdGenerator::class)
    @JsonConverter(LongToStringConverter::class)
    val id: Long
    @JsonConverter(PhoneMaskConverter::class)
    val phone: String
    @ExcludeFromAllScalars
    val passwordHash: String
    @Default("0")
    val state: Int
    @DatabaseDefault
    val createdAt: Instant
    @LogicalDeleted
    val deletedMillis: Long
}
