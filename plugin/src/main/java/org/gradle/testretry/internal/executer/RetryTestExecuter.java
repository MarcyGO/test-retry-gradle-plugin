/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.testretry.internal.config.TestRetryTaskExtensionAdapter;
import org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy;
import org.gradle.testretry.internal.filter.AnnotationInspectorImpl;
import org.gradle.testretry.internal.filter.RetryFilter;
import org.gradle.api.internal.artifacts.mvnsettings.DefaultMavenFileLocations;
import org.gradle.process.JavaForkOptions;

import java.io.File;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.nio.file.Paths;

import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Level;
import edu.illinois.nondex.common.Logger;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.instr.Main;

import static org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy.gradleVersionIsAtLeast;

public final class RetryTestExecuter implements TestExecuter<JvmTestExecutionSpec> {

    private final TestRetryTaskExtensionAdapter extension;
    private final TestExecuter<JvmTestExecutionSpec> delegate;
    private final Test testTask;
    private final TestFrameworkTemplate frameworkTemplate;

    protected int numRuns;
    protected int seed;

    private RoundResult lastResult;

    public RetryTestExecuter(
        Test task,
        TestRetryTaskExtensionAdapter extension,
        TestExecuter<JvmTestExecutionSpec> delegate,
        Instantiator instantiator,
        ObjectFactory objectFactory,
        Set<File> testClassesDir,
        Set<File> resolvedClasspath
    ) {
        this.numRuns = new Integer(System.getProperty(ConfigurationDefaults.PROPERTY_NUM_RUNS, ConfigurationDefaults.DEFAULT_NUM_RUNS_STR));
        this.seed = new Integer(System.getProperty(ConfigurationDefaults.PROPERTY_SEED, ConfigurationDefaults.DEFAULT_SEED_STR));
        this.extension = extension;
        this.delegate = delegate;
        this.testTask = task;
        this.frameworkTemplate = new TestFrameworkTemplate(
            testTask,
            instantiator,
            objectFactory,
            testClassesDir,
            resolvedClasspath
        );
    }

    @Override
    public void execute(JvmTestExecutionSpec spec, TestResultProcessor testResultProcessor) {
        String outPath = System.getProperty("user.dir")+ File.separator
                + ConfigurationDefaults.DEFAULT_NONDEX_JAR_DIR + File.separator
                + ConfigurationDefaults.INSTRUMENTATION_JAR;
        try {
            File fileForJar = Paths.get(System.getProperty("user.dir"), 
                    ConfigurationDefaults.DEFAULT_NONDEX_JAR_DIR).toFile();
            fileForJar.mkdirs();
            Main.main(Paths.get(fileForJar.getAbsolutePath(),
                    ConfigurationDefaults.INSTRUMENTATION_JAR).toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

        int maxRetries = this.numRuns;
        int maxFailures = extension.getMaxFailures();
        boolean failOnPassedAfterRetry = extension.getFailOnPassedAfterRetry();

        if (maxRetries <= 0) {
            delegate.execute(spec, testResultProcessor);
            return;
        }

        TestFrameworkStrategy testFrameworkStrategy = TestFrameworkStrategy.of(spec.getTestFramework());

        RetryFilter filter = new RetryFilter(
            new AnnotationInspectorImpl(frameworkTemplate.testsReader),
            extension.getIncludeClasses(),
            extension.getIncludeAnnotationClasses(),
            extension.getExcludeClasses(),
            extension.getExcludeAnnotationClasses()
        );

        RetryTestResultProcessor retryTestResultProcessor = new RetryTestResultProcessor(
            testFrameworkStrategy,
            filter,
            frameworkTemplate.testsReader,
            testResultProcessor,
            maxFailures
        );

        int retryCount = 0;
        JvmTestExecutionSpec testExecutionSpec = spec;

        while (true) {
            Logger.getGlobal().log(Level.INFO, "retryCount = " + retryCount);
            delegate.execute(testExecutionSpec, retryTestResultProcessor);
            RoundResult result = retryTestResultProcessor.getResult();
            lastResult = result;

            if (extension.getSimulateNotRetryableTest() || !result.nonRetriedTests.isEmpty()) {
                // fall through to our doLast action to fail accordingly
                testTask.setIgnoreFailures(true);
                break;
            } else if (result.lastRound) {
                break;
            } else {
                testExecutionSpec = createRetryJvmExecutionSpec(retryCount, spec);
                retryTestResultProcessor.reset(++retryCount == maxRetries);
            }
        }
    }

    public void failWithNonRetriedTestsIfAny() {
        if (extension.getSimulateNotRetryableTest() || hasNonRetriedTests()) {
            throw new IllegalStateException("org.gradle.test-retry was unable to retry the following test methods, which is unexpected. Please file a bug report at https://github.com/gradle/test-retry-gradle-plugin/issues" +
                lastResult.nonRetriedTests.stream()
                    .flatMap(entry -> entry.getValue().stream().map(methodName -> "   " + entry.getKey() + "#" + methodName))
                    .collect(Collectors.joining("\n", "\n", "\n")));
        }
    }

    private boolean hasNonRetriedTests() {
        return lastResult != null && !lastResult.nonRetriedTests.isEmpty();
    }

    private JvmTestExecutionSpec createRetryJvmExecutionSpec(int i, JvmTestExecutionSpec spec) {
        JavaForkOptions option = spec.getJavaForkOptions();
        List<String> arg = this.setupArgline(i);
        option.setJvmArgs(arg);
        if (gradleVersionIsAtLeast("6.4")) {
            // This constructor is in Gradle 6.4+
            return new JvmTestExecutionSpec(
                spec.getTestFramework(),
                spec.getClasspath(),
                spec.getModulePath(),
                spec.getCandidateClassFiles(),
                spec.isScanForTestClasses(),
                spec.getTestClassesDirs(),
                spec.getPath(),
                spec.getIdentityPath(),
                spec.getForkEvery(),
                option,
                spec.getMaxParallelForks(),
                spec.getPreviousFailedTestClasses()
            );
        } else {
            // This constructor is in Gradle 4.7+
            return new JvmTestExecutionSpec(
                spec.getTestFramework(),
                spec.getClasspath(),
                spec.getCandidateClassFiles(),
                spec.isScanForTestClasses(),
                spec.getTestClassesDirs(),
                spec.getPath(),
                spec.getIdentityPath(),
                spec.getForkEvery(),
                option,
                spec.getMaxParallelForks(),
                spec.getPreviousFailedTestClasses()
            );
        }
    }

    @Override
    public void stopNow() {
        delegate.stopNow();
    }

    private String getPathToNondexJar() {
        // to do: use default name of instr jar; nondexjar should be get from configuration; instr jar should be in .nondex
        String outPath = System.getProperty("user.dir")+ File.separator
            + ConfigurationDefaults.DEFAULT_NONDEX_JAR_DIR + File.separator
            + ConfigurationDefaults.INSTRUMENTATION_JAR;
        DefaultMavenFileLocations loc = new DefaultMavenFileLocations();
        File mvnLoc = loc.getUserMavenDir();
        String result = outPath + File.pathSeparator + Paths.get(mvnLoc.toString(), 
            "repository", "edu", "illinois", "nondex-common", ConfigurationDefaults.VERSION,
            "nondex-common-" + ConfigurationDefaults.VERSION + ".jar");
        return result;
    }

    private List<String> setupArgline(int i) {
        String pathToNondex = getPathToNondexJar();
        List<String> arg = new ArrayList();
        if (!Utils.checkJDKBefore8()) {
            arg.add("--patch-module=java.base=" + pathToNondex);
            arg.add("--add-exports=java.base/edu.illinois.nondex.common=ALL-UNNAMED");
            arg.add("--add-exports=java.base/edu.illinois.nondex.shuffling=ALL-UNNAMED");
        } else {
            arg.add("-Xbootclasspath/p:" + pathToNondex);
        }
        // to do: use configuration
        arg.add("-D" + ConfigurationDefaults.PROPERTY_EXECUTION_ID + "=" + Utils.getFreshExecutionId());
        arg.add("-D" + ConfigurationDefaults.PROPERTY_SEED + "=" + this.computeIthSeed(i));
        return arg;
    }

    private int computeIthSeed(int ithSeed) {
        return Utils.computeIthSeed(ithSeed, false, this.seed); // hardcode rerun to false
    }
}
