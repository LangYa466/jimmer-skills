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
interface Document {
    @Id @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    val title: String
    @Serialized
    val attributes: Map<String, List<Int>>
    val settings: Settings?
    @LogicalDeleted("now")
    val deletedTime: LocalDateTime?
}
