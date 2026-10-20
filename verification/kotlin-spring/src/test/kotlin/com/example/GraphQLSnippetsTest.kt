package com.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.GraphQlTester

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:kgraphql;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true"])
@AutoConfigureGraphQlTester
class GraphQLSnippetsTest {

    @Autowired lateinit var graphQlTester: GraphQlTester

    @Test
    fun queryWithFetcherFromEnvironment() {
        graphQlTester.document("""{ books(name: "graphql") { id name store { name } authors { firstName } } }""")
            .execute()
            .path("books[0].name").entity(String::class.java).isEqualTo("GraphQL in Action")
            .path("books[0].store.name").entity(String::class.java).isEqualTo("MANNING")
            .path("books[0].authors[0].firstName").entity(String::class.java).isEqualTo("Samer")
    }

    @Test
    fun associationsOfOrphanObjects() {
        val storeNames = graphQlTester.document("{ orphanBooks { name store { name books { name } } } }")
            .execute()
            .path("orphanBooks[*].store.name").entityList(String::class.java).get()
        assertEquals(listOf("O'REILLY", "O'REILLY", "MANNING"), storeNames)
    }

    @Test
    fun mutationWithInputDto() {
        graphQlTester.document("""mutation { saveBook(input: {name: "GQL", edition: 1, price: 9.5, storeId: 2, authorIds: [3]}) { id name } }""")
            .execute()
            .path("saveBook.name").entity(String::class.java).isEqualTo("GQL")
    }
}
