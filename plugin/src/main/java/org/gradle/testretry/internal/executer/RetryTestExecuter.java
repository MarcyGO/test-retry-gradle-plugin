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
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.testretry.internal.config.TestRetryTaskExtensionAdapter;
import org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy;
import org.gradle.testretry.internal.filter.AnnotationInspectorImpl;
import org.gradle.testretry.internal.filter.RetryFilter;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Collection;
import org.gradle.process.JavaForkOptions;

import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Configuration;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.common.Level;
import edu.illinois.nondex.common.Logger;
import edu.illinois.nondex.instr.Main;

import static org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy.gradleVersionIsAtLeast;

public final class RetryTestExecuter implements TestExecuter<JvmTestExecutionSpec> {

    private final TestRetryTaskExtensionAdapter extension;
    private final TestExecuter<JvmTestExecutionSpec> delegate;
    private final Test testTask;
    private final TestFrameworkTemplate frameworkTemplate;

    // counterpart for parameters in AbstractNonDexMojo; interfacing with argline
    protected int numRuns; // as int or string??
    protected int seed;
    // baseDir?
    // maybe I should put a configuration type here? no

    private List<NonDexExecution> executions = new LinkedList<>();

    private RoundResult lastResult;

    public RetryTestExecuter(
        Test task,
        TestRetryTaskExtensionAdapter extension,
        // there is a TestExecuter inside RetryTestExecuter
        // TestExecuter is an interface
        TestExecuter<JvmTestExecutionSpec> delegate,
        Instantiator instantiator,
        ObjectFactory objectFactory,
        Set<File> testClassesDir,
        Set<File> resolvedClasspath
    ) {
        // read from argline
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
    // executeTests() > TestExecuter.execute()
    public void execute(JvmTestExecutionSpec spec, TestResultProcessor testResultProcessor) {
        int maxRetries = extension.getMaxRetries();     // TestRetryTaskExtensionAdapter configuration
        int maxFailures = extension.getMaxFailures();
        boolean failOnPassedAfterRetry = extension.getFailOnPassedAfterRetry();

        // execute the test once, no retry
        if (maxRetries <= 0) {
            delegate.execute(spec, testResultProcessor);    // TestExecuter<JvmTestExecutionSpec> Test also has DefaultTestExecuter
            return;
        }

        // the current
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
        // original spec
        JvmTestExecutionSpec testExecutionSpec = spec;
        System.out.println("begin the for loop");
        CleanExecution cleanExec = new CleanExecution(delegate, spec, retryTestResultProcessor, 
            System.getProperty("user.dir")+ File.separator + ConfigurationDefaults.DEFAULT_NONDEX_DIR);
        cleanExec.run();

        for (int i = 0; i < numRuns; i++) {
            // put argline parameters into the constructer
            NonDexExecution execution = new NonDexExecution(computeIthSeed(i),
                delegate, spec, retryTestResultProcessor, 
                System.getProperty("user.dir")+ File.separator + ConfigurationDefaults.DEFAULT_NONDEX_DIR);
            this.executions.add(execution);
            execution.run();
        }
        // int i = 0;
        // while (i<numRuns) {
        //     System.out.println("execute the test " + i);
        //     delegate.execute(testExecutionSpec, retryTestResultProcessor);
            
        //     RoundResult result = retryTestResultProcessor.getResult();
        //     lastResult = result;
        //     if (result.failedTests.isEmpty()) System.out.println("no test fail in this run");
        //     System.out.println("\n \n");
        //     // something like "5 tests completed, 3 failed" is printed at the end before the following
        //     if (extension.getSimulateNotRetryableTest() || !result.nonRetriedTests.isEmpty()) {
        //         // fall through to our doLast action to fail accordingly
        //         testTask.setIgnoreFailures(true);
        //         break;
        //     // delete some condition check so it will run test even if it pass at the first time
        //     } else {
        //         testExecutionSpec = createRetryJvmExecutionSpec(i, this.seed, spec);
        //         // what is this processor?
        //         retryTestResultProcessor.reset(++retryCount == maxRetries);
        //         i++;
        //     }
        // }
        // to do: postProcessExecutions: filter these fail in clean execution
        // to do: printSummary
        this.postProcessExecutions(cleanExec);
        Configuration config = this.executions.get(0).getConfiguration();
        this.printSummary(cleanExec, config);
    }

    private void postProcessExecutions(CleanExecution cleanExec) {
        Collection<String> failedInClean = cleanExec.getConfiguration().getFailedTests();
        for (NonDexExecution exec : this.executions) {
            exec.getConfiguration().filterTests(failedInClean);
        }
    }

    private void printSummary(CleanExecution cleanExec, Configuration config) {
        Set<String> allFailures = new LinkedHashSet<>();
        Logger.getGlobal().log(Level.INFO, "NonDex SUMMARY:");  // what is this log?
        for (CleanExecution exec : this.executions) {
            // this.printExecutionResults(allFailures, exec);
        }

        if (!cleanExec.getConfiguration().getFailedTests().isEmpty()) {
            Logger.getGlobal().log(Level.INFO, "Tests are failing without NonDex.");
            // this.printExecutionResults(allFailures, cleanExec);
        }
        allFailures.removeAll(cleanExec.getConfiguration().getFailedTests());

        Logger.getGlobal().log(Level.INFO, "Across all seeds:");
        for (String test : allFailures) {
            Logger.getGlobal().log(Level.INFO, test);
        }
    }

    private int computeIthSeed(int ithSeed) {
        return Utils.computeIthSeed(ithSeed, false, this.seed); // hardcode rerun to false
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

    public JvmTestExecutionSpec createRetryJvmExecutionSpec(int i, int seed, JvmTestExecutionSpec spec) {
        // construct a same spec, only change the framework
        // why we need to change the framework for each run?
        // what is changed inside?
        // to do: use a method to find the path
        String commonPath = "/home/xinyuwu4/.m2/repository/edu/illinois/nondex-common/1.1.3-SNAPSHOT/nondex-common-1.1.3-SNAPSHOT.jar";
        String outPath = System.getProperty("user.dir") + File.separator + "out.jar";
        String args = "-Xbootclasspath/p:" + outPath + File.pathSeparator + commonPath;
        // maybe I can put this somewhere inside
        // set jvmArgs at test execution?
        String setExecutionId = "-D" + ConfigurationDefaults.PROPERTY_EXECUTION_ID + "=" + Utils.getFreshExecutionId();
        String setSeed = "-D" + ConfigurationDefaults.PROPERTY_SEED + "=" + Utils.computeIthSeed(i,false,seed);

        JavaForkOptions option = spec.getJavaForkOptions();
        List<String> arg = Arrays.asList(setExecutionId, args, setSeed);
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
}
