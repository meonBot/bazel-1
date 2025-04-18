// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.TotalAndConfiguredTargetOnlyMetric;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationResolver.TopLevelTargetsAndConfigsResult;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.constraints.PlatformRestrictionsResult;
import com.google.devtools.build.lib.analysis.constraints.RuleContextConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory.CoverageReportActionsWrapper;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.pkgcache.PackageManager.PackageManagerStatistics;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns.Code;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.CoverageReportValue;
import com.google.devtools.build.lib.skyframe.PrepareAnalysisPhaseValue;
import com.google.devtools.build.lib.skyframe.SkyframeAnalysisAndExecutionResult;
import com.google.devtools.build.lib.skyframe.SkyframeAnalysisResult;
import com.google.devtools.build.lib.skyframe.SkyframeBuildView;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * The BuildView presents a semantically-consistent and transitively-closed dependency graph for
 * some set of packages.
 *
 * <h2>Package design</h2>
 *
 * <p>This package contains the Blaze dependency analysis framework (aka "analysis phase"). The goal
 * of this code is to perform semantic analysis of all of the build targets required for a given
 * build, to report errors/warnings for any problems in the input, and to construct an "action
 * graph" (see {@code lib.actions} package) correctly representing the work to be done during the
 * execution phase of the build.
 *
 * <p><b>Configurations</b> the inputs to a build come from two sources: the intrinsic inputs,
 * specified in the BUILD file, are called <em>targets</em>. The environmental inputs, coming from
 * the build tool, the command-line, or configuration files, are called the <em>configuration</em>.
 * Only when a target and a configuration are combined is there sufficient information to perform a
 * build.
 *
 * <p>Targets are implemented by the {@link Target} hierarchy in the {@code lib.packages} code.
 * Configurations are implemented by {@link BuildConfigurationValue}. The pair of these together is
 * represented by an instance of class {@link ConfiguredTarget}; this is the root of a hierarchy
 * with different implementations for each kind of target: source file, derived file, rules, etc.
 *
 * <p>The framework code in this package (as opposed to its subpackages) is responsible for
 * constructing the {@code ConfiguredTarget} graph for a given target and configuration, taking care
 * of such issues as:
 *
 * <ul>
 *   <li>caching common subgraphs.
 *   <li>detecting and reporting cycles.
 *   <li>correct propagation of errors through the graph.
 *   <li>reporting universal errors, such as dependencies from production code to tests, or to
 *       experimental branches.
 *   <li>capturing and replaying errors.
 *   <li>maintaining the graph from one build to the next to avoid unnecessary recomputation.
 *   <li>checking software licenses.
 * </ul>
 *
 * <p>See also {@link ConfiguredTarget} which documents some important invariants.
 */
public class BuildView {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final BlazeDirectories directories;

  private final SkyframeExecutor skyframeExecutor;
  private final SkyframeBuildView skyframeBuildView;

  private final ConfiguredRuleClassProvider ruleClassProvider;

  /** A factory class to create the coverage report action. May be null. */
  @Nullable private final CoverageReportActionFactory coverageReportActionFactory;

  public BuildView(
      BlazeDirectories directories,
      ConfiguredRuleClassProvider ruleClassProvider,
      SkyframeExecutor skyframeExecutor,
      CoverageReportActionFactory coverageReportActionFactory) {
    this.directories = directories;
    this.coverageReportActionFactory = coverageReportActionFactory;
    this.ruleClassProvider = ruleClassProvider;
    this.skyframeExecutor = Preconditions.checkNotNull(skyframeExecutor);
    this.skyframeBuildView = skyframeExecutor.getSkyframeBuildView();
  }

  /** Returns the number of analyzed targets/aspects. */
  public TotalAndConfiguredTargetOnlyMetric getEvaluatedCounts() {
    return skyframeBuildView.getEvaluatedCounts();
  }

  public TotalAndConfiguredTargetOnlyMetric getEvaluatedActionsCounts() {
    return skyframeBuildView.getEvaluatedActionCounts();
  }

  public PackageManagerStatistics getAndClearPkgManagerStatistics() {
    return skyframeExecutor.getPackageManager().getAndClearStatistics();
  }

  private ArtifactFactory getArtifactFactory() {
    return skyframeBuildView.getArtifactFactory();
  }

  /** Returns the collection of configured targets corresponding to any of the provided targets. */
  @VisibleForTesting
  static LinkedHashSet<ConfiguredTarget> filterTestsByTargets(
      Collection<ConfiguredTarget> targets, Set<Label> allowedTargetLabels) {
    return targets.stream()
        .filter(ct -> allowedTargetLabels.contains(ct.getLabel()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @ThreadCompatible
  public AnalysisResult update(
      TargetPatternPhaseValue loadingResult,
      BuildOptions targetOptions,
      Set<String> multiCpu,
      ImmutableSet<String> explicitTargetPatterns,
      List<String> aspects,
      ImmutableMap<String, String> aspectsParameters,
      AnalysisOptions viewOptions,
      boolean keepGoing,
      boolean checkForActionConflicts,
      int loadingPhaseThreads,
      TopLevelArtifactContext topLevelOptions,
      boolean reportIncompatibleTargets,
      ExtendedEventHandler eventHandler,
      EventBus eventBus,
      BugReporter bugReporter,
      boolean includeExecutionPhase,
      int mergedPhasesExecutionJobsCount)
      throws ViewCreationFailedException, InvalidConfigurationException, InterruptedException,
          BuildFailedException, TestExecException {
    logger.atInfo().log("Starting analysis");
    pollInterruptedStatus();

    skyframeBuildView.resetProgressReceiver();
    skyframeExecutor.setBaselineConfiguration(targetOptions);

    ImmutableMap.Builder<Label, Target> labelToTargetsMapBuilder =
        ImmutableMap.builderWithExpectedSize(loadingResult.getTargetLabels().size());
    loadingResult
        .getTargets(eventHandler, skyframeExecutor.getPackageManager())
        .forEach(target -> labelToTargetsMapBuilder.put(target.getLabel(), target));
    ImmutableMap<Label, Target> labelToTargetMap = labelToTargetsMapBuilder.buildOrThrow();

    eventBus.post(new AnalysisPhaseStartedEvent(labelToTargetMap.values()));

    // Prepare the analysis phase
    BuildConfigurationCollection configurations;
    TopLevelTargetsAndConfigsResult topLevelTargetsWithConfigsResult;
    if (viewOptions.skyframePrepareAnalysis) {
      PrepareAnalysisPhaseValue prepareAnalysisPhaseValue;
      try (SilentCloseable c = Profiler.instance().profile("Prepare analysis phase")) {
        prepareAnalysisPhaseValue =
            skyframeExecutor.prepareAnalysisPhase(
                eventHandler, targetOptions, multiCpu, loadingResult.getTargetLabels());

        // Determine the configurations
        configurations =
            prepareAnalysisPhaseValue.getConfigurations(eventHandler, skyframeExecutor);
        topLevelTargetsWithConfigsResult =
            prepareAnalysisPhaseValue.getTopLevelCts(eventHandler, skyframeExecutor);
      }
    } else {
      // Configuration creation.
      // TODO(gregce): Consider dropping this phase and passing on-the-fly target / host configs as
      // needed. This requires cleaning up the invalidation in SkyframeBuildView.setConfigurations.
      try (SilentCloseable c = Profiler.instance().profile("createConfigurations")) {
        configurations =
            skyframeExecutor.createConfigurations(eventHandler, targetOptions, multiCpu, keepGoing);
      }
      try (SilentCloseable c = Profiler.instance().profile("AnalysisUtils.getTargetsWithConfigs")) {
        topLevelTargetsWithConfigsResult =
            AnalysisUtils.getTargetsWithConfigs(
                configurations,
                labelToTargetMap.values(),
                eventHandler,
                ruleClassProvider,
                skyframeExecutor);
      }
    }

    skyframeBuildView.setConfigurations(
        eventHandler, configurations, viewOptions.maxConfigChangesToShow);

    if (configurations.getTargetConfigurations().size() == 1) {
      eventBus.post(
          new MakeEnvironmentEvent(
              configurations.getTargetConfigurations().get(0).getMakeEnvironment()));
    }
    for (BuildConfigurationValue targetConfig : configurations.getTargetConfigurations()) {
      eventBus.post(targetConfig.toBuildEvent());
    }

    Collection<TargetAndConfiguration> topLevelTargetsWithConfigs =
        topLevelTargetsWithConfigsResult.getTargetsAndConfigs();

    // Report the generated association of targets to configurations
    Multimap<Label, BuildConfigurationValue> byLabel = ArrayListMultimap.create();
    for (TargetAndConfiguration pair : topLevelTargetsWithConfigs) {
      byLabel.put(pair.getLabel(), pair.getConfiguration());
    }
    for (Target target : labelToTargetMap.values()) {
      eventBus.post(new TargetConfiguredEvent(target, byLabel.get(target.getLabel())));
    }

    List<ConfiguredTargetKey> topLevelCtKeys =
        topLevelTargetsWithConfigs.stream()
            .map(BuildView::getConfiguredTargetKey)
            .collect(Collectors.toList());

    ImmutableList.Builder<AspectClass> aspectClassesBuilder = ImmutableList.builder();
    for (String aspect : aspects) {
      // Syntax: label%aspect
      int delimiterPosition = aspect.indexOf('%');
      if (delimiterPosition >= 0) {
        // TODO(jfield): For consistency with Starlark loads, the aspect should be specified
        // as an absolute label.
        // We convert it for compatibility reasons (this will be removed in the future).
        String bzlFileLoadLikeString = aspect.substring(0, delimiterPosition);
        if (!bzlFileLoadLikeString.startsWith("//") && !bzlFileLoadLikeString.startsWith("@")) {
          // "Legacy" behavior of '--aspects' parameter.
          if (bzlFileLoadLikeString.startsWith("/")) {
            bzlFileLoadLikeString = bzlFileLoadLikeString.substring(1);
          }
          int lastSlashPosition = bzlFileLoadLikeString.lastIndexOf('/');
          if (lastSlashPosition >= 0) {
            bzlFileLoadLikeString =
                "//"
                    + bzlFileLoadLikeString.substring(0, lastSlashPosition)
                    + ":"
                    + bzlFileLoadLikeString.substring(lastSlashPosition + 1);
          } else {
            bzlFileLoadLikeString = "//:" + bzlFileLoadLikeString;
          }
          if (!bzlFileLoadLikeString.endsWith(".bzl")) {
            bzlFileLoadLikeString = bzlFileLoadLikeString + ".bzl";
          }
        }
        Label starlarkFileLabel;
        try {
          starlarkFileLabel =
              Label.parseAbsolute(
                  bzlFileLoadLikeString, /* repositoryMapping= */ ImmutableMap.of());
        } catch (LabelSyntaxException e) {
          String errorMessage = String.format("Invalid aspect '%s': %s", aspect, e.getMessage());
          throw new ViewCreationFailedException(
              errorMessage,
              createFailureDetail(errorMessage, Analysis.Code.ASPECT_LABEL_SYNTAX_ERROR),
              e);
        }
        String starlarkFunctionName = aspect.substring(delimiterPosition + 1);
        aspectClassesBuilder.add(new StarlarkAspectClass(starlarkFileLabel, starlarkFunctionName));
      } else {
        final NativeAspectClass aspectFactoryClass =
            ruleClassProvider.getNativeAspectClassMap().get(aspect);

        if (aspectFactoryClass != null) {
          aspectClassesBuilder.add(aspectFactoryClass);
        } else {
          String errorMessage = "Aspect '" + aspect + "' is unknown";
          throw new ViewCreationFailedException(
              errorMessage, createFailureDetail(errorMessage, Analysis.Code.ASPECT_NOT_FOUND));
        }
      }
    }

    Multimap<Pair<Label, String>, BuildConfigurationValue> aspectConfigurations =
        ArrayListMultimap.create();
    ImmutableList<AspectClass> aspectClasses = aspectClassesBuilder.build();
    ImmutableList.Builder<TopLevelAspectsKey> aspectsKeys = ImmutableList.builder();
    for (TargetAndConfiguration targetSpec : topLevelTargetsWithConfigs) {
      BuildConfigurationValue configuration = targetSpec.getConfiguration();
      for (AspectClass aspectClass : aspectClasses) {
        aspectConfigurations.put(
            Pair.of(targetSpec.getLabel(), aspectClass.getName()), configuration);
      }
      // For invoking top-level aspects, use the top-level configuration for both the
      // aspect and the base target while the top-level configuration is untrimmed.
      if (!aspectClasses.isEmpty()) {
        aspectsKeys.add(
            AspectKeyCreator.createTopLevelAspectsKey(
                aspectClasses, targetSpec.getLabel(), configuration, aspectsParameters));
      }
    }

    for (Pair<Label, String> target : aspectConfigurations.keys()) {
      eventBus.post(
          new AspectConfiguredEvent(
              target.getFirst(), target.getSecond(), aspectConfigurations.get(target)));
    }

    getArtifactFactory().noteAnalysisStarting();
    SkyframeAnalysisResult skyframeAnalysisResult;
    try {
      Supplier<Map<BuildConfigurationKey, BuildConfigurationValue>>
          memoizedConfigurationLookupSupplier =
              Suppliers.memoize(
                  () -> {
                    Map<BuildConfigurationKey, BuildConfigurationValue> result = new HashMap<>();
                    for (TargetAndConfiguration node : topLevelTargetsWithConfigs) {
                      if (node.getConfiguration() != null) {
                        result.put(node.getConfiguration().getKey(), node.getConfiguration());
                      }
                    }
                    return result;
                  });
      if (!includeExecutionPhase) {
        skyframeAnalysisResult =
            skyframeBuildView.configureTargets(
                eventHandler,
                topLevelCtKeys,
                aspectsKeys.build(),
                memoizedConfigurationLookupSupplier,
                topLevelOptions,
                eventBus,
                keepGoing,
                loadingPhaseThreads,
                viewOptions.strictConflictChecks,
                checkForActionConflicts,
                viewOptions.cpuHeavySkyKeysThreadPoolSize);
        setArtifactRoots(skyframeAnalysisResult.getPackageRoots());
      } else {
        skyframeAnalysisResult =
            skyframeBuildView.analyzeAndExecuteTargets(
                eventHandler,
                topLevelCtKeys,
                aspectsKeys.build(),
                loadingResult.getTestsToRunLabels(),
                labelToTargetMap,
                memoizedConfigurationLookupSupplier,
                topLevelOptions,
                eventBus,
                bugReporter,
                keepGoing,
                viewOptions.strictConflictChecks,
                checkForActionConflicts,
                loadingPhaseThreads,
                viewOptions.cpuHeavySkyKeysThreadPoolSize,
                mergedPhasesExecutionJobsCount);
      }
    } finally {
      skyframeBuildView.clearInvalidatedActionLookupKeys();
    }

    int numTargetsToAnalyze = topLevelTargetsWithConfigs.size();
    int numSuccessful = skyframeAnalysisResult.getConfiguredTargets().size();
    if (0 < numSuccessful && numSuccessful < numTargetsToAnalyze) {
      String msg =
          String.format(
              "Analysis succeeded for only %d of %d top-level targets",
              numSuccessful, numTargetsToAnalyze);
      eventHandler.handle(Event.info(msg));
      logger.atInfo().log("%s", msg);
    }

    AnalysisResult result;
    if (includeExecutionPhase) {
      // TODO(b/199053098): Also consider targets with errors like below.
      result =
          createResult(
              eventHandler,
              eventBus,
              loadingResult,
              configurations,
              topLevelOptions,
              viewOptions,
              skyframeAnalysisResult,
              /*targetsToSkip=*/ ImmutableSet.of(),
              /*labelToTargetMap=*/ labelToTargetMap,
              topLevelTargetsWithConfigsResult,
              /*includeExecutionPhase=*/ true);
    } else {
      ImmutableSet<ConfiguredTarget> targetsToSkip = ImmutableSet.of();
      if (reportIncompatibleTargets) {
        TopLevelConstraintSemantics topLevelConstraintSemantics =
            new TopLevelConstraintSemantics(
                (RuleContextConstraintSemantics) ruleClassProvider.getConstraintSemantics(),
                skyframeExecutor.getPackageManager(),
                input -> skyframeExecutor.getConfiguration(eventHandler, input),
                eventHandler);

        PlatformRestrictionsResult platformRestrictions =
            topLevelConstraintSemantics.checkPlatformRestrictions(
                skyframeAnalysisResult.getConfiguredTargets(), explicitTargetPatterns, keepGoing);

        if (!platformRestrictions.targetsWithErrors().isEmpty()) {
          // If there are any errored targets (e.g. incompatible targets that are explicitly
          // specified on the command line), remove them from the list of targets to be built.
          skyframeAnalysisResult =
              skyframeAnalysisResult.withAdditionalErroredTargets(
                  platformRestrictions.targetsWithErrors());
        }

        targetsToSkip =
            Sets.union(
                    topLevelConstraintSemantics.checkTargetEnvironmentRestrictions(
                        skyframeAnalysisResult.getConfiguredTargets()),
                    platformRestrictions.targetsToSkip())
                .immutableCopy();
      }

      result =
          createResult(
              eventHandler,
              eventBus,
              loadingResult,
              configurations,
              topLevelOptions,
              viewOptions,
              skyframeAnalysisResult,
              targetsToSkip,
              labelToTargetMap,
              topLevelTargetsWithConfigsResult,
              /*includeExecutionPhase=*/ false);
    }
    logger.atInfo().log("Finished analysis");
    return result;
  }

  private static ConfiguredTargetKey getConfiguredTargetKey(
      TargetAndConfiguration targetAndConfiguration) {
    return ConfiguredTargetKey.builder()
        .setLabel(targetAndConfiguration.getLabel())
        .setConfiguration(targetAndConfiguration.getConfiguration())
        .build();
  }

  private AnalysisResult createResult(
      ExtendedEventHandler eventHandler,
      EventBus eventBus,
      TargetPatternPhaseValue loadingResult,
      BuildConfigurationCollection configurations,
      TopLevelArtifactContext topLevelOptions,
      AnalysisOptions viewOptions,
      SkyframeAnalysisResult skyframeAnalysisResult,
      Set<ConfiguredTarget> targetsToSkip,
      ImmutableMap<Label, Target> labelToTargetMap,
      TopLevelTargetsAndConfigsResult topLevelTargetsWithConfigs,
      boolean includeExecutionPhase)
      throws InterruptedException {
    Set<Label> testsToRun = loadingResult.getTestsToRunLabels();
    Set<ConfiguredTarget> configuredTargets =
        Sets.newLinkedHashSet(skyframeAnalysisResult.getConfiguredTargets());
    ImmutableMap<AspectKey, ConfiguredAspect> aspects = skyframeAnalysisResult.getAspects();

    Set<ConfiguredTarget> allTargetsToTest = null;
    if (testsToRun != null) {
      // Determine the subset of configured targets that are meant to be run as tests.
      allTargetsToTest = filterTestsByTargets(configuredTargets, testsToRun);
    }

    ImmutableSet.Builder<Artifact> artifactsToBuild = ImmutableSet.builder();

    // build-info and build-changelist.
    Collection<Artifact> buildInfoArtifacts =
        skyframeExecutor.getWorkspaceStatusArtifacts(eventHandler);
    Preconditions.checkState(buildInfoArtifacts.size() == 2, buildInfoArtifacts);

    // Extra actions
    addExtraActionsIfRequested(
        viewOptions, configuredTargets, aspects, artifactsToBuild, eventHandler);

    // Coverage
    NestedSet<Artifact> baselineCoverageArtifacts =
        getBaselineCoverageArtifacts(configuredTargets, artifactsToBuild);
    if (coverageReportActionFactory != null) {
      CoverageReportActionsWrapper actionsWrapper;
      actionsWrapper =
          coverageReportActionFactory.createCoverageReportActionsWrapper(
              eventHandler,
              eventBus,
              directories,
              allTargetsToTest,
              baselineCoverageArtifacts,
              getArtifactFactory(),
              skyframeExecutor.getActionKeyContext(),
              CoverageReportValue.COVERAGE_REPORT_KEY,
              loadingResult.getWorkspaceName());
      if (actionsWrapper != null) {
        Actions.GeneratingActions actions = actionsWrapper.getActions();
        skyframeExecutor.injectCoverageReportData(actions);
        actionsWrapper.getCoverageOutputs().forEach(artifactsToBuild::add);
      }
    }
    // TODO(cparsons): If extra actions are ever removed, this filtering step can probably be
    //  removed as well: the only concern would be action conflicts involving coverage artifacts,
    //  which seems far-fetched.
    if (skyframeAnalysisResult.hasActionConflicts()) {
      // We don't remove the (hopefully unnecessary) guard in SkyframeBuildView that enables/
      // disables analysis, since no new targets should actually be analyzed.
      ImmutableSet<Artifact> artifacts = artifactsToBuild.build();
      Predicate<Artifact> errorFreeArtifacts =
          skyframeExecutor.filterActionConflictsForTopLevelArtifacts(eventHandler, artifacts);

      artifactsToBuild = ImmutableSet.builder();
      artifacts.stream().filter(errorFreeArtifacts).forEach(artifactsToBuild::add);
    }
    // Build-info artifacts are always conflict-free, and can't be checked easily.
    buildInfoArtifacts.forEach(artifactsToBuild::add);

    // Tests.
    Pair<ImmutableSet<ConfiguredTarget>, ImmutableSet<ConfiguredTarget>> testsPair =
        collectTests(topLevelOptions, allTargetsToTest, labelToTargetMap);
    ImmutableSet<ConfiguredTarget> parallelTests = testsPair.first;
    ImmutableSet<ConfiguredTarget> exclusiveTests = testsPair.second;

    FailureDetail failureDetail =
        createFailureDetail(loadingResult, skyframeAnalysisResult, topLevelTargetsWithConfigs);
    if (includeExecutionPhase) {
      return new AnalysisAndExecutionResult(
          configurations,
          ImmutableSet.copyOf(configuredTargets),
          aspects,
          allTargetsToTest == null ? null : ImmutableList.copyOf(allTargetsToTest),
          ImmutableSet.copyOf(targetsToSkip),
          failureDetail,
          artifactsToBuild.build(),
          parallelTests,
          exclusiveTests,
          topLevelOptions,
          loadingResult.getWorkspaceName(),
          topLevelTargetsWithConfigs.getTargetsAndConfigs(),
          loadingResult.getNotSymlinkedInExecrootDirectories());
    }


    WalkableGraph graph = skyframeAnalysisResult.getWalkableGraph();
    ActionGraph actionGraph =
        new ActionGraph() {
          @Nullable
          @Override
          public ActionAnalysisMetadata getGeneratingAction(Artifact artifact) {
            if (artifact.isSourceArtifact()) {
              return null;
            }
            ActionLookupData generatingActionKey =
                ((Artifact.DerivedArtifact) artifact).getGeneratingActionKey();
            ActionLookupValue val;
            try {
              val = (ActionLookupValue) graph.getValue(generatingActionKey.getActionLookupKey());
            } catch (InterruptedException e) {
              throw new IllegalStateException(
                  "Interruption not expected from this graph: " + generatingActionKey, e);
            }
            if (val == null) {
              logger.atWarning().atMostEvery(1, TimeUnit.SECONDS).log(
                  "Missing generating action for %s (%s)", artifact, generatingActionKey);
              return null;
            }
            return val.getActions().get(generatingActionKey.getActionIndex());
          }
        };
    return new AnalysisResult(
        configurations,
        ImmutableSet.copyOf(configuredTargets),
        aspects,
        allTargetsToTest == null ? null : ImmutableList.copyOf(allTargetsToTest),
        ImmutableSet.copyOf(targetsToSkip),
        failureDetail,
        actionGraph,
        artifactsToBuild.build(),
        parallelTests,
        exclusiveTests,
        topLevelOptions,
        skyframeAnalysisResult.getPackageRoots(),
        loadingResult.getWorkspaceName(),
        topLevelTargetsWithConfigs.getTargetsAndConfigs(),
        loadingResult.getNotSymlinkedInExecrootDirectories());
  }

  /**
   * Check for errors in "chronological" order (acknowledge that loading and analysis are
   * interleaved, but sequential on the single target scale).
   */
  @Nullable
  public static FailureDetail createFailureDetail(
      TargetPatternPhaseValue loadingResult,
      @Nullable SkyframeAnalysisResult skyframeAnalysisResult,
      @Nullable TopLevelTargetsAndConfigsResult topLevelTargetsAndConfigs) {
    if (loadingResult.hasError()) {
      return FailureDetail.newBuilder()
          .setMessage("command succeeded, but there were errors parsing the target pattern")
          .setTargetPatterns(TargetPatterns.newBuilder().setCode(Code.TARGET_PATTERN_PARSE_FAILURE))
          .build();
    }
    if (loadingResult.hasPostExpansionError()
        || (skyframeAnalysisResult != null && skyframeAnalysisResult.hasLoadingError())) {
      return FailureDetail.newBuilder()
          .setMessage("command succeeded, but there were loading phase errors")
          .setAnalysis(Analysis.newBuilder().setCode(Analysis.Code.GENERIC_LOADING_PHASE_FAILURE))
          .build();
    }
    if (topLevelTargetsAndConfigs != null && topLevelTargetsAndConfigs.hasError()) {
      return FailureDetail.newBuilder()
          .setMessage("command succeeded, but top level configurations could not be created")
          .setBuildConfiguration(
              FailureDetails.BuildConfiguration.newBuilder()
                  .setCode(
                      FailureDetails.BuildConfiguration.Code
                          .TOP_LEVEL_CONFIGURATION_CREATION_FAILURE))
          .build();
    }
    if (skyframeAnalysisResult != null && skyframeAnalysisResult.hasAnalysisError()) {
      return FailureDetail.newBuilder()
          .setMessage("command succeeded, but not all targets were analyzed")
          .setAnalysis(Analysis.newBuilder().setCode(Analysis.Code.NOT_ALL_TARGETS_ANALYZED))
          .build();
    }
    if (skyframeAnalysisResult instanceof SkyframeAnalysisAndExecutionResult) {
      SkyframeAnalysisAndExecutionResult skyframeAnalysisAndExecutionResult =
          (SkyframeAnalysisAndExecutionResult) skyframeAnalysisResult;
      if (skyframeAnalysisAndExecutionResult.getRepresentativeExecutionExitCode() != null) {
        return skyframeAnalysisAndExecutionResult
            .getRepresentativeExecutionExitCode()
            .getFailureDetail();
      }
    }
    return null;
  }

  private static FailureDetail createFailureDetail(String errorMessage, Analysis.Code code) {
    return FailureDetail.newBuilder()
        .setMessage(errorMessage)
        .setAnalysis(Analysis.newBuilder().setCode(code))
        .build();
  }

  private static NestedSet<Artifact> getBaselineCoverageArtifacts(
      Collection<ConfiguredTarget> configuredTargets,
      ImmutableSet.Builder<Artifact> artifactsToBuild) {
    NestedSetBuilder<Artifact> baselineCoverageArtifacts = NestedSetBuilder.stableOrder();
    for (ConfiguredTarget target : configuredTargets) {
      InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
      if (provider != null) {
        artifactsToBuild.addAll(provider.getBaselineCoverageArtifacts().toList());
        baselineCoverageArtifacts.addTransitive(provider.getBaselineCoverageArtifacts());
      }
    }
    return baselineCoverageArtifacts.build();
  }

  private void addExtraActionsIfRequested(
      AnalysisOptions viewOptions,
      Collection<ConfiguredTarget> configuredTargets,
      ImmutableMap<AspectKey, ConfiguredAspect> aspects,
      ImmutableSet.Builder<Artifact> artifactsToBuild,
      ExtendedEventHandler eventHandler) {
    RegexFilter filter = viewOptions.extraActionFilter;
    for (ConfiguredTarget target : configuredTargets) {
      ExtraActionArtifactsProvider provider =
          target.getProvider(ExtraActionArtifactsProvider.class);
      if (provider != null) {
        if (viewOptions.extraActionTopLevelOnly) {
          // Collect all aspect-classes that topLevel might inject.
          Set<AspectClass> aspectClasses = new HashSet<>();
          Target actualTarget = null;
          try {
            actualTarget =
                skyframeExecutor.getPackageManager().getTarget(eventHandler, target.getLabel());
          } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
            eventHandler.handle(Event.error(""));
          }
          for (Attribute attr : actualTarget.getAssociatedRule().getAttributes()) {
            aspectClasses.addAll(attr.getAspectClasses());
          }
          addArtifactsToBuilder(
              provider.getExtraActionArtifacts().toList(), artifactsToBuild, filter);
          if (!aspectClasses.isEmpty()) {
            addArtifactsToBuilder(
                filterTransitiveExtraActions(provider, aspectClasses), artifactsToBuild, filter);
          }
        } else {
          addArtifactsToBuilder(
              provider.getTransitiveExtraActionArtifacts().toList(), artifactsToBuild, filter);
        }
      }
    }
    for (Map.Entry<AspectKey, ConfiguredAspect> aspectEntry : aspects.entrySet()) {
      ExtraActionArtifactsProvider provider =
          aspectEntry.getValue().getProvider(ExtraActionArtifactsProvider.class);
      if (provider != null) {
        if (viewOptions.extraActionTopLevelOnly) {
          addArtifactsToBuilder(
              provider.getExtraActionArtifacts().toList(), artifactsToBuild, filter);
        } else {
          addArtifactsToBuilder(
              provider.getTransitiveExtraActionArtifacts().toList(), artifactsToBuild, filter);
        }
      }
    }
  }

  private static void addArtifactsToBuilder(
      List<? extends Artifact> artifacts,
      ImmutableSet.Builder<Artifact> builder,
      RegexFilter filter) {
    for (Artifact artifact : artifacts) {
      if (filter.isIncluded(artifact.getOwnerLabel().toString())) {
        builder.add(artifact);
      }
    }
  }

  /**
   * Returns a list of artifacts from 'provider' that were registered by an aspect from
   * 'aspectClasses'. All artifacts in 'provider' are considered - both direct and transitive.
   */
  private static ImmutableList<Artifact> filterTransitiveExtraActions(
      ExtraActionArtifactsProvider provider, Set<AspectClass> aspectClasses) {
    ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
    // Add to 'artifacts' all extra-actions which were registered by aspects which 'topLevel'
    // might have injected.
    for (Artifact.DerivedArtifact artifact :
        provider.getTransitiveExtraActionArtifacts().toList()) {
      ActionLookupKey owner = artifact.getArtifactOwner();
      if (owner instanceof AspectKey) {
        if (aspectClasses.contains(((AspectKey) owner).getAspectClass())) {
          artifacts.add(artifact);
        }
      }
    }
    return artifacts.build();
  }

  private static Pair<ImmutableSet<ConfiguredTarget>, ImmutableSet<ConfiguredTarget>> collectTests(
      TopLevelArtifactContext topLevelOptions,
      @Nullable Iterable<ConfiguredTarget> allTestTargets,
      ImmutableMap<Label, Target> labelToTargetMap) {
    Set<String> outputGroups = topLevelOptions.outputGroups();
    if (!outputGroups.contains(OutputGroupInfo.FILES_TO_COMPILE)
        && !outputGroups.contains(OutputGroupInfo.COMPILATION_PREREQUISITES)
        && allTestTargets != null) {
      final boolean isExclusive = topLevelOptions.runTestsExclusively();
      ImmutableSet.Builder<ConfiguredTarget> targetsToTest = ImmutableSet.builder();
      ImmutableSet.Builder<ConfiguredTarget> targetsToTestExclusive = ImmutableSet.builder();
      for (ConfiguredTarget configuredTarget : allTestTargets) {
        Target target = labelToTargetMap.get(configuredTarget.getLabel());
        if (target instanceof Rule) {
          if (isExclusive || TargetUtils.isExclusiveTestRule((Rule) target)) {
            targetsToTestExclusive.add(configuredTarget);
          } else {
            targetsToTest.add(configuredTarget);
          }
        }
      }
      return Pair.of(targetsToTest.build(), targetsToTestExclusive.build());
    } else {
      return Pair.of(ImmutableSet.of(), ImmutableSet.of());
    }
  }

  /**
   * Sets the possible artifact roots in the artifact factory. This allows the factory to resolve
   * paths with unknown roots to artifacts.
   */
  private void setArtifactRoots(PackageRoots packageRoots) {
    getArtifactFactory().setPackageRoots(packageRoots.getPackageRootLookup());
  }

  /**
   * Tests and clears the current thread's pending "interrupted" status, and throws
   * InterruptedException iff it was set.
   */
  private static void pollInterruptedStatus() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }
}
