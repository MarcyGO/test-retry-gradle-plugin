package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;

import java.util.Set;

public class CleanExecution {

    private final TestExecuter<JvmTestExecutionSpec> delegate;
    protected JvmTestExecutionSpec originalSpec;
    private RetryTestResultProcessor testResultProcessor;

    public CleanExecution(TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec originalSpec, 
            RetryTestResultProcessor testResultProcessor) {
        this.delegate = delegate;
        this.originalSpec = originalSpec;
        this.testResultProcessor = testResultProcessor;
    }

    public RetryTestResultProcessor run() {
        delegate.execute(this.originalSpec, this.testResultProcessor);
        RoundResult result = testResultProcessor.getResult();
        if (!result.failedTests.isEmpty()) {
            System.out.println("Failed when running tests");
        }
        return this.testResultProcessor;
    }
}
