package com.example.model

import com.example.model.common.BaseEntity
import org.babyfish.jimmer.Formula
import org.babyfish.jimmer.sql.*

@Entity
interface Author : BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val firstName: String

    @Key
    val lastName: String

    val gender: Gender

    @LogicalDeleted("true")
    val deleted: Boolean

    @ManyToMany(mappedBy = "authors")
    val books: List<Book>

    @Formula(dependencies = ["firstName", "lastName"])
    val fullName: String
        get() = "$firstName $lastName"

    @Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)")
    val sqlFullName: String
}
