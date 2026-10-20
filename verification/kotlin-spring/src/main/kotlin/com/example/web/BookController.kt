package com.example.web

import com.example.dto.BookInput
import com.example.dto.BookView
import com.example.error.UserInfoException
import com.example.model.*
import com.example.repo.BookRepository
import org.babyfish.jimmer.client.FetchBy
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.exception.SaveException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

/**
 * Book API.
 */
@RestController
@RequestMapping("/book")
@Transactional
class BookController(
    private val bookRepository: BookRepository
) {

    @GetMapping("/list")
    fun list(): List<@FetchBy("SIMPLE_FETCHER") Book> =
        bookRepository.findAll(SIMPLE_FETCHER) {
            asc(Book::name)
            desc(Book::edition)
        }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): @FetchBy("COMPLEX_FETCHER") Book? =
        bookRepository.findById(id, COMPLEX_FETCHER)

    @GetMapping("/view/{id}")
    fun view(@PathVariable id: Long): BookView? =
        bookRepository.findById(id, BookView::class)

    @PutMapping
    @Throws(SaveException::class)
    fun save(@RequestBody input: BookInput): @FetchBy("COMPLEX_FETCHER") Book =
        bookRepository.saveCommand(input).execute(COMPLEX_FETCHER).modifiedEntity

    @PostMapping("/user")
    @Throws(UserInfoException::class)
    fun register(@RequestParam password: String) {
        if (password.length < 8) {
            throw UserInfoException.passwordTooShort("Password too short")
        }
    }

    companion object {
        private val SIMPLE_FETCHER = newFetcher(Book::class).by {
            name()
            edition()
        }

        private val COMPLEX_FETCHER = newFetcher(Book::class).by {
            allScalarFields()
            store { name() }
            authors { firstName(); lastName() }
        }
    }
}
