package com.example.web;

import com.example.dto.BookInput;
import com.example.dto.BookView;
import com.example.error.UserInfoException;
import com.example.model.*;
import com.example.repo.BookRepository;
import org.babyfish.jimmer.client.FetchBy;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Book API.
 */
@RestController
@RequestMapping("/book")
@Transactional
public class BookController {

    private static final Fetcher<Book> SIMPLE_FETCHER = BookFetcher.$.name().edition();
    private static final Fetcher<Book> COMPLEX_FETCHER = BookFetcher.$
            .allScalarFields()
            .store(BookStoreFetcher.$.name())
            .authors(AuthorFetcher.$.firstName().lastName());

    private final BookRepository bookRepository;

    public BookController(BookRepository bookRepository) { this.bookRepository = bookRepository; }

    @GetMapping("/list")
    public List<@FetchBy("SIMPLE_FETCHER") Book> list() {
        return bookRepository.findAll(SIMPLE_FETCHER, BookProps.NAME, BookProps.EDITION.desc());
    }

    @GetMapping("/{id}")
    public @FetchBy(value = "COMPLEX_FETCHER", nullable = true) Book get(@PathVariable long id) {
        return bookRepository.findById(id, COMPLEX_FETCHER);
    }

    @GetMapping("/view/{id}")
    public BookView view(@PathVariable long id) {
        return bookRepository.findById(id, BookView.class);
    }

    @PutMapping
    public @FetchBy("COMPLEX_FETCHER") Book save(@RequestBody BookInput input) throws SaveException {
        return bookRepository.saveCommand(input).execute(COMPLEX_FETCHER).getModifiedEntity();
    }

    @PostMapping("/user")
    public void register(@RequestParam String password) throws UserInfoException {
        if (password.length() < 8) {
            throw UserInfoException.passwordTooShort("Password too short");
        }
    }
}
