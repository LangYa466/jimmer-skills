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

class PhoneMaskConverter : Converter<String, String> {
    override fun output(value: String): String = value.take(3) + "****" + value.takeLast(4)
    override fun input(value: String): String = value
}
