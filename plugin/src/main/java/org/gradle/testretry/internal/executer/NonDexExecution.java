package org.gradle.testretry.internal.executer;

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.artifacts.mvnsettings.DefaultMavenFileLocations;
import org.gradle.process.JavaForkOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Paths;

import edu.illinois.nondex.common.Configuration;
import edu.illinois.nondex.common.ConfigurationDefaults;
import edu.illinois.nondex.common.Utils;
import edu.illinois.nondex.common.Logger;
import java.util.regex.Pattern;

import static org.gradle.testretry.internal.executer.framework.TestFrameworkStrategy.gradleVersionIsAtLeast;

public class NonDexExecution extends CleanExecution {
    private NonDexExecution(TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec spec, 
            RetryTestResultProcessor testResultProcessor, String nondexDir) {
        super(delegate, spec, testResultProcessor, Utils.getFreshExecutionId(), nondexDir);
    }

    public NonDexExecution(int seed, TestExecuter<JvmTestExecutionSpec> delegate, JvmTestExecutionSpec originalSpec, 
            RetryTestResultProcessor testResultProcessor, String nondexDir, String nondexJarDir) {
        this(delegate, originalSpec, testResultProcessor, nondexDir);
        this.configuration = new Configuration(ConfigurationDefaults.DEFAULT_MODE, seed, Pattern.compile(ConfigurationDefaults.DEFAULT_FILTER), 
                ConfigurationDefaults.DEFAULT_START, ConfigurationDefaults.DEFAULT_END, nondexDir, nondexJarDir, null,
                this.executionId, Logger.getGlobal().getLoggingLevel());
        this.originalSpec = this.createRetryJvmExecutionSpec();
    }

    private JvmTestExecutionSpec createRetryJvmExecutionSpec() {
        JvmTestExecutionSpec spec = this.originalSpec;
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

    private List<String> setupArgline() {
        String pathToNondex = getPathToNondexJar();
        List<String> arg = new ArrayList();
        if (!Utils.checkJDKBefore8()) {
            arg.add("--patch-module=java.base=" + pathToNondex);
            arg.add("--add-exports=java.base/edu.illinois.nondex.common=ALL-UNNAMED");
            arg.add("--add-exports=java.base/edu.illinois.nondex.shuffling=ALL-UNNAMED");
        } else {
            arg.add("-Xbootclasspath/p:" + pathToNondex);
        }
        arg.add("-D" + ConfigurationDefaults.PROPERTY_EXECUTION_ID + "=" + this.configuration.executionId);
        arg.add("-D" + ConfigurationDefaults.PROPERTY_SEED + "=" + this.configuration.seed);
        return arg;
    }

    private String getPathToNondexJar() {
        DefaultMavenFileLocations loc = new DefaultMavenFileLocations();
        File mvnLoc = loc.getUserMavenDir();
        String result = Paths.get(this.configuration.nondexJarDir, ConfigurationDefaults.INSTRUMENTATION_JAR) + File.pathSeparator
                + Paths.get(mvnLoc.toString(),
                "repository", "edu", "illinois", "nondex-common", ConfigurationDefaults.VERSION,
                "nondex-common-" + ConfigurationDefaults.VERSION + ".jar");
        return result;
    }
}
