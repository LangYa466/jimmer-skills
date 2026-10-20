package com.example.model

import org.babyfish.jimmer.sql.TypedTuple

@TypedTuple
data class CourseRow(val id: Long, val name: String)
