<<<<<<< HEAD

=======
>>>>>>> 750be90 (some working stuffs)
package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;

import edu.illinois.nondex.common.Configuration;
import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.common.Logger;
import java.util.regex.Pattern;

public class NonDexExecution extends CleanExecution {
    // constructors
    private NonDexExecution(TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec spec, 
            RetryTestResultProcessor testResultProcessor, String nondexDir) {
        super(delegate, spec, testResultProcessor, Utils.getFreshExecutionId(), nondexDir);
    }

    // this is called in RetryTestExecuter
    public NonDexExecution(int seed, TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec originalSpec, 
            RetryTestResultProcessor testResultProcessor, String nondexDir) {
        this(delegate, originalSpec, testResultProcessor, nondexDir);
        // temperarily put default values here
        // eventually they comes in as parameters of the constructer, which are from argline properties
        this.configuration = new Configuration(ConfigurationDefaults.DEFAULT_MODE, seed, Pattern.compile(ConfigurationDefaults.DEFAULT_FILTER), 
            ConfigurationDefaults.DEFAULT_START, ConfigurationDefaults.DEFAULT_END, nondexDir, nondexDir, null,
            this.executionId, Logger.getGlobal().getLoggingLevel());
    }
}