package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.process.JavaForkOptions;

import edu.illinois.nondex.common.Configuration;
import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.common.Logger;
import java.util.List;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.io.File;

import static org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy.gradleVersionIsAtLeast;

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

    /*
     currently, no original argline. Don't have to deal with other argline in addition to nondex stuffs
     the name of the below method may need to change to create spec or something else
    */

    protected List<String> setupArgline() {
        String commonPath = "/home/xinyuwu4/.m2/repository/edu/illinois/nondex-common/1.1.3-SNAPSHOT/nondex-common-1.1.3-SNAPSHOT.jar";
        String outPath = System.getProperty("user.dir") + File.separator + "out.jar";
        String args = "-Xbootclasspath/p:" + outPath + File.pathSeparator + commonPath;
        // the toArgLine method return a string, but we need a iterable here
        // to do: other parameter
        String setExecutionId = "-D" + ConfigurationDefaults.PROPERTY_EXECUTION_ID + "=" + this.executionId;
        String setSeed = "-D" + ConfigurationDefaults.PROPERTY_SEED + "=" + this.configuration.seed;
        List<String> arg = Arrays.asList(setExecutionId, args, setSeed);
        return arg;
    }

    @Override
    protected void updateSpec() {
        originalSpec = createRetryJvmExecutionSpec();
    }

    // copy the method here seems to be a bit ugly. May find another way to do it
    protected JvmTestExecutionSpec createRetryJvmExecutionSpec() {
        JvmTestExecutionSpec spec = originalSpec;
        JavaForkOptions option = spec.getJavaForkOptions();
        List<String> arg = this.setupArgline();
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

    private String getPathToNondexJar(String localRepo) {
        return "";
    }
}