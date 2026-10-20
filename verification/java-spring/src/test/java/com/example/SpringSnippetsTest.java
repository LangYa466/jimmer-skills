package com.example;

import com.example.model.*;
import com.example.repo.BookJRepository;
import com.example.repo.BookRepository;
import com.example.runtime.Hooks;
import org.babyfish.jimmer.Page;
import org.babyfish.jimmer.spring.model.SortUtils;
import org.babyfish.jimmer.spring.repo.PageParam;
import org.babyfish.jimmer.spring.repository.SpringOrders;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode;
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class SpringSnippetsTest {

    @Autowired JSqlClient sqlClient;
    @Autowired BookRepository bookRepository;
    @Autowired BookJRepository bookJRepository;
    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate tx;

    @Test
    public void starterAndHooks() {
        assertNotNull(sqlClient);
        assertTrue(Hooks.CUSTOMIZED.get() >= 1);   // once per Spring context
        assertTrue(Hooks.INITIALIZED.get() >= 1);
        Book saved = bookRepository.save(Immutables.createBook(d -> d
                .setName("Spring").setEdition(1).setPrice(BigDecimal.ONE))).getModifiedEntity();
        assertTrue(saved.id() > 0);             // modifiedTime set by DraftInterceptor bean
        bookRepository.deleteById(saved.id(), DeleteMode.PHYSICAL);
    }

    @Test
    public void classRepository() {
        Page<Book> page = bookRepository.findBooks(PageParam.byIndex(0, 2), "graphql", BookFetcher.$.name());
        assertEquals(3, page.getTotalRowCount());
        assertEquals(2, page.getRows().size());
        assertEquals(2, page.getTotalPageCount());
        assertEquals(3, bookRepository.findAll(BookFetcher.$.name(), BookProps.NAME, BookProps.EDITION.desc()).size());
        Page<Book> p2 = bookRepository.findPage(PageParam.byNo(2, 2), BookFetcher.$.name(), BookProps.NAME);
        assertEquals(1, p2.getRows().size());
        assertNotNull(bookRepository.findById(1L, BookFetcher.$.name()));
        assertEquals(2, bookRepository.findByIds(List.of(1L, 2L), BookFetcher.$.name()).size());

        tx.executeWithoutResult(s -> {
            Book b = bookRepository.insert(Immutables.createBook(d -> d
                    .setName("Repo").setEdition(1).setPrice(BigDecimal.TEN))).getModifiedEntity();
            bookRepository.update(Immutables.createBook(d -> d.setId(b.id()).setPrice(BigDecimal.ONE)));
            bookRepository.save(Immutables.createBook(d -> d.setId(b.id()).setAuthorIds(List.of(1L))), AssociatedSaveMode.MERGE);
            assertEquals(List.of(1L), bookRepository.findById(b.id(), BookFetcher.$.authorIds()).authorIds());
            bookRepository.deleteById(b.id(), DeleteMode.PHYSICAL);
            s.setRollbackOnly();
        });
    }

    @Test
    public void springDataRepository() {
        List<Book> books = bookJRepository.findByNameLikeIgnoreCaseAndPriceBetween(
                "graphql", new BigDecimal(50), null, BookFetcher.$.name().store(BookStoreFetcher.$.name()));
        assertEquals(3, books.size());
        assertEquals(1, bookJRepository.findByNameLikeIgnoreCaseAndPriceBetween(null, null, new BigDecimal(50), null).size());
        org.springframework.data.domain.Page<Book> page = bookJRepository.findByStoreName(
                "MANNING", PageRequest.of(0, 10), BookFetcher.$.name());
        assertEquals(1, page.getTotalElements());
        assertEquals(2, bookJRepository.countByName("Learning GraphQL"));
        assertEquals(1, bookJRepository.findCheap(new BigDecimal(50)).size());

        Sort sort = SortUtils.toSort("name asc, edition desc");
        BookTable t = BookTable.$;
        List<String> names = sqlClient.createQuery(t)
                .orderBy(SpringOrders.toOrders(t, sort))
                .select(t.name())
                .execute();
        assertEquals("GraphQL in Action", names.get(0));
    }

    @Test
    public void web() throws Exception {
        mvc.perform(get("/book/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("GraphQL in Action"))
                .andExpect(jsonPath("$[0].price").doesNotExist());
        mvc.perform(get("/book/1"))
                .andExpect(jsonPath("$.store.name").value("O'REILLY"))
                .andExpect(jsonPath("$.authors.length()").value(2));
        mvc.perform(get("/book/view/1")).andExpect(jsonPath("$.store.name").value("O'REILLY"));
        mvc.perform(post("/book/user").param("password", "123"))
                .andExpect(status().is(500))
                .andExpect(jsonPath("$.family").value("USER_INFO"))
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));

        String yml = mvc.perform(asyncDispatch(mvc.perform(get("/openapi.yml")).andReturn()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(yml.contains("/book/list"), yml);
        System.err.println("OPENAPI\n" + yml);
        assertTrue(yml.contains("SIMPLE_FETCHER"), yml);
        mvc.perform(get("/openapi.html")).andExpect(status().isOk());

        org.springframework.test.web.servlet.MvcResult tsResult = mvc.perform(get("/ts.zip")).andReturn();
        byte[] zip = (tsResult.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(tsResult)).andReturn() : tsResult)
                .getResponse().getContentAsByteArray();
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) entries.add(e.getName());
        }
        System.err.println("TS ENTRIES " + entries);
        assertTrue(entries.contains("Api.ts"), entries.toString());
        assertTrue(entries.stream().anyMatch(n -> n.startsWith("services/")), entries.toString());
        assertTrue(entries.stream().anyMatch(n -> n.startsWith("model/dto/")), entries.toString());
    }
}
