package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;

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
        this.delegate.execute(this.originalSpec, this.testResultProcessor);
        return this.testResultProcessor;
    }
}
