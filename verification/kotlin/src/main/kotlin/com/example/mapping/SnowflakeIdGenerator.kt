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

class SnowflakeIdGenerator : UserIdGenerator<Long> {
    override fun generate(entityType: Class<*>): Long = SEQ.incrementAndGet()
    companion object { val SEQ = AtomicLong(1000) }
}
