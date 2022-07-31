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
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Configuration;
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

    private List<NonDexExecution> executions = new LinkedList<>();

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

        CleanExecution cleanExec = new CleanExecution(this.delegate, testExecutionSpec, retryTestResultProcessor,
            System.getProperty("user.dir")+ File.separator + ConfigurationDefaults.DEFAULT_NONDEX_DIR);
        retryTestResultProcessor = cleanExec.run();

        while (true) {
            retryTestResultProcessor.reset(++retryCount == maxRetries);
            NonDexExecution execution = new NonDexExecution(this.computeIthSeed(retryCount - 1),
                delegate, testExecutionSpec, retryTestResultProcessor,
                System.getProperty("user.dir")+ File.separator + ConfigurationDefaults.DEFAULT_NONDEX_DIR);
            this.executions.add(execution);
            retryTestResultProcessor = execution.run();
            this.writeCurrentRunInfo(execution);
            RoundResult result = retryTestResultProcessor.getResult();
            lastResult = result;

            if (extension.getSimulateNotRetryableTest() || !result.nonRetriedTests.isEmpty()) {
                // fall through to our doLast action to fail accordingly
                testTask.setIgnoreFailures(true);
                break;
            } else if (result.lastRound) {
                break;
            } 
        }
        this.writeCurrentRunInfo(cleanExec);
        this.postProcessExecutions(cleanExec);

        Configuration config = this.executions.get(0).getConfiguration();
        this.printSummary(cleanExec, config);
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

    @Override
    public void stopNow() {
        delegate.stopNow();
    }

    private int computeIthSeed(int ithSeed) {
        return Utils.computeIthSeed(ithSeed, false, this.seed); // hardcode rerun to false
    }

    private void postProcessExecutions(CleanExecution cleanExec) {
        Collection<String> failedInClean = cleanExec.getConfiguration().getFailedTests();

        for (NonDexExecution exec : this.executions) {
            exec.getConfiguration().filterTests(failedInClean);
        }
    }

    private void writeCurrentRunInfo(CleanExecution execution) {
        try {
            Files.write(this.executions.get(0).getConfiguration().getRunFilePath(),
                        (execution.getConfiguration().executionId + String.format("%n")).getBytes(),
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Logger.getGlobal().log(Level.SEVERE, "Cannot write execution id to current run file", ex);
        }
    }

    private void printSummary(CleanExecution cleanExec, Configuration config) {
        Set<String> allFailures = new LinkedHashSet<>();
        Logger.getGlobal().log(Level.INFO, "NonDex SUMMARY:");
        for (CleanExecution exec : this.executions) {
            this.printExecutionResults(allFailures, exec);
        }

        if (!cleanExec.getConfiguration().getFailedTests().isEmpty()) {
            Logger.getGlobal().log(Level.INFO, "Tests are failing without NonDex.");
            this.printExecutionResults(allFailures, cleanExec);
        }
        allFailures.removeAll(cleanExec.getConfiguration().getFailedTests());

        Logger.getGlobal().log(Level.INFO, "Across all seeds:");
        for (String test : allFailures) {
            Logger.getGlobal().log(Level.INFO, test);
        }

        this.generateHtml(allFailures, config);
    }

    private void generateHtml(Set<String> allFailures, Configuration config) {
        String head = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Test Results</title>"
                + "<style>"
                + "table { border-collapse: collapse; width: 100%; }"
                + "th { height: 50%; }"
                + "th, td { padding: 10px; text-align: left; }"
                + "tr:nth-child(even) {background-color:#f2f2f2;}"
                + ".x { color: red; font-size: 150%;}"
                + ".✓ { color: green; font-size: 150%;}"
                + "</style>"
                + "</head>";
        String html = head + "<body>" + "<table>";

        html += "<thead><tr>";
        html += "<th>Test Name</th>";
        for (int iter = 0; iter < this.executions.size(); iter++) {
            html += "<th>";
            html += "" + this.executions.get(iter).getConfiguration().seed;
            html += "</th>";
        }
        html += "</tr></thead>";
        html += "<tbody>";
        for (String failure : allFailures) {
            html += "<tr><td>" + failure + "</td>";
            for (CleanExecution exec : this.executions) {
                boolean testDidFail = false;
                for (String test : exec.getConfiguration().getFailedTests()) {
                    if (test.equals(failure)) {
                        testDidFail = true;
                    }
                }
                if (testDidFail) {
                    html += "<td class=\"x\">&#10006;</td>";
                } else {
                    html += "<td class=\"✓\">&#10004;</td>";
                }
            }
            html += "</tr>";
        }
        html += "</tbody></table></body></html>";

        File nondexDir = config.getNondexDir().toFile();
        File htmlFile = new File(nondexDir, "test_results.html");
        try {
            PrintWriter htmlPrinter = new PrintWriter(htmlFile);
            htmlPrinter.print(html);
            htmlPrinter.close();
        } catch (FileNotFoundException ex) {
            Logger.getGlobal().log(Level.INFO, "File Missing.  But that shouldn't happen...");
        }
        Logger.getGlobal().log(Level.INFO, "Test results can be found at: ");
        Logger.getGlobal().log(Level.INFO, "file://" + htmlFile.getPath());
    }

    private void printExecutionResults(Set<String> allFailures, CleanExecution exec) {
        Logger.getGlobal().log(Level.INFO, "*********");
        Logger.getGlobal().log(Level.INFO, "mvn nondex:nondex " + exec.getConfiguration().toArgLine());
        Collection<String> failedTests = exec.getConfiguration().getFailedTests();
        if (failedTests.isEmpty()) {
            Logger.getGlobal().log(Level.INFO, "No Test Failed with this configuration.");
        }
        for (String test : failedTests) {
            allFailures.add(test); // add elements in this input set? is it a reference?
            Logger.getGlobal().log(Level.WARNING, test);
        }
        Logger.getGlobal().log(Level.INFO, "*********");
    }
}
