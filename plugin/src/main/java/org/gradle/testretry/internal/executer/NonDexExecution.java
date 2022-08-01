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

    /*
     TODO: currently, no original argline. Don't have to deal with other argline in addition to nondex stuffs
     the name of the below method may need to change to create spec or something else
    */
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
        // TODO: include configuration; can I use toArgline method directly?
        arg.add("-D" + ConfigurationDefaults.PROPERTY_EXECUTION_ID + "=" + this.configuration.executionId);
        arg.add("-D" + ConfigurationDefaults.PROPERTY_SEED + "=" + this.configuration.seed);
        return arg;
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
}