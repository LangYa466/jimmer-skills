package com.example.runtime;

import com.example.model.BookDraft;
import com.example.model.Book;
import com.example.model.BookProps;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.sql.DraftInterceptor;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.runtime.Customizer;
import org.babyfish.jimmer.sql.runtime.Initializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class Hooks {

    public static final AtomicInteger CUSTOMIZED = new AtomicInteger();
    public static final AtomicInteger INITIALIZED = new AtomicInteger();

    @Bean
    public Customizer jimmerCustomizer() {
        return new Customizer() {
            @Override
            public void customize(JSqlClient.Builder builder) {
                builder.setDefaultBatchSize(64);
                CUSTOMIZED.incrementAndGet();
            }
        };
    }

    @Bean
    public Initializer jimmerInitializer() {
        return new Initializer() {
            @Override
            public void initialize(JSqlClient sqlClient) {
                INITIALIZED.incrementAndGet();
            }
        };
    }

    @Bean
    public DraftInterceptor<Book, BookDraft> bookInterceptor() {
        return new DraftInterceptor<Book, BookDraft>() {
            @Override
            public void beforeSave(@NotNull BookDraft draft, @Nullable Book original) {
                if (!ImmutableObjects.isLoaded(draft, BookProps.MODIFIED_TIME)) {
                    draft.setModifiedTime(LocalDateTime.now());
                }
            }
        };
    }
}
