package com.example;

import com.example.mapping.*;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.meta.MetadataStrategy;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MappingSnippetsTest {

    @Test
    public void metadata() {
        List<Class<?>> types = Arrays.asList(
                Transition.class, Shop.class, Customer.class, Student.class, Course.class, LearningLink.class,
                Client.class, Organization.class, Person.class, Message.class, MessageDelivery.class,
                UserAccount.class, Document.class, Ticket.class, Job.class, Address.class, Member.class
        );
        JSqlClient client = JSqlClient.newBuilder().setDialect(new H2Dialect()).build();
        MetadataStrategy strategy = ((JSqlClientImplementor) client).getMetadataStrategy();
        for (Class<?> type : types) {
            ImmutableType immutableType = ImmutableType.get(type);
            assertNotNull(immutableType.getIdProp(), type.getName());
            for (ImmutableProp prop : immutableType.getProps().values()) {
                prop.isNullable();
                prop.getStorage(strategy);
                prop.getTargetType();
                prop.getMappedBy();
            }
            immutableType.getTableName(strategy);
            immutableType.getSelectableProps();
        }
        assertNotNull(ImmutableType.get(Organization.class).getInheritanceInfo());
        JSqlClient sqlClient = JSqlClient.newBuilder().setDialect(new H2Dialect()).build();
        assertNotNull(sqlClient);
    }

    @Test
    public void convertersAndViews() {
        UserAccount account = UserAccountDraft.$.produce(d -> {
            d.setId(1546434604146774987L);
            d.setPhone("13512346792");
        });
        assertEquals("{\"id\":\"1546434604146774987\",\"phone\":\"135****6792\"}", account.toString());

        Student student = StudentDraft.$.produce(d -> {
            d.setId(1L);
            d.addIntoLearningLinks(link -> link.setId(10L).applyCourse(c -> c.setId(100L).setName("Math")));
        });
        assertEquals("Math", student.courses().get(0).name());

        MessageDelivery delivery = MessageDeliveryDraft.$.produce(d -> {
            d.setMessageId(101L);
            d.setStatus("READ");
            d.applyMessage(m -> m.setId(101L).setText("Hi"));
        });
        assertEquals(101L, delivery.message().id());

        Organization org = OrganizationDraft.$.produce(d -> d.setId(1L).setName("Acme").setTaxCode("X"));
        assertNull(ImmutableObjects.isLoaded(org, "type") ? "loaded" : null);
        assertEquals("ORG", ImmutableObjects.getDiscriminator(org));

        MemberTable member = MemberTable.$;
        assertNotNull(member.fullName()); // SQL formula is part of DSL
    }
}
