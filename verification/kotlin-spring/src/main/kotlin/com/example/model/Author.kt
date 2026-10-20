package com.example.model

import org.babyfish.jimmer.sql.*

@Entity
interface Author {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val firstName: String

    @Key
    val lastName: String

    @ManyToMany(mappedBy = "authors")
    val books: List<Book>
}
