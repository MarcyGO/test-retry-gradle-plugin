package org.gradle.plugin

import org.gradle.plugin.tasks.NonDexTest

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.testing.Test;

import javax.inject.Inject;

import static org.gradle.testretry.internal.config.TestTaskConfigurer.configureTestTask;

public class TestRetryPlugin implements Plugin<Project> {
    
    private final ObjectFactory objectFactory;
    private final ProviderFactory providerFactory;

    @Inject
    TestRetryPlugin(ObjectFactory objectFactory, ProviderFactory providerFactory) {
        this.objectFactory = objectFactory;
        this.providerFactory = providerFactory;
    }

    @Override
    public void apply(Project project) {
        if (pluginAlreadyApplied(project)) {
            return;
        }

        project.getTasks()
            .withType(Test.class)
            .configureEach(task -> configureTestTask(task, objectFactory, providerFactory));

        project.getTasks().create(NonDexTest.NAME, NonDexTest).init(this.objectFactory, this.providerFactory);
    }

    private static boolean pluginAlreadyApplied(Project project) {
        return project.getPlugins().stream().anyMatch(plugin -> plugin.getClass().getName().equals(TestRetryPlugin.class.getName()));
    }
}
