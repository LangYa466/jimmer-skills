package com.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:graphql;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true")
@AutoConfigureGraphQlTester
public class GraphQLSnippetsTest {

    @Autowired GraphQlTester graphQlTester;

    @Test
    public void queryWithFetcherFromEnvironment() {
        graphQlTester.document("{ books(name: \"graphql\") { id name store { name } authors { firstName } } }")
                .execute()
                .path("books[0].name").entity(String.class).isEqualTo("GraphQL in Action")
                .path("books[0].store.name").entity(String.class).isEqualTo("MANNING")
                .path("books[0].authors[0].firstName").entity(String.class).isEqualTo("Samer");
    }

    @Test
    public void associationsOfOrphanObjects() {
        List<String> storeNames = graphQlTester.document("{ orphanBooks { name store { name books { name } } } }")
                .execute()
                .path("orphanBooks[*].store.name").entityList(String.class).get();
        assertEquals(List.of("O'REILLY", "O'REILLY", "MANNING"), storeNames);
    }

    @Test
    public void mutationWithInputDto() {
        graphQlTester.document("mutation { saveBook(input: {name: \"GQL\", edition: 1, price: 9.5, storeId: 2, authorIds: [3]}) { id name } }")
                .execute()
                .path("saveBook.name").entity(String.class).isEqualTo("GQL");
    }
}
