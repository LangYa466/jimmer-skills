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
interface Transition {
    @Id val id: Long
    val source: Rect
    @PropOverride(prop = "leftTop.x", columnName = "TARGET_LEFT")
    @PropOverride(prop = "leftTop.y", columnName = "TARGET_TOP")
    @PropOverride(prop = "rightBottom.x", columnName = "TARGET_RIGHT")
    @PropOverride(prop = "rightBottom.y", columnName = "TARGET_BOTTOM")
    val target: Rect
}
