package org.sitenetsoft.quarkus.social.hibernate.deployment;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.hibernate.HibernateSocialPostStore;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.test.SocialPostStoreContractTest;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;

/** The store contract against the Hibernate store on H2. */
public class HibernateSocialPostStoreTest extends SocialPostStoreContractTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:social;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "drop-and-create");

    @Inject
    SocialPostStore store;

    @Override
    protected SocialPostStore store() {
        return store;
    }

    @Test
    void theHibernateStoreReplacesTheInMemoryDefault() {
        assertInstanceOf(HibernateSocialPostStore.class, ClientProxy.unwrap(store));
    }
}
