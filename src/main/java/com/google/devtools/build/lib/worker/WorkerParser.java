// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.regex.Pattern;

/**
 * A helper class to process a {@link Spawn} into a {@link WorkerKey}, which is used to select a
 * persistent worker process (actions with equal keys are allowed to use the same worker process),
 * and a separate list of flag files. The result is encapsulated as a {@link WorkerConfig}.
 */
class WorkerParser {
  public static final String ERROR_MESSAGE_PREFIX =
      "Worker strategy cannot execute this %s action, ";
  public static final String REASON_NO_FLAGFILE =
      "because the command-line arguments do not contain at least one @flagfile or --flagfile=";

  /** Pattern for @flagfile.txt and --flagfile=flagfile.txt */
  private static final Pattern FLAG_FILE_PATTERN = Pattern.compile("(?:@|--?flagfile=)(.+)");

  /** The global execRoot. */
  private final Path execRoot;

  private final WorkerOptions workerOptions;
  private final LocalEnvProvider localEnvProvider;
  private final BinTools binTools;

  public WorkerParser(
      Path execRoot,
      WorkerOptions workerOptions,
      LocalEnvProvider localEnvProvider,
      BinTools binTools) {
    this.execRoot = execRoot;
    this.workerOptions = workerOptions;
    this.localEnvProvider = localEnvProvider;
    this.binTools = binTools;
  }

  /**
   * Processes the given {@link Spawn} and {@link SpawnExecutionContext} to compute the worker key.
   * This involves splitting the command line into the worker startup command and the separate list
   * of flag files. Returns a pair of the {@link WorkerKey} and list of flag files.
   */
  public WorkerConfig compute(Spawn spawn, SpawnExecutionContext context)
      throws ExecException, IOException, InterruptedException {
    // We assume that the spawn to be executed always gets at least one @flagfile.txt or
    // --flagfile=flagfile.txt argument, which contains the flags related to the work itself (as
    // opposed to start-up options for the executed tool). Thus, we can extract those elements from
    // its args and put them into the WorkRequest instead.
    List<String> flagFiles = new ArrayList<>();
    ImmutableList<String> workerArgs = splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles);
    ImmutableMap<String, String> env =
        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");

    SortedMap<PathFragment, byte[]> workerFiles =
        WorkerFilesHash.getWorkerFilesWithDigests(
            spawn, context.getArtifactExpander(), context.getMetadataProvider());

    HashCode workerFilesCombinedHash = WorkerFilesHash.getCombinedHash(workerFiles);

    WorkerProtocolFormat protocolFormat = Spawns.getWorkerProtocolFormat(spawn);
    if (!workerOptions.experimentalJsonWorkerProtocol) {
      if (protocolFormat == WorkerProtocolFormat.JSON) {
        throw new IOException(
            "Persistent worker protocol format must be set to proto unless"
                + " --experimental_worker_allow_json_protocol is used");
      }
    }
    WorkerKey key =
        createWorkerKey(
            spawn,
            workerArgs,
            env,
            execRoot,
            workerFilesCombinedHash,
            workerFiles,
            workerOptions,
            context.speculating(),
            protocolFormat);
    return new WorkerConfig(key, flagFiles);
  }

  /**
   * This method handles the logic of creating a WorkerKey (e.g., if sandboxing should be enabled or
   * not, when to use multiplex-workers)
   */
  @VisibleForTesting
  static WorkerKey createWorkerKey(
      Spawn spawn,
      ImmutableList<String> workerArgs,
      ImmutableMap<String, String> env,
      Path execRoot,
      HashCode workerFilesCombinedHash,
      SortedMap<PathFragment, byte[]> workerFiles,
      WorkerOptions options,
      boolean dynamic,
      WorkerProtocolFormat protocolFormat) {
    boolean multiplex = options.workerMultiplex && Spawns.supportsMultiplexWorkers(spawn);
    if (dynamic && !(Spawns.supportsMultiplexSandboxing(spawn) && options.multiplexSandboxing)) {
      multiplex = false;
    }
    boolean sandboxed;
    if (multiplex) {
      sandboxed =
          Spawns.supportsMultiplexSandboxing(spawn) && (options.multiplexSandboxing || dynamic);
    } else {
      sandboxed = options.workerSandboxing || dynamic;
    }
    return new WorkerKey(
        workerArgs,
        env,
        execRoot,
        Spawns.getWorkerKeyMnemonic(spawn),
        workerFilesCombinedHash,
        workerFiles,
        sandboxed,
        multiplex,
        Spawns.supportsWorkerCancellation(spawn),
        protocolFormat);
  }

  /**
   * Splits the command-line arguments of the {@code Spawn} into the part that is used to start the
   * persistent worker ({@code workerArgs}) and the part that goes into the {@code WorkRequest}
   * protobuf ({@code flagFiles}).
   */
  private ImmutableList<String> splitSpawnArgsIntoWorkerArgsAndFlagFiles(
      Spawn spawn, List<String> flagFiles) throws UserExecException {
    ImmutableList.Builder<String> workerArgs = ImmutableList.builder();
    for (String arg : spawn.getArguments()) {
      if (FLAG_FILE_PATTERN.matcher(arg).matches()) {
        flagFiles.add(arg);
      } else {
        workerArgs.add(arg);
      }
    }

    if (flagFiles.isEmpty()) {
      throw new UserExecException(
          FailureDetails.FailureDetail.newBuilder()
              .setMessage(
                  String.format(ERROR_MESSAGE_PREFIX + REASON_NO_FLAGFILE, spawn.getMnemonic()))
              .setWorker(
                  FailureDetails.Worker.newBuilder()
                      .setCode(FailureDetails.Worker.Code.NO_FLAGFILE))
              .build());
    }

    ImmutableList.Builder<String> mnemonicFlags = ImmutableList.builder();

    workerOptions.workerExtraFlags.stream()
        .filter(entry -> entry.getKey().equals(spawn.getMnemonic()))
        .forEach(entry -> mnemonicFlags.add(entry.getValue()));

    return workerArgs
        .add("--persistent_worker")
        .addAll(MoreObjects.firstNonNull(mnemonicFlags.build(), ImmutableList.of()))
        .build();
  }

  /** A pair of the {@link WorkerKey} and the list of flag files. */
  public static class WorkerConfig {
    private final WorkerKey workerKey;
    private final List<String> flagFiles;

    public WorkerConfig(WorkerKey workerKey, List<String> flagFiles) {
      this.workerKey = workerKey;
      this.flagFiles = ImmutableList.copyOf(flagFiles);
    }

    public WorkerKey getWorkerKey() {
      return workerKey;
    }

    public List<String> getFlagFiles() {
      return flagFiles;
    }
  }
}
