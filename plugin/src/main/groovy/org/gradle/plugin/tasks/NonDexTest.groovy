package org.gradle.plugin.tasks;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;

import org.gradle.testretry.internal.config.TestTaskConfigurer;
import org.gradle.api.tasks.testing.Test;

class NonDexTest extends Test {
    static final String NAME = "nondexTest"

    void init(ObjectFactory objectFactory, ProviderFactory providerFactory) {
        setDescription("Test with NonDex")
        setGroup("NonDex")

        testLogging {
            exceptionFormat 'full'
        }

        doFirst {
            System.out.println("doFirst")
            TestTaskConfigurer.configureTestTask(this,  objectFactory, providerFactory)
            // new TestTaskConfigurer.InitTaskAction(adapter, objectFactory)
        }

        doLast {
            System.out.println("doLast")
            TestTaskConfigurer.FinalizeTaskAction()
        }
    }
}
