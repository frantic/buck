/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.Tool;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AppleTest extends NoopBuildRule implements TestRule, HasRuntimeDeps {

  @AddToRuleKey
  private final Optional<Path> xctoolPath;

  @AddToRuleKey
  private final Tool xctest;

  @AddToRuleKey
  private final Tool otest;

  @AddToRuleKey
  private final boolean useXctest;

  @AddToRuleKey
  private final String platformName;

  @AddToRuleKey
  private final Optional<SourcePath> xctoolZipPath;

  @AddToRuleKey
  private final Optional<String> simulatorName;

  @AddToRuleKey
  private final BuildRule testBundle;

  @AddToRuleKey
  private final Optional<AppleBundle> testHostApp;

  private final ImmutableSet<String> contacts;
  private final ImmutableSet<Label> labels;

  private final Path testBundleDirectory;
  private final Path testHostAppDirectory;
  private final Path xctoolUnzipDirectory;
  private final Path testOutputPath;

  private final String testBundleExtension;

  AppleTest(
      Optional<Path> xctoolPath,
      Optional<SourcePath> xctoolZipPath,
      Tool xctest,
      Tool otest,
      Boolean useXctest,
      String platformName,
      Optional<String> simulatorName,
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRule testBundle,
      Optional<AppleBundle> testHostApp,
      String testBundleExtension,
      ImmutableSet<String> contacts,
      ImmutableSet<Label> labels) {
    super(params, resolver);
    this.xctoolPath = xctoolPath;
    this.xctoolZipPath = xctoolZipPath;
    this.useXctest = useXctest;
    this.xctest = xctest;
    this.otest = otest;
    this.platformName = platformName;
    this.simulatorName = simulatorName;
    this.testBundle = testBundle;
    this.testHostApp = testHostApp;
    this.contacts = contacts;
    this.labels = labels;
    this.testBundleExtension = testBundleExtension;
    // xctool requires the extension to be present to determine whether the test is ocunit or xctest
    this.testBundleDirectory = BuildTargets.getScratchPath(
        params.getBuildTarget(),
        "__test_bundle_%s__." + testBundleExtension);
    this.testHostAppDirectory = BuildTargets.getScratchPath(
        params.getBuildTarget(),
        "__test_host_app_%s__.app");
    this.xctoolUnzipDirectory = BuildTargets.getScratchPath(
        params.getBuildTarget(),
        "__xctool_%s__");
    this.testOutputPath = getPathToTestOutputDirectory().resolve("test-output.json");
  }

  /**
   * Returns the test bundle to run.
   */
  public BuildRule getTestBundle() {
    return testBundle;
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    // Apple tests always express a rule -> test dependency, not the other way
    // around.
    return ImmutableSet.of();
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return executionContext.getProjectFilesystem().exists(testOutputPath);
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path resolvedTestBundleDirectory = executionContext.getProjectFilesystem().resolve(
        testBundleDirectory);
    steps.add(new MakeCleanDirectoryStep(resolvedTestBundleDirectory));
    steps.add(new UnzipStep(testBundle.getPathToOutputFile(), resolvedTestBundleDirectory));

    Path pathToTestOutput = executionContext.getProjectFilesystem().resolve(
        getPathToTestOutputDirectory());
    steps.add(new MakeCleanDirectoryStep(pathToTestOutput));

    Path resolvedTestOutputPath = executionContext.getProjectFilesystem().resolve(
        testOutputPath);

    Optional<Path> testHostAppPath = Optional.absent();
    if (testHostApp.isPresent()) {
      Path resolvedTestHostAppDirectory = executionContext.getProjectFilesystem().resolve(
          testHostAppDirectory);
      steps.add(new MakeCleanDirectoryStep(resolvedTestHostAppDirectory));
      steps.add(
          new UnzipStep(testHostApp.get().getPathToOutputFile(), resolvedTestHostAppDirectory));

      testHostAppPath = Optional.of(resolvedTestHostAppDirectory.resolve(
          testHostApp.get().getUnzippedOutputFilePathToBinary()));
    }

    if (!useXctest) {
      if (!xctoolPath.isPresent() && !xctoolZipPath.isPresent()) {
        throw new HumanReadableException(
            "Set xctool_path = /path/to/xctool or xctool_zip_target = //path/to:xctool-zip " +
            "in the [apple] section of .buckconfig to run this test");
      }

      ImmutableSet.Builder<Path> logicTestPathsBuilder = ImmutableSet.builder();
      ImmutableMap.Builder<Path, Path> appTestPathsToHostAppsBuilder = ImmutableMap.builder();

      if (testHostAppPath.isPresent()) {
        appTestPathsToHostAppsBuilder.put(
            resolvedTestBundleDirectory,
            testHostAppPath.get());
      } else {
        logicTestPathsBuilder.add(resolvedTestBundleDirectory);
      }

      Path xctoolBinaryPath;
      if (xctoolZipPath.isPresent()) {
        Path resolvedXctoolUnzipDirectory = executionContext.getProjectFilesystem().resolve(
            xctoolUnzipDirectory);
        steps.add(new MakeCleanDirectoryStep(resolvedXctoolUnzipDirectory));
        steps.add(
            new UnzipStep(
                getResolver().getPath(xctoolZipPath.get()),
                resolvedXctoolUnzipDirectory));
        xctoolBinaryPath = resolvedXctoolUnzipDirectory.resolve("bin/xctool");
      } else {
        xctoolBinaryPath = xctoolPath.get();
      }

      steps.add(
          new XctoolRunTestsStep(
              xctoolBinaryPath,
              platformName,
              simulatorName,
              logicTestPathsBuilder.build(),
              appTestPathsToHostAppsBuilder.build(),
              resolvedTestOutputPath));
    } else {
      steps.add(
          new XctestRunTestsStep(
              (testBundleExtension == "xctest" ? xctest : otest)
                  .getCommandPrefix(getResolver()),
              (testBundleExtension == "xctest" ? "-XCTest" : "-SenTest"),
              resolvedTestBundleDirectory,
              resolvedTestOutputPath));
    }

    return steps.build();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        Path resolvedOutputPath = executionContext.getProjectFilesystem().resolve(testOutputPath);
        try (BufferedReader reader =
            Files.newBufferedReader(resolvedOutputPath, StandardCharsets.UTF_8)) {
          return new TestResults(
            getBuildTarget(),
            useXctest ?
                XctestOutputParsing.parseOutputFromReader(reader) :
                XctoolOutputParsing.parseOutputFromReader(reader),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
        }
      }
    };
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    // TODO(user): Refactor the JavaTest implementation; this is identical.

    List<String> pathsList = new ArrayList<>();
    pathsList.add(getBuildTarget().getBaseNameWithSlash());
    pathsList.add(
        String.format("__apple_test_%s_output__", getBuildTarget().getShortNameAndFlavorPostfix()));

    // Putting the one-time test-sub-directory below the usual directory has the nice property that
    // doing a test run without "--one-time-output" will tidy up all the old one-time directories!
    String subdir = BuckConstant.oneTimeTestSubdirectory;
    if (subdir != null && !subdir.isEmpty()) {
      pathsList.add(subdir);
    }

    String[] pathsArray = pathsList.toArray(new String[pathsList.size()]);
    return Paths.get(BuckConstant.GEN_DIR, pathsArray);
  }

  @Override
  public boolean runTestSeparately() {
    // Tests which run in the simulator must run separately from all other tests;
    // there's a 20 second timeout hard-coded in the iOS Simulator SpringBoard which
    // is hit any time the host is overloaded.
    return testHostApp.isPresent();
  }

  // This test rule just executes the test bundle, so we need it available locally.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(testBundle)
        .addAll(testHostApp.asSet())
        .build();
  }

}
