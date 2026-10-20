package com.example.model

import org.babyfish.jimmer.sql.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A book.
 */
@Entity
interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val name: String

    @Key
    val edition: Int

    val price: BigDecimal

    val modifiedTime: LocalDateTime

    @ManyToOne
    val store: BookStore?

    @ManyToMany
    @JoinTable(name = "BOOK_AUTHOR_MAPPING", joinColumnName = "BOOK_ID", inverseJoinColumnName = "AUTHOR_ID")
    val authors: List<Author>
}
