package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import java.util.Set;

import edu.illinois.nondex.common.Configuration;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.common.Logger;
import edu.illinois.nondex.common.Level;

public class CleanExecution {

    protected Configuration configuration;
    protected final String executionId;

    private final TestExecuter<JvmTestExecutionSpec> delegate;
    protected JvmTestExecutionSpec originalSpec;
    private RetryTestResultProcessor testResultProcessor;

    protected CleanExecution(TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec originalSpec, 
            RetryTestResultProcessor testResultProcessor, String executionId, String nondexDir) {
        this.delegate = delegate;
        this.originalSpec = originalSpec;
        this.testResultProcessor = testResultProcessor;
        this.executionId = executionId;
        this.configuration = new Configuration(executionId, nondexDir); // why we need this dir?
    }

    // this is called in RetryTestExecuter
    public CleanExecution(TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec originalSpec, 
            RetryTestResultProcessor testResultProcessor, String nondexDir) {
        this(delegate, originalSpec, testResultProcessor, "clean_" + Utils.getFreshExecutionId(), nondexDir);
    }

    public Configuration getConfiguration() {
        return this.configuration;
    }

    protected void updateSpec() {
        return;
    }

    public void run() {
        updateSpec();
        Logger.getGlobal().log(Level.CONFIG, this.configuration.toString());
        delegate.execute(originalSpec, testResultProcessor);
        RoundResult result = testResultProcessor.getResult();
        if (result.failedTests.isEmpty()) System.out.println("no test fail in this run");
        else {
            Logger.getGlobal().log(Level.INFO, "Failed when running tests for " + this.configuration.executionId);
            // Set<String> failingTests = result.failedTests.map.entrySet();
            // this.configuration.setFailures(failingTests);
        }
        // throw exception if fail or anything
    }
}