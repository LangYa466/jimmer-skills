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

@Embeddable
interface Rect {
    @PropOverride(prop = "x", columnName = "LEFT_X")
    @PropOverride(prop = "y", columnName = "TOP_Y")
    val leftTop: Point
    @PropOverride(prop = "x", columnName = "RIGHT_X")
    @PropOverride(prop = "y", columnName = "BOTTOM_Y")
    val rightBottom: Point
}
