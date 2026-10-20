package com.example.model

import org.babyfish.jimmer.sql.TypedTuple

@TypedTuple
data class BookAuthorPair(val bookId: Long, val authorId: Long)
