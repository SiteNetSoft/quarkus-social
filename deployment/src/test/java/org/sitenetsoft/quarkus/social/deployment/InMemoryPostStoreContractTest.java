package org.sitenetsoft.quarkus.social.deployment;

import jakarta.inject.Inject;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.test.SocialPostStoreContractTest;

import io.quarkus.test.QuarkusExtensionTest;

/** The store contract against the default in-memory store. */
public class InMemoryPostStoreContractTest extends SocialPostStoreContractTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest();

    @Inject
    SocialPostStore store;

    @Override
    protected SocialPostStore store() {
        return store;
    }
}
