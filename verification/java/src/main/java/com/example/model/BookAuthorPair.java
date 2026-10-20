package com.example.model;

import org.babyfish.jimmer.sql.TypedTuple;

@TypedTuple
public class BookAuthorPair {
    private final long bookId;
    private final long authorId;
    public BookAuthorPair(long bookId, long authorId) { this.bookId = bookId; this.authorId = authorId; }
    public long getBookId() { return bookId; }
    public long getAuthorId() { return authorId; }
}
