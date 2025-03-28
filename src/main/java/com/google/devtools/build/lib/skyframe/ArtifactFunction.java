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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.actions.MiddlemanType.RUNFILES_MIDDLEMAN;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversalRoot;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.PackageBoundaryMode;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.TraversalRequest;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A builder of values for {@link Artifact} keys when the key is not a simple generated artifact. To
 * save memory, ordinary generated artifacts (non-middleman, non-tree) have their metadata accessed
 * directly from the corresponding {@link ActionExecutionValue}. This SkyFunction is therefore only
 * usable for source, middleman, and tree artifacts.
 */
class ArtifactFunction implements SkyFunction {
  private final Supplier<Boolean> mkdirForTreeArtifacts;
  private final MetadataConsumerForMetrics sourceArtifactsSeen;
  private final XattrProvider xattrProvider;

  static final class MissingArtifactValue implements SkyValue {
    private final DetailedExitCode detailedExitCode;

    private MissingArtifactValue(Artifact missingArtifact) {
      FailureDetail failureDetail =
          FailureDetail.newBuilder()
              .setMessage(constructErrorMessage(missingArtifact, "missing input file"))
              .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_MISSING))
              .build();
      this.detailedExitCode = DetailedExitCode.of(failureDetail);
    }

    DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("detailedExitCode", detailedExitCode).toString();
    }
  }

  public ArtifactFunction(
      Supplier<Boolean> mkdirForTreeArtifacts,
      MetadataConsumerForMetrics sourceArtifactsSeen,
      XattrProvider xattrProvider) {
    this.mkdirForTreeArtifacts = mkdirForTreeArtifacts;
    this.sourceArtifactsSeen = sourceArtifactsSeen;
    this.xattrProvider = xattrProvider;
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ArtifactFunctionException, InterruptedException {
    Artifact artifact = (Artifact) skyKey;
    if (!artifact.hasKnownGeneratingAction()) {
      // If the artifact has no known generating action, it is either a source artifact, or a
      // NinjaMysteryArtifact, which undergoes the same handling here.
      return createSourceValue(artifact, env);
    }
    Artifact.DerivedArtifact derivedArtifact = (DerivedArtifact) artifact;

    ArtifactDependencies artifactDependencies =
        ArtifactDependencies.discoverDependencies(derivedArtifact, env);
    if (artifactDependencies == null) {
      return null;
    }

    // If the action is an ActionTemplate, we need to expand the ActionTemplate into concrete
    // actions, execute those actions in parallel and then aggregate the action execution results.
    ActionTemplate<?> actionTemplate = artifactDependencies.maybeGetTemplateActionForTreeArtifact();
    if (actionTemplate != null) {
      if (mkdirForTreeArtifacts.get()) {
        mkdirForTreeArtifact(artifact, env, actionTemplate);
      }
      return createTreeArtifactValueFromActionKey(artifactDependencies, env);
    }

    ActionLookupData generatingActionKey = derivedArtifact.getGeneratingActionKey();
    ActionExecutionValue actionValue = (ActionExecutionValue) env.getValue(generatingActionKey);
    if (actionValue == null) {
      return null;
    }

    if (artifact.isTreeArtifact()) {
      // We got a request for the whole tree artifact. We can just return the associated
      // TreeArtifactValue.
      return Preconditions.checkNotNull(actionValue.getTreeArtifactValue(artifact), artifact);
    }

    Preconditions.checkState(artifact.isMiddlemanArtifact(), artifact);
    Action action =
        Preconditions.checkNotNull(
            artifactDependencies.actionLookupValue.getAction(generatingActionKey.getActionIndex()),
            "Null middleman action? %s",
            artifactDependencies);
    FileArtifactValue individualMetadata = actionValue.getExistingFileArtifactValue(artifact);
    if (isAggregatingValue(action)) {
      return createRunfilesArtifactValue(artifact, action, individualMetadata, env);
    }
    return individualMetadata;
  }

  private static void mkdirForTreeArtifact(
      Artifact artifact, Environment env, ActionTemplate<?> actionForFailure)
      throws ArtifactFunctionException {
    try {
      artifact.getPath().createDirectoryAndParents();
    } catch (IOException e) {
      String errorMessage =
          String.format(
              "Failed to create output directory for TreeArtifact %s: %s",
              artifact.getExecPath(), e.getMessage());
      env.getListener()
          .handle(Event.error(actionForFailure.getOwner().getLocation(), errorMessage));
      // We could throw this as an IOException and expect our callers to catch and reprocess it,
      // but we know the action at fault, so we should be in charge.
      DetailedExitCode code =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(errorMessage)
                  .setExecution(
                      Execution.newBuilder().setCode(Code.TREE_ARTIFACT_DIRECTORY_CREATION_FAILURE))
                  .build());
      throw new ArtifactFunctionException(
          new ActionExecutionException(errorMessage, e, actionForFailure, false, code));
    }
  }

  private static TreeArtifactValue createTreeArtifactValueFromActionKey(
      ArtifactDependencies artifactDependencies, Environment env) throws InterruptedException {
    // Request the list of expanded actions from the ActionTemplate.
    ActionTemplateExpansion actionTemplateExpansion =
        artifactDependencies.getActionTemplateExpansion(env);
    if (actionTemplateExpansion == null) {
      // The expanded actions are not yet available.
      return null;
    }
    ActionTemplateExpansionValue expansionValue = actionTemplateExpansion.getValue();
    ImmutableList<ActionLookupData> expandedActionExecutionKeys =
        actionTemplateExpansion.getExpandedActionExecutionKeys();

    Map<SkyKey, SkyValue> expandedActionValueMap = env.getValues(expandedActionExecutionKeys);
    if (env.valuesMissing()) {
      // The execution values of the expanded actions are not yet all available.
      return null;
    }

    // Aggregate the metadata for individual TreeFileArtifacts into a TreeArtifactValue for the
    // parent TreeArtifact.
    SpecialArtifact parent = (SpecialArtifact) artifactDependencies.artifact;
    TreeArtifactValue.Builder treeBuilder = TreeArtifactValue.newBuilder(parent);
    boolean omitted = false;

    for (ActionLookupData actionKey : expandedActionExecutionKeys) {
      boolean sawTreeChild = false;
      ActionExecutionValue actionExecutionValue =
          (ActionExecutionValue)
              Preconditions.checkNotNull(
                  expandedActionValueMap.get(actionKey),
                  "Missing tree value: %s %s %s",
                  artifactDependencies,
                  expansionValue,
                  expandedActionValueMap);

      for (Map.Entry<Artifact, FileArtifactValue> entry :
          actionExecutionValue.getAllFileValues().entrySet()) {
        Artifact artifact = entry.getKey();
        Preconditions.checkState(
            artifact.hasParent(),
            "Parentless artifact %s found in ActionExecutionValue for %s: %s %s",
            artifact,
            actionKey,
            actionExecutionValue,
            artifactDependencies);

        if (artifact.getParent().equals(parent)) {
          sawTreeChild = true;
          if (FileArtifactValue.OMITTED_FILE_MARKER.equals(entry.getValue())) {
            omitted = true;
          } else {
            treeBuilder.putChild((TreeFileArtifact) artifact, entry.getValue());
          }
        }
      }

      Preconditions.checkState(
          sawTreeChild,
          "Action denoted by %s does not output any TreeFileArtifacts from %s",
          actionKey,
          artifactDependencies);
    }

    TreeArtifactValue tree = treeBuilder.build();

    if (omitted) {
      Preconditions.checkState(
          tree.getChildValues().isEmpty(),
          "Action template expansion has some but not all outputs omitted, present outputs: %s",
          artifactDependencies,
          tree.getChildValues());
      return TreeArtifactValue.OMITTED_TREE_MARKER;
    }

    return tree;
  }

  private SkyValue createSourceValue(Artifact artifact, Environment env)
      throws InterruptedException, ArtifactFunctionException {
    RootedPath path = RootedPath.toRootedPath(artifact.getRoot().getRoot(), artifact.getPath());
    SkyKey fileSkyKey = FileValue.key(path);
    FileValue fileValue;
    try {
      fileValue = (FileValue) env.getValueOrThrow(fileSkyKey, IOException.class);
    } catch (IOException e) {
      throw new ArtifactFunctionException(
          SourceArtifactException.create(artifact, e), Transience.PERSISTENT);
    }
    if (fileValue == null) {
      return null;
    }
    if (!fileValue.exists()) {
      return new MissingArtifactValue(artifact);
    }

    if (!fileValue.isDirectory() || !TrackSourceDirectoriesFlag.trackSourceDirectories()) {
      FileArtifactValue metadata;
      try {
        metadata = FileArtifactValue.createForSourceArtifact(artifact, fileValue, xattrProvider);
      } catch (IOException e) {
        throw new ArtifactFunctionException(
            SourceArtifactException.create(artifact, e), Transience.TRANSIENT);
      }
      sourceArtifactsSeen.accumulate(metadata);
      return metadata;
    }
    // For directory artifacts that are not Filesets, we initiate a directory traversal here, and
    // compute a hash from the directory structure.
    // We rely on the guarantees of RecursiveFilesystemTraversalFunction for correctness.
    //
    // This approach may have unexpected interactions with --package_path. In particular, the exec
    // root is setup from the loading / analysis phase, and it is now too late to change it;
    // therefore, this may traverse a different set of files depending on which targets are built
    // at the same time and what the package-path layout is (this may be moot if there is only one
    // entry). Or this may return a set of files that's inconsistent with those actually available
    // to the action (for local execution).
    //
    // In the future, we need to make this result the source of truth for the files available to
    // the action so that we at least have consistency.
    TraversalRequest request =
        TraversalRequest.create(
            DirectTraversalRoot.forRootedPath(path),
            /*isRootGenerated=*/ false,
            PackageBoundaryMode.CROSS,
            /*strictOutputFiles=*/ true,
            /*skipTestingForSubpackage=*/ true,
            /*errorInfo=*/ "Directory artifact " + artifact.prettyPrint());
    RecursiveFilesystemTraversalValue value;
    try {
      value =
          (RecursiveFilesystemTraversalValue)
              env.getValueOrThrow(request, RecursiveFilesystemTraversalException.class);
    } catch (RecursiveFilesystemTraversalException e) {
      // Use a switch to guarantee that if a new type is added, this stops compiling.
      switch (e.getType()) {
        case DANGLING_SYMLINK:
        case FILE_OPERATION_FAILURE:
        case SYMLINK_CYCLE_OR_INFINITE_EXPANSION:
          throw new ArtifactFunctionException(
              SourceArtifactException.create(artifact, e), Transience.PERSISTENT);
        case CANNOT_CROSS_PACKAGE_BOUNDARY:
          throw new IllegalStateException(
              String.format(
                  "Package boundary mode was cross: %s %s %s" + artifact, fileValue, request),
              e);
        case GENERATED_PATH_CONFLICT:
          throw new IllegalStateException(
              String.format(
                  "Generated conflict in source tree: %s %s %s", artifact, fileValue, request),
              e);
      }
      throw new IllegalStateException("Can't get here", e);
    }
    if (value == null) {
      return null;
    }
    Fingerprint fp = new Fingerprint();
    for (ResolvedFile file : value.getTransitiveFiles().toList()) {
      fp.addString(file.getNameInSymlinkTree().getPathString());
      fp.addBytes(file.getMetadata().getDigest());
    }
    return FileArtifactValue.createForDirectoryWithHash(fp.digestAndReset());
  }

  @Nullable
  private static RunfilesArtifactValue createRunfilesArtifactValue(
      Artifact artifact,
      ActionAnalysisMetadata action,
      FileArtifactValue value,
      SkyFunction.Environment env)
      throws InterruptedException {
    ImmutableList.Builder<Pair<Artifact, FileArtifactValue>> fileInputsBuilder =
        ImmutableList.builder();
    ImmutableList.Builder<Pair<Artifact, TreeArtifactValue>> directoryInputsBuilder =
        ImmutableList.builder();
    // Avoid iterating over nested set twice.
    List<Artifact> inputs = action.getInputs().toList();
    Map<SkyKey, SkyValue> values = env.getValues(Artifact.keys(inputs));
    if (env.valuesMissing()) {
      return null;
    }
    for (Artifact input : inputs) {
      SkyValue inputValue = Preconditions.checkNotNull(values.get(Artifact.key(input)), input);
      if (inputValue instanceof FileArtifactValue) {
        fileInputsBuilder.add(Pair.of(input, (FileArtifactValue) inputValue));
      } else if (inputValue instanceof ActionExecutionValue) {
        fileInputsBuilder.add(
            Pair.of(
                input, ((ActionExecutionValue) inputValue).getExistingFileArtifactValue(input)));
      } else if (inputValue instanceof TreeArtifactValue) {
        directoryInputsBuilder.add(Pair.of(input, (TreeArtifactValue) inputValue));
      } else {
        // We do not recurse in middleman artifacts.
        Preconditions.checkState(
            !(inputValue instanceof RunfilesArtifactValue),
            "%s %s %s",
            artifact,
            action,
            inputValue);
      }
    }

    ImmutableList<Pair<Artifact, FileArtifactValue>> fileInputs =
        ImmutableList.sortedCopyOf(
            Comparator.comparing(pair -> pair.getFirst().getExecPathString()),
            fileInputsBuilder.build());
    ImmutableList<Pair<Artifact, TreeArtifactValue>> directoryInputs =
        ImmutableList.sortedCopyOf(
            Comparator.comparing(pair -> pair.getFirst().getExecPathString()),
            directoryInputsBuilder.build());

    return new RunfilesArtifactValue(fileInputs, directoryInputs, value);
  }

  /**
   * Returns whether this value needs to contain the data of all its inputs. Currently only tests to
   * see if the action is a runfiles middleman action. However, may include Fileset artifacts in the
   * future.
   */
  private static boolean isAggregatingValue(ActionAnalysisMetadata action) {
    return action.getActionType() == RUNFILES_MIDDLEMAN;
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((Artifact) skyKey).getOwner());
  }

  static ActionLookupKey getActionLookupKey(Artifact artifact) {
    ArtifactOwner artifactOwner = artifact.getArtifactOwner();
    Preconditions.checkState(
        artifactOwner instanceof ActionLookupKey, "%s %s", artifact, artifactOwner);
    return (ActionLookupKey) artifactOwner;
  }

  @Nullable
  static ActionLookupValue getActionLookupValue(
      ActionLookupKey actionLookupKey, SkyFunction.Environment env) throws InterruptedException {
    ActionLookupValue value = (ActionLookupValue) env.getValue(actionLookupKey);
    if (value == null) {
      Preconditions.checkState(
          actionLookupKey == CoverageReportValue.COVERAGE_REPORT_KEY,
          "Not-yet-present artifact owner: %s",
          actionLookupKey);
      return null;
    }
    return value;
  }

  private static final class ArtifactFunctionException extends SkyFunctionException {
    ArtifactFunctionException(ActionExecutionException e) {
      super(e, Transience.TRANSIENT);
    }

    ArtifactFunctionException(SourceArtifactException e, Transience transience) {
      super(e, transience);
    }
  }

  private static String constructErrorMessage(Artifact artifact, String error) {
    Label ownerLabel = artifact.getOwner();
    if (ownerLabel == null || ownerLabel.getName().equals(".")) {
      // Discovered inputs may not have an owner. Directory source artifacts may be owned by a label
      // ':.' which will crash toPathFragment below.
      return String.format("%s '%s'", error, artifact.getExecPathString());
    } else if (ownerLabel.toPathFragment().equals(artifact.getExecPath())) {
      // No additional useful information from path.
      return String.format("%s '%s'", error, ownerLabel);
    } else {
      // TODO(janakr): when is this hit?
      BugReport.sendBugReport(
          new IllegalStateException("Unexpected special owner? " + artifact + ", " + ownerLabel));
      return String.format("%s '%s', owner: '%s'", error, artifact.getExecPathString(), ownerLabel);
    }
  }

  /** Describes dependencies of derived artifacts. */
  // TODO(b/19539699): extend this to comprehensively support all special artifact types (e.g.
  // middleman, etc).
  static class ArtifactDependencies {
    private final DerivedArtifact artifact;
    private final ActionLookupValue actionLookupValue;

    private ArtifactDependencies(DerivedArtifact artifact, ActionLookupValue actionLookupValue) {
      this.artifact = artifact;
      this.actionLookupValue = actionLookupValue;
    }

    /**
     * Constructs an {@link ArtifactDependencies} for the provided {@code derivedArtifact}. Returns
     * {@code null} if any dependencies are not yet ready.
     */
    @Nullable
    static ArtifactDependencies discoverDependencies(
        Artifact.DerivedArtifact derivedArtifact, SkyFunction.Environment env)
        throws InterruptedException {

      ActionLookupData generatingActionKey = derivedArtifact.getGeneratingActionKey();
      ActionLookupValue actionLookupValue =
          ArtifactFunction.getActionLookupValue(generatingActionKey.getActionLookupKey(), env);
      if (actionLookupValue == null) {
        return null;
      }

      return new ArtifactDependencies(derivedArtifact, actionLookupValue);
    }

    boolean isTemplateActionForTreeArtifact() {
      return maybeGetTemplateActionForTreeArtifact() != null;
    }

    ActionTemplate<?> maybeGetTemplateActionForTreeArtifact() {
      if (!artifact.isTreeArtifact()) {
        return null;
      }
      ActionAnalysisMetadata result =
          actionLookupValue.getActions().get(artifact.getGeneratingActionKey().getActionIndex());
      return result instanceof ActionTemplate ? (ActionTemplate<?>) result : null;
    }

    /**
     * Returns action template expansion information or {@code null} if that information is
     * unavailable.
     *
     * <p>Must not be called if {@code !isTemplateActionForTreeArtifact()}.
     */
    @Nullable
    ActionTemplateExpansion getActionTemplateExpansion(SkyFunction.Environment env)
        throws InterruptedException {
      Preconditions.checkState(
          isTemplateActionForTreeArtifact(), "Action is unexpectedly non-template: %s", this);
      ActionTemplateExpansionValue.ActionTemplateExpansionKey key =
          ActionTemplateExpansionValue.key(
              artifact.getArtifactOwner(), artifact.getGeneratingActionKey().getActionIndex());
      // This call may throw an ActionExecutionFunction that bubbles up.
      ActionTemplateExpansionValue value = (ActionTemplateExpansionValue) env.getValue(key);
      return value == null ? null : new ActionTemplateExpansion(value);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("artifact", artifact)
          .add("generatingActionKey", artifact.getGeneratingActionKey())
          .add("actionLookupValue", actionLookupValue)
          .toString();
    }
  }

  static class ActionTemplateExpansion {
    private final ActionTemplateExpansionValue value;

    private ActionTemplateExpansion(ActionTemplateExpansionValue value) {
      this.value = value;
    }

    ActionTemplateExpansionValue getValue() {
      return value;
    }

    ImmutableList<ActionLookupData> getExpandedActionExecutionKeys() {
      int numActions = value.getNumActions();
      ImmutableList.Builder<ActionLookupData> expandedActionExecutionKeys =
          ImmutableList.builderWithExpectedSize(numActions);
      for (ActionAnalysisMetadata action : value.getActions()) {
        expandedActionExecutionKeys.add(
            ((DerivedArtifact) action.getPrimaryOutput()).getGeneratingActionKey());
      }
      return expandedActionExecutionKeys.build();
    }
  }

  static final class SourceArtifactException extends Exception implements DetailedException {
    private final DetailedExitCode detailedExitCode;

    private SourceArtifactException(DetailedExitCode detailedExitCode, Exception e) {
      super(detailedExitCode.getFailureDetail().getMessage(), e);
      this.detailedExitCode = detailedExitCode;
    }

    private static SourceArtifactException create(Artifact artifact, IOException e) {
      DetailedExitCode detailedExitCode =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(
                      constructErrorMessage(artifact, "error reading file") + ": " + e.getMessage())
                  .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
                  .build());
      return new SourceArtifactException(detailedExitCode, e);
    }

    private static SourceArtifactException create(
        Artifact artifact, RecursiveFilesystemTraversalException e) {
      FailureDetail failureDetail =
          FailureDetail.newBuilder()
              .setMessage(
                  constructErrorMessage(artifact, "error traversing directory")
                      + ": "
                      + e.getMessage())
              .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
              .build();
      return new SourceArtifactException(DetailedExitCode.of(failureDetail), e);
    }

    @Override
    public DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }
}
