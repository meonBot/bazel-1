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
package com.google.devtools.build.skyframe;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.testutil.EventIterableSubjectFactory.assertThatEvents;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static com.google.devtools.build.skyframe.GraphTester.CONCATENATE;
import static com.google.devtools.build.skyframe.GraphTester.skyKey;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.AtomicLongMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.skyframe.EvaluationContext.UnnecessaryTemporaryStateDropper;
import com.google.devtools.build.skyframe.EvaluationContext.UnnecessaryTemporaryStateDropperReceiver;
import com.google.devtools.build.skyframe.GraphTester.StringValue;
import com.google.devtools.build.skyframe.NotifyingHelper.EventType;
import com.google.devtools.build.skyframe.NotifyingHelper.Order;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Tests for {@link ParallelEvaluator}. */
@RunWith(TestParameterInjector.class)
public class ParallelEvaluatorTest {
  private static final SkyFunctionName CHILD_TYPE = SkyFunctionName.createHermetic("child");
  private static final SkyFunctionName PARENT_TYPE = SkyFunctionName.createHermetic("parent");

  protected ProcessableGraph graph;
  protected IntVersion graphVersion = IntVersion.of(0);
  protected GraphTester tester = new GraphTester();

  private StoredEventHandler storedEventHandler;

  private DirtyTrackingProgressReceiver revalidationReceiver =
      new DirtyTrackingProgressReceiver(null);

  @Before
  public void initializeReporter() {
    storedEventHandler = new StoredEventHandler();
  }

  @After
  public void assertNoTrackedErrors() {
    TrackingAwaiter.INSTANCE.assertNoErrors();
  }

  private ParallelEvaluator makeEvaluator(
      ProcessableGraph graph,
      ImmutableMap<SkyFunctionName, SkyFunction> builders,
      boolean keepGoing,
      EventFilter storedEventFilter) {
    Version oldGraphVersion = graphVersion;
    graphVersion = graphVersion.next();
    return new ParallelEvaluator(
        graph,
        oldGraphVersion,
        builders,
        storedEventHandler,
        new MemoizingEvaluator.EmittedEventState(),
        storedEventFilter,
        ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
        keepGoing,
        revalidationReceiver,
        GraphInconsistencyReceiver.THROWING,
        () -> AbstractQueueVisitor.createExecutorService(200, "test-pool"),
        new SimpleCycleDetector(),
        /*cpuHeavySkyKeysThreadPoolSize=*/ 0,
        /*executionJobsThreadPoolSize=*/ 0,
        UnnecessaryTemporaryStateDropperReceiver.NULL);
  }

  private ParallelEvaluator makeEvaluator(
      ProcessableGraph graph,
      ImmutableMap<SkyFunctionName, SkyFunction> builders,
      boolean keepGoing) {
    return makeEvaluator(
        graph, builders, keepGoing, InMemoryMemoizingEvaluator.DEFAULT_STORED_EVENT_FILTER);
  }

  /** Convenience method for eval-ing a single value. */
  protected SkyValue eval(boolean keepGoing, SkyKey key) throws InterruptedException {
    return eval(keepGoing, ImmutableList.of(key)).get(key);
  }

  protected <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, SkyKey... keys)
      throws InterruptedException {
    return eval(keepGoing, ImmutableList.copyOf(keys));
  }

  protected <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, Iterable<SkyKey> keys)
      throws InterruptedException {
    ParallelEvaluator evaluator = makeEvaluator(graph, tester.getSkyFunctionMap(), keepGoing);
    return evaluator.eval(keys);
  }

  protected ErrorInfo evalValueInError(SkyKey key) throws InterruptedException {
    return eval(true, ImmutableList.of(key)).getError(key);
  }

  protected GraphTester.TestFunction set(String name, String value) {
    return tester.set(name, new StringValue(value));
  }

  @Test
  public void smoke() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a");
    set("b", "b");
    tester.getOrCreate("ab").addDependency("a").addDependency("b").setComputedValue(CONCATENATE);
    StringValue value = (StringValue) eval(false, GraphTester.toSkyKey("ab"));
    assertThat(value.getValue()).isEqualTo("ab");
    assertThat(storedEventHandler.getEvents()).isEmpty();
    assertThat(storedEventHandler.getPosts()).isEmpty();
  }

  @Test
  public void enqueueDoneFuture() throws Exception {
    final SkyKey parentKey = GraphTester.toSkyKey("parentKey");
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              SettableFuture<SkyValue> future = SettableFuture.create();
              future.set(new StringValue("good"));
              env.dependOnFuture(future);
              assertThat(env.valuesMissing()).isFalse();
              try {
                return future.get();
              } catch (ExecutionException e) {
                throw new RuntimeException(e);
              }
            });
    graph = new InMemoryGraphImpl();
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(parentKey)).isEqualTo(new StringValue("good"));
  }

  @Test
  public void enqueueBadFuture() throws Exception {
    final SkyKey parentKey = GraphTester.toSkyKey("parentKey");
    final CountDownLatch doneLatch = new CountDownLatch(1);
    final ListeningExecutorService executor =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            new SkyFunction() {
              private ListenableFuture<SkyValue> future;

              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) {
                if (future == null) {
                  future =
                      executor.submit(
                          () -> {
                            doneLatch.await();
                            throw new UnsupportedOperationException();
                          });
                  env.dependOnFuture(future);
                  assertThat(env.valuesMissing()).isTrue();
                  return null;
                }
                assertThat(future.isDone()).isTrue();
                ExecutionException expected =
                    assertThrows(ExecutionException.class, () -> future.get());
                assertThat(expected.getCause()).isInstanceOf(UnsupportedOperationException.class);
                return new StringValue("Caught!");
              }
            });
    graph =
        NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  // NodeEntry.addExternalDep is called as part of bookkeeping at the end of
                  // AbstractParallelEvaluator.Evaluate#run.
                  if (key == parentKey && type == EventType.ADD_EXTERNAL_DEP) {
                    doneLatch.countDown();
                  }
                })
            .transform(new InMemoryGraphImpl());
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(parentKey)).isEqualTo(new StringValue("Caught!"));
  }

  @Test
  public void dependsOnKeyAndFuture() throws Exception {
    final SkyKey parentKey = GraphTester.toSkyKey("parentKey");
    final SkyKey childKey = GraphTester.toSkyKey("childKey");
    final CountDownLatch doneLatch = new CountDownLatch(1);
    tester.getOrCreate(childKey).setConstantValue(new StringValue("child"));
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            new SkyFunction() {
              private SettableFuture<SkyValue> future;

              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                SkyValue child = env.getValue(childKey);
                if (future == null) {
                  assertThat(child).isNull();
                  future = SettableFuture.create();
                  env.dependOnFuture(future);
                  assertThat(env.valuesMissing()).isTrue();
                  new Thread(
                          () -> {
                            try {
                              doneLatch.await();
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                            future.set(new StringValue("future"));
                          })
                      .start();
                  return null;
                }
                assertThat(child).isEqualTo(new StringValue("child"));
                assertThat(future.isDone()).isTrue();
                try {
                  assertThat(future.get()).isEqualTo(new StringValue("future"));
                } catch (ExecutionException e) {
                  throw new RuntimeException(e);
                }
                return new StringValue("All done!");
              }
            });
    graph =
        NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (key == childKey && type == EventType.SET_VALUE) {
                    doneLatch.countDown();
                  }
                })
            .transform(new InMemoryGraphImpl());
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(parentKey)).isEqualTo(new StringValue("All done!"));
  }

  /** Test interruption handling when a long-running SkyFunction gets interrupted. */
  @Test
  public void interruptedFunction() throws Exception {
    runInterruptionTest(
        (threadStarted, errorMessage) ->
            (key, env) -> {
              // Signal the waiting test thread that the evaluator thread has really started.
              threadStarted.release();

              // Simulate a SkyFunction that runs for 10 seconds (this number was chosen
              // arbitrarily). The main thread should interrupt it shortly after it got started.
              Thread.sleep(10 * 1000);

              // Set an error message to indicate that the expected interruption didn't happen.
              // We can't use Assert.fail(String) on an async thread.
              errorMessage[0] = "SkyFunction should have been interrupted";
              return null;
            });
  }

  /**
   * Test interruption handling when the Evaluator is in-between running SkyFunctions.
   *
   * <p>This is the point in time after a SkyFunction requested a dependency which is not yet built
   * so the builder returned null to the Evaluator, and the latter is about to schedule evaluation
   * of the missing dependency but gets interrupted before the dependency's SkyFunction could start.
   */
  @Test
  public void interruptedEvaluatorThread() throws Exception {
    runInterruptionTest(
        (threadStarted, errorMessage) ->
            new SkyFunction() {
              // No need to synchronize access to this field; we always request just one more
              // dependency, so it's only one SkyFunction running at any time.
              private int valueIdCounter = 0;

              @Override
              public SkyValue compute(SkyKey key, Environment env) throws InterruptedException {
                // Signal the waiting test thread that the Evaluator thread has really started.
                threadStarted.release();

                // Keep the evaluator busy until the test's thread gets scheduled and can
                // interrupt the Evaluator's thread.
                env.getValue(GraphTester.toSkyKey("a" + valueIdCounter++));

                // This method never throws InterruptedException, therefore it's the responsibility
                // of the Evaluator to detect the interrupt and avoid calling subsequent
                // SkyFunctions.
                return null;
              }
            });
  }

  @Test
  public void interruptedEvaluatorThreadAfterEnqueueBeforeWaitForCompletionAndConstructResult()
      throws InterruptedException {
    // This is a regression test for a crash bug in
    // AbstractExceptionalParallelEvaluator#doMutatingEvaluation in a very specific window of time
    // between enqueueing one top-level node for evaluation and checking if another top-level node
    // is done.

    // When we have two top-level nodes, A and B,
    SkyKey keyA = GraphTester.toSkyKey("a");
    SkyKey keyB = GraphTester.toSkyKey("b");

    // And rig the graph and node entries, such that B's addReverseDepAndCheckIfDone waits for A to
    // start computing and then tries to observe an interrupt (which will happen on the calling
    // thread, aka the main Skyframe evaluation thread),
    CountDownLatch keyAStartedComputingLatch = new CountDownLatch(1);
    CountDownLatch keyBAddReverseDepAndCheckIfDoneLatch = new CountDownLatch(1);
    InMemoryNodeEntry nodeEntryB = mock(InMemoryNodeEntry.class);
    AtomicBoolean keyBAddReverseDepAndCheckIfDoneInterrupted = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              keyAStartedComputingLatch.await();
              keyBAddReverseDepAndCheckIfDoneLatch.countDown();
              try {
                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                throw new IllegalStateException("shouldn't get here");
              } catch (InterruptedException e) {
                keyBAddReverseDepAndCheckIfDoneInterrupted.set(true);
                throw e;
              }
            })
        .when(nodeEntryB)
        .addReverseDepAndCheckIfDone(eq(null));
    graph =
        new InMemoryGraphImpl() {
          @Override
          protected InMemoryNodeEntry newNodeEntry(SkyKey key) {
            return key.equals(keyB) ? nodeEntryB : super.newNodeEntry(key);
          }
        };
    // And A's SkyFunction tries to observe an interrupt after it starts computing,
    AtomicBoolean keyAComputeInterrupted = new AtomicBoolean(false);
    tester
        .getOrCreate(keyA)
        .setBuilder(
            (skyKey, env) -> {
              keyAStartedComputingLatch.countDown();
              try {
                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                throw new IllegalStateException("shouldn't get here");
              } catch (InterruptedException e) {
                keyAComputeInterrupted.set(true);
                throw e;
              }
            });

    // And we have a dedicated thread that kicks off the evaluation of A and B together (in that
    // order).
    TestThread evalThread =
        new TestThread(
            () ->
                assertThrows(
                    InterruptedException.class, () -> eval(/*keepGoing=*/ true, keyA, keyB)));

    // Then when we start that thread,
    evalThread.start();
    // We (the thread running the test) are able to observe that B's addReverseDepAndCheckIfDone has
    // just been called (implying that A has started to be computed).
    assertThat(
            keyBAddReverseDepAndCheckIfDoneLatch.await(
                TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .isTrue();
    // Then when we interrupt the evaluation thread,
    evalThread.interrupt();
    // The evaluation thread eventually terminates.
    evalThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    // And we are able to verify both that A's SkyFunction had observed an interrupt,
    assertThat(keyAComputeInterrupted.get()).isTrue();
    // And also that B's addReverseDepAndCheckIfDoneInterrupted had observed an interrupt.
    assertThat(keyBAddReverseDepAndCheckIfDoneInterrupted.get()).isTrue();
  }

  @Test
  public void runPartialResultOnInterruption(@TestParameter boolean buildFastFirst)
      throws Exception {
    graph = new InMemoryGraphImpl();
    // Two runs for fastKey's builder and one for the start of waitKey's builder.
    final CountDownLatch allValuesReady = new CountDownLatch(3);
    final SkyKey waitKey = GraphTester.toSkyKey("wait");
    final SkyKey fastKey = GraphTester.toSkyKey("fast");
    SkyKey leafKey = GraphTester.toSkyKey("leaf");
    tester
        .getOrCreate(waitKey)
        .setBuilder(
            (skyKey, env) -> {
              allValuesReady.countDown();
              Thread.sleep(10000);
              throw new AssertionError("Should have been interrupted");
            });
    tester
        .getOrCreate(fastKey)
        .setBuilder(
            new ChainedFunction(
                null,
                null,
                allValuesReady,
                false,
                new StringValue("fast"),
                ImmutableList.of(leafKey)));
    tester.set(leafKey, new StringValue("leaf"));
    if (buildFastFirst) {
      eval(/*keepGoing=*/ false, fastKey);
    }
    final Set<SkyKey> receivedValues = Sets.newConcurrentHashSet();
    revalidationReceiver =
        new DirtyTrackingProgressReceiver(
            new EvaluationProgressReceiver() {
              @Override
              public void evaluated(
                  SkyKey skyKey,
                  @Nullable SkyValue newValue,
                  @Nullable ErrorInfo newError,
                  Supplier<EvaluationSuccessState> evaluationSuccessState,
                  EvaluationState state) {
                receivedValues.add(skyKey);
              }
            });
    TestThread evalThread =
        new TestThread(
            () ->
                assertThrows(
                    InterruptedException.class, () -> eval(/*keepGoing=*/ true, waitKey, fastKey)));
    evalThread.start();
    assertThat(allValuesReady.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    evalThread.interrupt();
    evalThread.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    assertThat(evalThread.isAlive()).isFalse();
    if (buildFastFirst) {
      // If leafKey was already built, it is not reported to the receiver.
      assertThat(receivedValues).containsExactly(fastKey);
    } else {
      // On first time being built, leafKey is registered too.
      assertThat(receivedValues).containsExactly(fastKey, leafKey);
    }
  }

  /** Factory for SkyFunctions for interruption testing (see {@link #runInterruptionTest}). */
  private interface SkyFunctionFactory {
    /**
     * Creates a SkyFunction suitable for a specific test scenario.
     *
     * @param threadStarted a latch which the returned SkyFunction must {@link Semaphore#release()
     *     release} once it started (otherwise the test won't work)
     * @param errorMessage a single-element array; the SkyFunction can put a error message in it to
     *     indicate that an assertion failed (calling {@code fail} from async thread doesn't work)
     */
    SkyFunction create(final Semaphore threadStarted, final String[] errorMessage);
  }

  /**
   * Test that we can handle the Evaluator getting interrupted at various points.
   *
   * <p>This method creates an Evaluator with the specified SkyFunction for GraphTested.NODE_TYPE,
   * then starts a thread, requests evaluation and asserts that evaluation started. It then
   * interrupts the Evaluator thread and asserts that it acknowledged the interruption.
   *
   * @param valueBuilderFactory creates a SkyFunction which may or may not handle interruptions
   *     (depending on the test)
   */
  private void runInterruptionTest(SkyFunctionFactory valueBuilderFactory) throws Exception {
    final Semaphore threadStarted = new Semaphore(0);
    final Semaphore threadInterrupted = new Semaphore(0);
    final String[] wasError = new String[] {null};
    final ParallelEvaluator evaluator =
        makeEvaluator(
            new InMemoryGraphImpl(),
            ImmutableMap.of(
                GraphTester.NODE_TYPE, valueBuilderFactory.create(threadStarted, wasError)),
            false);

    Thread t =
        new Thread(
            () -> {
              try {
                evaluator.eval(ImmutableList.of(GraphTester.toSkyKey("a")));

                // There's no real need to set an error here. If the thread is not interrupted then
                // threadInterrupted is not released and the test thread will fail to acquire it.
                wasError[0] = "evaluation should have been interrupted";
              } catch (InterruptedException e) {
                // This is the interrupt we are waiting for. It should come straight from the
                // evaluator (more precisely, the AbstractQueueVisitor).
                // Signal the waiting test thread that the interrupt was acknowledged.
                threadInterrupted.release();
              }
            });

    // Start the thread and wait for a semaphore. This ensures that the thread was really started.
    t.start();
    assertThat(threadStarted.tryAcquire(TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS))
        .isTrue();

    // Interrupt the thread and wait for a semaphore. This ensures that the thread was really
    // interrupted and this fact was acknowledged.
    t.interrupt();
    assertThat(
            threadInterrupted.tryAcquire(
                TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS))
        .isTrue();

    // The SkyFunction may have reported an error.
    if (wasError[0] != null) {
      fail(wasError[0]);
    }

    // Wait for the thread to finish.
    t.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
  }

  @Test
  public void unrecoverableError() throws Exception {
    class CustomRuntimeException extends RuntimeException {}
    final CustomRuntimeException expected = new CustomRuntimeException();

    SkyFunction builder =
        new SkyFunction() {
          @Override
          @Nullable
          public SkyValue compute(SkyKey skyKey, Environment env) {
            throw expected;
          }
        };

    ParallelEvaluator evaluator =
        makeEvaluator(
            new InMemoryGraphImpl(), ImmutableMap.of(GraphTester.NODE_TYPE, builder), false);

    SkyKey valueToEval = GraphTester.toSkyKey("a");
    RuntimeException re =
        assertThrows(RuntimeException.class, () -> evaluator.eval(ImmutableList.of(valueToEval)));
    assertThat(re)
        .hasMessageThat()
        .contains("Unrecoverable error while evaluating node '" + valueToEval.toString() + "'");
    assertThat(re).hasCauseThat().isInstanceOf(CustomRuntimeException.class);
  }

  @Test
  public void simpleWarning() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a").setWarning("warning on 'a'");
    StringValue value = (StringValue) eval(false, GraphTester.toSkyKey("a"));
    assertThat(value.getValue()).isEqualTo("a");
    assertThatEvents(storedEventHandler.getEvents()).containsExactly("warning on 'a'");
  }

  /** Regression test: events from already-done value not replayed. */
  @Test
  public void eventFromDoneChildRecorded() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a").setWarning("warning on 'a'");
    SkyKey a = GraphTester.toSkyKey("a");
    SkyKey top = GraphTester.toSkyKey("top");
    tester.getOrCreate(top).addDependency(a).setComputedValue(CONCATENATE);
    // Build a so that it is already in the graph.
    eval(false, a);
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    storedEventHandler.clear();
    // Build top. The warning from a should be printed.
    eval(false, top);
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    storedEventHandler.clear();
    // Build top again. The warning should have been stored in the value.
    eval(false, top);
    assertThat(storedEventHandler.getEvents()).hasSize(1);
  }

  @Test
  public void postableFromDoneChildRecorded() throws Exception {
    graph = new InMemoryGraphImpl();
    Postable post = new Postable() {};
    set("a", "a").setPostable(post);
    SkyKey a = GraphTester.toSkyKey("a");
    SkyKey top = GraphTester.toSkyKey("top");
    tester.getOrCreate(top).addDependency(a).setComputedValue(CONCATENATE);
    // Build a so that it is already in the graph.
    eval(false, a);
    assertThat(storedEventHandler.getPosts()).containsExactly(post);
    storedEventHandler.clear();
    // Build top. The post from a should be printed.
    eval(false, top);
    assertThat(storedEventHandler.getPosts()).containsExactly(post);
    storedEventHandler.clear();
    // Build top again. The post should have been stored in the value.
    eval(false, top);
    assertThat(storedEventHandler.getPosts()).containsExactly(post);
  }

  @Test
  public void eventReportedTimely() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a").setWarning("warning on 'a'");
    SkyKey a = GraphTester.toSkyKey("a");
    SkyKey top = GraphTester.toSkyKey("top");
    tester
        .getOrCreate(top)
        .setBuilder(
            (key, env) -> {
              // The event from a should already have been posted.
              assertThat(storedEventHandler.getEvents()).hasSize(1);
              return new StringValue("foo");
            });
    // Build a so that it is already in the graph.
    eval(false, a);
    storedEventHandler.clear();
    // Build top. The warning from a should be printed before evaluating top.
    eval(false, ImmutableList.of(a, top));
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    storedEventHandler.clear();
  }

  @Test
  public void errorOfTopLevelTargetReported() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey a = GraphTester.toSkyKey("a");
    SkyKey b = GraphTester.toSkyKey("b");
    tester.getOrCreate(b).setHasError(true);
    Event errorEvent = Event.error("foobar");
    tester
        .getOrCreate(a)
        .setBuilder(
            (key, env) -> {
              try {
                if (env.getValueOrThrow(b, SomeErrorException.class) == null) {
                  return null;
                }
              } catch (SomeErrorException ignored) {
                // Continue silently.
              }
              env.getListener().handle(errorEvent);
              throw new SkyFunctionException(
                  new SomeErrorException("bazbar"), Transience.PERSISTENT) {};
            });
    eval(false, a);
    assertThat(storedEventHandler.getEvents()).containsExactly(errorEvent);
  }

  @Test
  public void storedEventFilter() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey a = GraphTester.toSkyKey("a");
    final AtomicBoolean evaluated = new AtomicBoolean(false);
    tester
        .getOrCreate(a)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) {
                evaluated.set(true);
                env.getListener().handle(Event.error(null, "boop"));
                env.getListener().handle(Event.warn(null, "beep"));
                return new StringValue("a");
              }
            });
    ParallelEvaluator evaluator =
        makeEvaluator(
            graph,
            tester.getSkyFunctionMap(),
            /*keepGoing=*/ false,
            new EventFilter() {
              @Override
              public boolean apply(Event event) {
                return event.getKind() == EventKind.ERROR;
              }

              @Override
              public boolean storeEventsAndPosts() {
                return true;
              }
            });
    evaluator.eval(ImmutableList.of(a));
    assertThat(evaluated.get()).isTrue();
    assertThat(storedEventHandler.getEvents()).hasSize(2);
    assertThatEvents(storedEventHandler.getEvents()).containsExactly("boop", "beep");
    storedEventHandler.clear();
    evaluator = makeEvaluator(graph, tester.getSkyFunctionMap(), /*keepGoing=*/ false);
    evaluated.set(false);
    evaluator.eval(ImmutableList.of(a));
    assertThat(evaluated.get()).isFalse();
    assertThatEvents(storedEventHandler.getEvents()).containsExactly("boop");
  }

  @Test
  public void shouldCreateErrorValueWithRootCause() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a");
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester
        .getOrCreate(parentErrorKey)
        .addDependency("a")
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(errorKey).setHasError(true);
    evalValueInError(parentErrorKey);
  }

  @Test
  public void shouldBuildOneTarget() throws Exception {
    graph = new InMemoryGraphImpl();
    set("a", "a");
    set("b", "b");
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    SkyKey errorFreeKey = GraphTester.toSkyKey("ab");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester
        .getOrCreate(parentErrorKey)
        .addDependency(errorKey)
        .addDependency("a")
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(errorKey).setHasError(true);
    tester
        .getOrCreate(errorFreeKey)
        .addDependency("a")
        .addDependency("b")
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(true, parentErrorKey, errorFreeKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(parentErrorKey);
    assertThatEvaluationResult(result).hasEntryThat(errorFreeKey).isEqualTo(new StringValue("ab"));
  }

  @Test
  public void catastrophicBuild(@TestParameter boolean keepGoing, @TestParameter boolean keepEdges)
      throws Exception {
    Assume.assumeTrue(keepGoing || keepEdges);

    graph = keepEdges ? InMemoryGraph.create() : InMemoryGraph.createEdgeless();

    SkyKey catastropheKey = GraphTester.toSkyKey("catastrophe");
    SkyKey otherKey = GraphTester.toSkyKey("someKey");

    Exception catastrophe = new SomeErrorException("bad");
    tester
        .getOrCreate(catastropheKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
                throw new SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                  @Override
                  public boolean isCatastrophic() {
                    return true;
                  }
                };
              }
            });

    tester
        .getOrCreate(otherKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                new CountDownLatch(1).await();
                throw new RuntimeException("can't get here");
              }
            });

    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(catastropheKey).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(keepGoing, topKey, otherKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey);
    if (keepGoing) {
      assertThat(result.getCatastrophe()).isSameInstanceAs(catastrophe);
    }
  }

  @Test
  public void topCatastrophe() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey catastropheKey = GraphTester.toSkyKey("catastrophe");
    Exception catastrophe = new SomeErrorException("bad");
    tester
        .getOrCreate(catastropheKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
                throw new SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                  @Override
                  public boolean isCatastrophic() {
                    return true;
                  }
                };
              }
            });

    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/ true, ImmutableList.of(catastropheKey));
    assertThat(result.getCatastrophe()).isEqualTo(catastrophe);
  }

  @Test
  public void catastropheBubblesIntoNonCatastrophe() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey catastropheKey = GraphTester.toSkyKey("catastrophe");
    Exception catastrophe = new SomeErrorException("bad");
    tester
        .getOrCreate(catastropheKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
                throw new SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                  @Override
                  public boolean isCatastrophic() {
                    return true;
                  }
                };
              }
            });
    SkyKey topKey = skyKey("top");
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                try {
                  env.getValueOrThrow(catastropheKey, SomeErrorException.class);
                } catch (SomeErrorException e) {
                  throw new SkyFunctionException(
                      new SomeErrorException("We got: " + e.getMessage()), Transience.PERSISTENT) {
                  };
                }
                return null;
              }
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));

    assertThat(result.getError(topKey).getException()).isInstanceOf(SomeErrorException.class);
    assertThat(result.getError(topKey).getException()).hasMessageThat().isEqualTo("We got: bad");
    assertThat(result.getCatastrophe()).isNotNull();
  }

  @Test
  public void incrementalCycleWithCatastropheAndFailedBubbleUp() throws Exception {
    SkyKey topKey = GraphTester.toSkyKey("top");
    // Comes alphabetically before "top".
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey catastropheKey = GraphTester.toSkyKey("catastrophe");
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    StringValue topValue = new StringValue("top");
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                env.getValues(ImmutableList.of(cycleKey));
                return env.valuesMissing() ? null : topValue;
              }
            });
    tester
        .getOrCreate(cycleKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                env.getValues(ImmutableList.of(cycleKey, catastropheKey));
                Preconditions.checkState(env.valuesMissing());
                return null;
              }
            });
    tester
        .getOrCreate(catastropheKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
                throw new SkyFunctionException(
                    new SomeErrorException("catastrophe"), Transience.TRANSIENT) {
                  @Override
                  public boolean isCatastrophic() {
                    return true;
                  }
                };
              }
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(topKey)
        .hasCycleInfoThat()
        .containsExactly(new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(cycleKey)));
  }

  @Test
  public void parentFailureDoesntAffectChild() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).setHasError(true);
    SkyKey childKey = GraphTester.toSkyKey("child");
    set("child", "onions");
    tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, parentKey, childKey);
    // Child is guaranteed to complete successfully before parent can run (and fail),
    // since parent depends on it.
    assertThatEvaluationResult(result).hasEntryThat(childKey).isEqualTo(new StringValue("onions"));
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(parentKey);
  }

  @Test
  public void newParentOfErrorShouldHaveError() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    evalValueInError(errorKey);
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency("error").setComputedValue(CONCATENATE);
    evalValueInError(parentKey);
  }

  @Test
  public void errorTwoLevelsDeep() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(CONCATENATE);
    evalValueInError(parentKey);
  }

  @Test
  public void valueNotUsedInFailFastErrorRecovery() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey recoveryKey = GraphTester.toSkyKey("midRecovery");
    SkyKey badKey = GraphTester.toSkyKey("bad");

    tester.getOrCreate(topKey).addDependency(recoveryKey).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(recoveryKey)
        .addErrorDependency(badKey, new StringValue("i recovered"))
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);

    EvaluationResult<SkyValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(recoveryKey));
    assertThat(result.errorMap()).isEmpty();
    assertThatEvaluationResult(result).hasNoError();
    assertThat(result.get(recoveryKey)).isEqualTo(new StringValue("i recovered"));

    result = eval(/*keepGoing=*/ false, ImmutableList.of(topKey));
    assertThatEvaluationResult(result).hasError();
    assertThat(result.keyNames()).isEmpty();
    assertThat(result.errorMap()).hasSize(1);
    assertThat(result.getError(topKey).getException()).isNotNull();
  }

  @Test
  public void multipleRootCauses() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey errorKey2 = GraphTester.toSkyKey("error2");
    SkyKey errorKey3 = GraphTester.toSkyKey("error3");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate(errorKey2).setHasError(true);
    tester.getOrCreate(errorKey3).setHasError(true);
    tester
        .getOrCreate("mid")
        .addDependency(errorKey)
        .addDependency(errorKey2)
        .setComputedValue(CONCATENATE);
    tester
        .getOrCreate(parentKey)
        .addDependency("mid")
        .addDependency(errorKey2)
        .addDependency(errorKey3)
        .setComputedValue(CONCATENATE);
    evalValueInError(parentKey);
  }

  @Test
  public void rootCauseWithNoKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey);
  }

  @Test
  public void errorBubblesToParentsOfTopLevelValue() throws Exception {
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    CountDownLatch latch = new CountDownLatch(1);
    graph =
        new NotifyingHelper.NotifyingProcessableGraph(
            new InMemoryGraphImpl(),
            (key, type, order, context) -> {
              if (key.equals(errorKey)
                  && parentKey.equals(context)
                  && type == EventType.ADD_REVERSE_DEP
                  && order == Order.AFTER) {
                latch.countDown();
              }
            });
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                null, /*waitToFinish=*/ latch, null, false, /*value=*/ null, ImmutableList.of()));
    tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/ false, ImmutableList.of(parentKey, errorKey));
    assertWithMessage(result.toString()).that(result.errorMap().size()).isEqualTo(2);
  }

  @Test
  public void noKeepGoingAfterKeepGoingFails() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency(errorKey);
    evalValueInError(parentKey);
    SkyKey[] list = {parentKey};
    EvaluationResult<StringValue> result = eval(false, list);
    assertThatEvaluationResult(result)
        .hasSingletonErrorThat(parentKey)
        .hasExceptionThat()
        .hasMessageThat()
        .isEqualTo(errorKey.toString());
  }

  @Test
  public void twoErrors() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey firstError = GraphTester.toSkyKey("error1");
    SkyKey secondError = GraphTester.toSkyKey("error2");
    CountDownLatch firstStart = new CountDownLatch(1);
    CountDownLatch secondStart = new CountDownLatch(1);
    tester
        .getOrCreate(firstError)
        .setBuilder(
            new ChainedFunction(
                firstStart,
                secondStart,
                /*notifyFinish=*/ null,
                /*waitForException=*/ false,
                /*value=*/ null,
                ImmutableList.of()));
    tester
        .getOrCreate(secondError)
        .setBuilder(
            new ChainedFunction(
                secondStart,
                firstStart,
                /*notifyFinish=*/ null,
                /*waitForException=*/ false,
                /*value=*/ null,
                ImmutableList.of()));
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, firstError, secondError);
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
    // With keepGoing=false, the eval call will terminate with exactly one error (the first one
    // thrown). But the first one thrown here is non-deterministic since we synchronize the
    // builders so that they run at roughly the same time.
    assertThat(ImmutableSet.of(firstError, secondError))
        .contains(Iterables.getOnlyElement(result.errorMap().keySet()));
  }

  @Test
  public void simpleCycle() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(aKey)).getError();
    assertThat(errorInfo.getException()).isNull();
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).isEmpty();
  }

  @Test
  public void cycleWithHead() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(topKey)).getError();
    assertThat(errorInfo.getException()).isNull();
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
  }

  @Test
  public void selfEdgeWithHead() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(topKey)).getError();
    assertThat(errorInfo.getException()).isNull();
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(aKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
  }

  @Test
  public void cycleWithKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey goodKey = GraphTester.toSkyKey("good");
    StringValue goodValue = new StringValue("good");
    tester.set(goodKey, goodValue);
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    EvaluationResult<StringValue> result = eval(true, topKey, goodKey);
    assertThat(result.get(goodKey)).isEqualTo(goodValue);
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
  }

  @Test
  public void twoCycles() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    tester.getOrCreate(cKey).addDependency(dKey);
    tester.getOrCreate(dKey).addDependency(cKey);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    Iterable<CycleInfo> cycles =
        CycleInfo.prepareCycles(
            topKey,
            ImmutableList.of(
                new CycleInfo(ImmutableList.of(aKey, bKey)),
                new CycleInfo(ImmutableList.of(cKey, dKey))));
    assertThat(cycles).contains(getOnlyElement(errorInfo.getCycleInfo()));
  }

  @Test
  public void twoCyclesKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    tester.getOrCreate(cKey).addDependency(dKey);
    tester.getOrCreate(dKey).addDependency(cKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo aCycle = new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(aKey, bKey));
    CycleInfo cCycle = new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(cKey, dKey));
    assertThat(errorInfo.getCycleInfo()).containsExactly(aCycle, cCycle);
  }

  @Test
  public void triangleBelowHeadCycle() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey).addDependency(cKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(topKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo topCycle = new CycleInfo(ImmutableList.of(topKey, aKey, cKey));
    assertThat(errorInfo.getCycleInfo()).containsExactly(topCycle);
  }

  @Test
  public void longCycle() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(topKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo topCycle = new CycleInfo(ImmutableList.of(topKey, aKey, bKey, cKey));
    assertThat(errorInfo.getCycleInfo()).containsExactly(topCycle);
  }

  @Test
  public void cycleWithTail() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(cKey);
    tester.set(cKey, new StringValue("cValue"));
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey).inOrder();
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void selfEdgeWithExtraChildrenUnderCycle() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey zKey = GraphTester.toSkyKey("z");
    SkyKey cKey = GraphTester.toSkyKey("c");
    tester.getOrCreate(aKey).addDependency(zKey);
    tester.getOrCreate(zKey).addDependency(cKey).addDependency(zKey);
    tester.getOrCreate(cKey).addDependency(aKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(aKey));
    assertThat(result.get(aKey)).isNull();
    ErrorInfo errorInfo = result.getError(aKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(zKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(aKey).inOrder();
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void cycleWithExtraChildrenUnderCycle() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey).addDependency(dKey);
    tester.getOrCreate(cKey).addDependency(aKey);
    tester.getOrCreate(dKey).addDependency(bKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(aKey));
    assertThat(result.get(aKey)).isNull();
    ErrorInfo errorInfo = result.getError(aKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(bKey, dKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(aKey).inOrder();
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void cycleAboveIndependentCycle() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(aKey).addDependency(bKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(aKey));
    assertThat(result.get(aKey)).isNull();
    assertThat(result.getError(aKey).getCycleInfo())
        .containsExactly(
            new CycleInfo(ImmutableList.of(aKey, bKey, cKey)),
            new CycleInfo(ImmutableList.of(aKey), ImmutableList.of(bKey, cKey)));
  }

  @Test
  public void valueAboveCycleAndExceptionReportsException() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey bKey = GraphTester.toSkyKey("b");
    tester.getOrCreate(aKey).addDependency(bKey).addDependency(errorKey);
    tester.getOrCreate(bKey).addDependency(bKey);
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(aKey));
    assertThat(result.get(aKey)).isNull();
    assertThat(result.getError(aKey).getException()).isNotNull();
    CycleInfo cycleInfo = Iterables.getOnlyElement(result.getError(aKey).getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(bKey).inOrder();
    assertThat(cycleInfo.getPathToCycle()).containsExactly(aKey).inOrder();
  }

  @Test
  public void errorValueStored() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey);
    // Update value. But builder won't rebuild it.
    tester.getOrCreate(errorKey).setHasError(false);
    tester.set(errorKey, new StringValue("no error?"));
    result = eval(false, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey);
  }

  /**
   * Regression test: "OOM in Skyframe cycle detection". We only store the first 20 cycles found
   * below any given root value.
   */
  @Test
  public void manyCycles() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey topKey = GraphTester.toSkyKey("top");
    for (int i = 0; i < 100; i++) {
      SkyKey dep = GraphTester.toSkyKey(Integer.toString(i));
      tester.getOrCreate(topKey).addDependency(dep);
      tester.getOrCreate(dep).addDependency(dep);
    }
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    assertManyCycles(result.getError(topKey), topKey, /*selfEdge=*/ false);
  }

  /**
   * Regression test: "OOM in Skyframe cycle detection". We filter out multiple paths to a cycle
   * that go through the same child value.
   */
  @Test
  public void manyPathsToCycle() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(cycleKey).addDependency(cycleKey);
    for (int i = 0; i < 100; i++) {
      SkyKey dep = GraphTester.toSkyKey(Integer.toString(i));
      tester.getOrCreate(midKey).addDependency(dep);
      tester.getOrCreate(dep).addDependency(cycleKey);
    }
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThat(result.get(topKey)).isNull();
    CycleInfo cycleInfo = Iterables.getOnlyElement(result.getError(topKey).getCycleInfo());
    assertThat(cycleInfo.getCycle()).hasSize(1);
    assertThat(cycleInfo.getPathToCycle()).hasSize(3);
    assertThat(cycleInfo.getPathToCycle().subList(0, 2)).containsExactly(topKey, midKey).inOrder();
  }

  /**
   * Checks that errorInfo has many self-edge cycles, and that one of them is a self-edge of topKey,
   * if {@code selfEdge} is true.
   */
  private static void assertManyCycles(ErrorInfo errorInfo, SkyKey topKey, boolean selfEdge) {
    assertThat(Iterables.size(errorInfo.getCycleInfo())).isGreaterThan(1);
    assertThat(Iterables.size(errorInfo.getCycleInfo())).isLessThan(50);
    boolean foundSelfEdge = false;
    for (CycleInfo cycle : errorInfo.getCycleInfo()) {
      assertThat(cycle.getCycle()).hasSize(1); // Self-edge.
      if (!Iterables.isEmpty(cycle.getPathToCycle())) {
        assertThat(cycle.getPathToCycle()).containsExactly(topKey).inOrder();
      } else {
        assertThat(cycle.getCycle()).containsExactly(topKey).inOrder();
        foundSelfEdge = true;
      }
    }
    assertWithMessage(errorInfo + ", " + topKey).that(foundSelfEdge).isEqualTo(selfEdge);
  }

  @Test
  public void manyUnprocessedValuesInCycle() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey lastSelfKey = GraphTester.toSkyKey("zlastSelf");
    SkyKey firstSelfKey = GraphTester.toSkyKey("afirstSelf");
    SkyKey midSelfKey = GraphTester.toSkyKey("midSelf9");
    // We add firstSelf first so that it is processed last in cycle detection (LIFO), meaning that
    // none of the dep values have to be cleared from firstSelf.
    tester.getOrCreate(firstSelfKey).addDependency(firstSelfKey);
    for (int i = 0; i < 100; i++) {
      SkyKey firstDep = GraphTester.toSkyKey("first" + i);
      SkyKey midDep = GraphTester.toSkyKey("midSelf" + i + "dep");
      SkyKey lastDep = GraphTester.toSkyKey("last" + i);
      tester.getOrCreate(firstSelfKey).addDependency(firstDep);
      tester.getOrCreate(midSelfKey).addDependency(midDep);
      tester.getOrCreate(lastSelfKey).addDependency(lastDep);
      if (i == 90) {
        // Most of the deps will be cleared from midSelf.
        tester.getOrCreate(midSelfKey).addDependency(midSelfKey);
      }
      tester.getOrCreate(firstDep).addDependency(firstDep);
      tester.getOrCreate(midDep).addDependency(midDep);
      tester.getOrCreate(lastDep).addDependency(lastDep);
    }
    // All the deps will be cleared from lastSelf.
    tester.getOrCreate(lastSelfKey).addDependency(lastSelfKey);
    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/ true, ImmutableList.of(lastSelfKey, firstSelfKey, midSelfKey));
    assertWithMessage(result.toString()).that(result.keyNames()).isEmpty();
    assertThat(result.errorMap().keySet()).containsExactly(lastSelfKey, firstSelfKey, midSelfKey);

    // Check lastSelfKey.
    ErrorInfo errorInfo = result.getError(lastSelfKey);
    assertWithMessage(errorInfo.toString())
        .that(Iterables.size(errorInfo.getCycleInfo()))
        .isEqualTo(1);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    assertThat(cycleInfo.getCycle()).containsExactly(lastSelfKey);
    assertThat(cycleInfo.getPathToCycle()).isEmpty();

    // Check firstSelfKey. It should not have discovered its own self-edge, because there were too
    // many other values before it in the queue.
    assertManyCycles(result.getError(firstSelfKey), firstSelfKey, /*selfEdge=*/ false);

    // Check midSelfKey. It should have discovered its own self-edge.
    assertManyCycles(result.getError(midSelfKey), midSelfKey, /*selfEdge=*/ true);
  }

  @Test
  public void errorValueStoredWithKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey);
    // Update value. But builder won't rebuild it.
    tester.getOrCreate(errorKey).setHasError(false);
    tester.set(errorKey, new StringValue("no error?"));
    result = eval(true, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey);
  }

  @Test
  public void continueWithErrorDep() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE)
        .addDependency("after");
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parentKey));
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter");
    result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey);
  }

  @Test
  public void transformErrorDep(@TestParameter boolean keepGoing) throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester
        .getOrCreate(parentErrorKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setHasError(true);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(parentErrorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(parentErrorKey);
  }

  @Test
  public void transformErrorDepOneLevelDownKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"));
    tester.set(parentErrorKey, new StringValue("parent value"));
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester
        .getOrCreate(topKey)
        .addDependency(parentErrorKey)
        .addDependency("after")
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThat(ImmutableList.<String>copyOf(result.keyNames())).containsExactly("top");
    assertThat(result.get(topKey).getValue()).isEqualTo("parent valueafter");
    assertThat(result.errorMap()).isEmpty();
  }

  @Test
  public void transformErrorDepOneLevelDownNoKeepGoing() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"));
    tester.set(parentErrorKey, new StringValue("parent value"));
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester
        .getOrCreate(topKey)
        .addDependency(parentErrorKey)
        .addDependency("after")
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(topKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
  }

  @Test
  public void errorDepDoesntStopOtherDep() throws Exception {
    graph = new InMemoryGraphImpl();
    final SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result1 = eval(/*keepGoing=*/ true, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result1).hasError();
    assertThatEvaluationResult(result1)
        .hasErrorEntryForKeyThat(errorKey)
        .hasExceptionThat()
        .isNotNull();
    final SkyKey otherKey = GraphTester.toSkyKey("other");
    tester.getOrCreate(otherKey).setConstantValue(new StringValue("other"));
    SkyKey topKey = GraphTester.toSkyKey("top");
    final Exception topException = new SomeErrorException("top exception");
    final AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                SkyframeLookupResult values =
                    env.getValuesAndExceptions(ImmutableList.of(errorKey, otherKey));
                if (numComputes.incrementAndGet() == 1) {
                  assertThat(env.valuesMissing()).isTrue();
                } else {
                  assertThat(numComputes.get()).isEqualTo(2);
                  assertThat(env.valuesMissing()).isFalse();
                }
                try {
                  values.getOrThrow(errorKey, SomeErrorException.class);
                  throw new AssertionError("Should have thrown");
                } catch (SomeErrorException e) {
                  throw new SkyFunctionException(topException, Transience.PERSISTENT) {};
                }
              }
            });
    EvaluationResult<StringValue> result2 = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThatEvaluationResult(result2).hasError();
    assertThatEvaluationResult(result2)
        .hasErrorEntryForKeyThat(topKey)
        .hasExceptionThat()
        .isSameInstanceAs(topException);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  /** Make sure that multiple unfinished children can be cleared from a cycle value. */
  @Test
  public void cycleWithMultipleUnfinishedChildren() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    SkyKey cycleKey = GraphTester.toSkyKey("zcycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey selfEdge1 = GraphTester.toSkyKey("selfEdge1");
    SkyKey selfEdge2 = GraphTester.toSkyKey("selfEdge2");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    // selfEdge* come before cycleKey, so cycleKey's path will be checked first (LIFO), and the
    // cycle with mid will be detected before the selfEdge* cycles are.
    tester
        .getOrCreate(midKey)
        .addDependency(selfEdge1)
        .addDependency(selfEdge2)
        .addDependency(cycleKey)
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(cycleKey).addDependency(midKey);
    tester.getOrCreate(selfEdge1).addDependency(selfEdge1);
    tester.getOrCreate(selfEdge2).addDependency(selfEdge2);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableSet.of(topKey));
    assertThat(result.errorMap().keySet()).containsExactly(topKey);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey);
    assertThat(cycleInfo.getCycle()).containsExactly(midKey, cycleKey);
  }

  /**
   * Regression test: "value in cycle depends on error". The mid value will have two parents -- top
   * and cycle. Error bubbles up from mid to cycle, and we should detect cycle.
   */
  @Test
  public void cycleAndErrorInBubbleUp(@TestParameter boolean keepGoing) throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(midKey)
        .addDependency(errorKey)
        .addDependency(cycleKey)
        .setComputedValue(CONCATENATE);

    // We need to ensure that cycle value has finished its work, and we have recorded dependencies
    CountDownLatch cycleFinish = new CountDownLatch(1);
    tester
        .getOrCreate(cycleKey)
        .setBuilder(
            new ChainedFunction(
                null, null, cycleFinish, false, new StringValue(""), ImmutableSet.of(midKey)));
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                null, cycleFinish, null, /*waitForException=*/ false, null, ImmutableSet.of()));

    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableSet.of(topKey));
    assertThatEvaluationResult(result)
        .hasSingletonErrorThat(topKey)
        .hasCycleInfoThat()
        .containsExactly(
            new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(midKey, cycleKey)));
  }

  /**
   * Regression test: "value in cycle depends on error". We add another value that won't finish
   * building before the threadpool shuts down, to check that the cycle detection can handle
   * unfinished values.
   */
  @Test
  public void cycleAndErrorAndOtherInBubbleUp() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    // We should add cycleKey first and errorKey afterwards. Otherwise there is a chance that
    // during error propagation cycleKey will not be processed, and we will not detect the cycle.
    tester
        .getOrCreate(midKey)
        .addDependency(errorKey)
        .addDependency(cycleKey)
        .setComputedValue(CONCATENATE);
    SkyKey otherTop = GraphTester.toSkyKey("otherTop");
    CountDownLatch topStartAndCycleFinish = new CountDownLatch(2);
    // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
    // then request its own dep. This guarantees that there is a value that is not finished when
    // cycle detection happens.
    tester
        .getOrCreate(otherTop)
        .setBuilder(
            new ChainedFunction(
                topStartAndCycleFinish,
                new CountDownLatch(0),
                null,
                /*waitForException=*/ true,
                new StringValue("never returned"),
                ImmutableSet.of(GraphTester.toSkyKey("dep that never builds"))));

    tester
        .getOrCreate(cycleKey)
        .setBuilder(
            new ChainedFunction(
                null,
                null,
                topStartAndCycleFinish,
                /*waitForException=*/ false,
                new StringValue(""),
                ImmutableSet.of(midKey)));
    // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
    // its dep before the threadpool shuts down.
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                null,
                topStartAndCycleFinish,
                null,
                /*waitForException=*/ false,
                null,
                ImmutableSet.of()));
    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/ false, ImmutableSet.of(topKey, otherTop));
    assertThat(result.errorMap().keySet()).containsExactly(topKey);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    assertThat(cycleInfos).isNotEmpty();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey);
    assertThat(cycleInfo.getCycle()).containsExactly(midKey, cycleKey);
  }

  /**
   * Regression test: "value in cycle depends on error". Here, we add an additional top-level key in
   * error, just to mix it up.
   */
  @Test
  public void cycleAndErrorAndError(@TestParameter boolean keepGoing) throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(midKey)
        .addDependency(errorKey)
        .addDependency(cycleKey)
        .setComputedValue(CONCATENATE);
    SkyKey otherTop = GraphTester.toSkyKey("otherTop");
    CountDownLatch topStartAndCycleFinish = new CountDownLatch(2);
    // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
    // then throw its own exception. This guarantees that its exception will not be the one
    // bubbling up, but that there is a top-level value with an exception by the time the bubbling
    // up starts.
    tester
        .getOrCreate(otherTop)
        .setBuilder(
            new ChainedFunction(
                topStartAndCycleFinish,
                new CountDownLatch(0),
                null,
                /*waitForException=*/ !keepGoing,
                null,
                ImmutableSet.of()));
    // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
    // its dep before the threadpool shuts down.
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                null,
                topStartAndCycleFinish,
                null,
                /*waitForException=*/ false,
                null,
                ImmutableSet.of()));
    tester
        .getOrCreate(cycleKey)
        .setBuilder(
            new ChainedFunction(
                null,
                null,
                topStartAndCycleFinish,
                /*waitForException=*/ false,
                new StringValue(""),
                ImmutableSet.of(midKey)));
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableSet.of(topKey, otherTop));
    if (keepGoing) {
      assertThatEvaluationResult(result).hasErrorMapThat().hasSize(2);
    }
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(topKey)
        .hasCycleInfoThat()
        .containsExactly(
            new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(midKey, cycleKey)));
  }

  @Test
  public void testFunctionCrashTrace() throws Exception {

    class ChildFunction implements SkyFunction {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) {
        throw new IllegalStateException("I WANT A PONY!!!");
      }
    }

    class ParentFunction implements SkyFunction {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
        SkyValue dep = env.getValue(ChildKey.create("billy the kid"));
        if (dep == null) {
          return null;
        }
        throw new IllegalStateException(); // Should never get here.
      }
    }

    ImmutableMap<SkyFunctionName, SkyFunction> skyFunctions =
        ImmutableMap.of(
            CHILD_TYPE, new ChildFunction(),
            PARENT_TYPE, new ParentFunction());
    ParallelEvaluator evaluator = makeEvaluator(new InMemoryGraphImpl(), skyFunctions, false);

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> evaluator.eval(ImmutableList.of(ParentKey.create("octodad"))));
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("I WANT A PONY!!!");
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Unrecoverable error while evaluating node 'child:billy the kid' "
                + "(requested by nodes 'parent:octodad')");
  }

  private static final class SomeOtherErrorException extends Exception {
    SomeOtherErrorException(String msg) {
      super(msg);
    }
  }

  /**
   * This and the following tests are in response to a bug: "Skyframe error propagation model is
   * problematic". They ensure that exceptions a child throws that a value does not specify it can
   * handle in getValueOrThrow do not cause a crash.
   */
  @Test
  public void unexpectedErrorDep(@TestParameter boolean keepGoing) throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    final SomeOtherErrorException exception = new SomeOtherErrorException("error exception");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new SkyFunctionException(exception, Transience.PERSISTENT) {};
            });
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester
        .getOrCreate(topKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(topKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
  }

  @Test
  public void unexpectedErrorDepOneLevelDown(@TestParameter boolean keepGoing) throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    final SomeErrorException exception = new SomeErrorException("error exception");
    final SomeErrorException topException = new SomeErrorException("top exception");
    final StringValue topValue = new StringValue("top");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(exception, Transience.PERSISTENT);
            });
    SkyKey topKey = GraphTester.toSkyKey("top");
    final SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              try {
                if (env.getValueOrThrow(parentKey, SomeErrorException.class) == null) {
                  return null;
                }
              } catch (SomeErrorException e) {
                assertWithMessage(e.toString()).that(e).isEqualTo(exception);
              }
              if (keepGoing) {
                return topValue;
              } else {
                throw new GenericFunctionException(topException, Transience.PERSISTENT);
              }
            });
    tester
        .getOrCreate(topKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(topKey));
    if (!keepGoing) {
      assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    } else {
      assertThatEvaluationResult(result).hasNoError();
      assertThatEvaluationResult(result).hasEntryThat(topKey).isSameInstanceAs(topValue);
    }
  }

  /**
   * Exercises various situations involving groups of deps that overlap -- request one group, then
   * request another group that has a dep in common with the first group.
   *
   * @param sameFirst whether the dep in common in the two groups should be the first dep.
   * @param twoCalls whether the two groups should be requested in two different builder calls.
   */
  @Test
  public void sameDepInTwoGroups(@TestParameter boolean sameFirst, @TestParameter boolean twoCalls)
      throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey topKey = GraphTester.toSkyKey("top");
    final List<SkyKey> leaves = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      SkyKey leaf = GraphTester.toSkyKey("leaf" + i);
      leaves.add(leaf);
      tester.set(leaf, new StringValue("leaf" + i));
    }
    final SkyKey leaf4 = GraphTester.toSkyKey("leaf4");
    tester.set(leaf4, new StringValue("leaf" + 4));
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              env.getOrderedValuesAndExceptions(leaves);
              if (twoCalls && env.valuesMissing()) {
                return null;
              }
              SkyKey first = sameFirst ? leaves.get(0) : leaf4;
              SkyKey second = sameFirst ? leaf4 : leaves.get(2);
              ImmutableList<SkyKey> secondRequest = ImmutableList.of(first, second);
              env.getOrderedValuesAndExceptions(secondRequest);
              if (env.valuesMissing()) {
                return null;
              }
              return new StringValue("top");
            });
    eval(/*keepGoing=*/ false, topKey);
    assertThat(eval(/*keepGoing=*/ false, topKey)).isEqualTo(new StringValue("top"));
  }

  @Test
  public void getValueOrThrowWithErrors(@TestParameter boolean keepGoing) throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final SkyKey errorDep = GraphTester.toSkyKey("errorChild");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester
        .getOrCreate(errorDep)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(childExn, Transience.PERSISTENT);
            });
    final List<SkyKey> deps = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      SkyKey dep = GraphTester.toSkyKey("child" + i);
      deps.add(dep);
      tester.set(dep, new StringValue("child" + i));
    }
    final SomeErrorException parentExn = new SomeErrorException("parent error");
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              try {
                SkyValue value = env.getValueOrThrow(errorDep, SomeErrorException.class);
                if (value == null) {
                  return null;
                }
              } catch (SomeErrorException e) {
                // Recover from the child error.
              }
              env.getValues(deps);
              if (env.valuesMissing()) {
                return null;
              }
              throw new GenericFunctionException(parentExn, Transience.PERSISTENT);
            });
    EvaluationResult<StringValue> evaluationResult = eval(keepGoing, ImmutableList.of(parentKey));
    assertThat(evaluationResult.hasError()).isTrue();
    assertThat(evaluationResult.getError().getException())
        .isEqualTo(keepGoing ? parentExn : childExn);
  }

  @Test
  public void getValuesAndExceptions() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey otherKey = GraphTester.toSkyKey("other");
    SkyKey anotherKey = GraphTester.toSkyKey("another");
    SkyKey errorExpectedKey = GraphTester.toSkyKey("errorExpected");
    SkyKey topKey = GraphTester.toSkyKey("top");
    Exception topException = new SomeErrorException("top exception");
    AtomicInteger numComputes = new AtomicInteger(0);

    tester.set(otherKey, new StringValue("other"));
    tester.set(anotherKey, new StringValue("another"));
    tester.getOrCreate(errorExpectedKey).setHasError(true);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                ImmutableList<SkyKey> depKeys =
                    ImmutableList.of(otherKey, anotherKey, errorExpectedKey);
                SkyframeLookupResult skyframeLookupResult = env.getValuesAndExceptions(depKeys);
                if (numComputes.incrementAndGet() == 1) {
                  assertThat(env.valuesMissing()).isTrue();
                  for (SkyKey depKey : depKeys.reverse()) {
                    try {
                      assertThat(skyframeLookupResult.getOrThrow(depKey, SomeErrorException.class))
                          .isNull();
                    } catch (SomeErrorException e) {
                      throw new AssertionError("should not have thrown", e);
                    }
                  }
                  return null;
                } else {
                  assertThat(numComputes.get()).isEqualTo(2);
                  SkyValue value1 = skyframeLookupResult.get(otherKey);
                  assertThat(value1).isNotNull();
                  assertThat(env.valuesMissing()).isFalse();
                  try {
                    SkyValue value2 =
                        skyframeLookupResult.getOrThrow(anotherKey, SomeErrorException.class);
                    assertThat(value2).isNotNull();
                    assertThat(env.valuesMissing()).isFalse();
                  } catch (SomeErrorException e) {
                    throw new AssertionError("Should not have thrown", e);
                  }
                  try {
                    skyframeLookupResult.getOrThrow(errorExpectedKey, SomeErrorException.class);
                    throw new AssertionError("Should throw");
                  } catch (SomeErrorException e) {
                    assertThat(env.valuesMissing()).isFalse();
                  }
                  throw new SkyFunctionException(topException, Transience.PERSISTENT) {};
                }
              }
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(topKey)
        .hasExceptionThat()
        .isSameInstanceAs(topException);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void getValuesAndExceptionsWithErrors() throws Exception {
    graph = new InMemoryGraphImpl();
    final SkyKey childKey = GraphTester.toSkyKey("error");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester
        .getOrCreate(childKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(childExn, Transience.PERSISTENT);
            });
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              try {
                SkyValue value =
                    env.getValuesAndExceptions(ImmutableList.of(childKey))
                        .getOrThrow(childKey, SomeOtherErrorException.class);
                assertThat(value).isNull();
              } catch (SomeOtherErrorException e) {
                throw new AssertionError("Should not have thrown", e);
              }
              numComputes.incrementAndGet();
              assertThat(env.valuesMissing()).isTrue();
              return null;
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(parentKey)
        .hasExceptionThat()
        .isSameInstanceAs(childExn);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void getOrderedValuesAndExceptions() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey otherKey = GraphTester.toSkyKey("other");
    tester.set(otherKey, new StringValue("other"));
    SkyKey anotherKey = GraphTester.toSkyKey("another");
    tester.set(anotherKey, new StringValue("another"));
    SkyKey errorExpectedKey = GraphTester.toSkyKey("errorExpected");
    tester.getOrCreate(errorExpectedKey).setHasError(true);
    SkyKey topKey = GraphTester.toSkyKey("top");
    Exception topException = new SomeErrorException("top exception");
    AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                SkyframeIterableResult skyframeIterableResult =
                    env.getOrderedValuesAndExceptions(
                        ImmutableList.of(otherKey, anotherKey, errorExpectedKey));
                if (numComputes.incrementAndGet() == 1) {
                  assertThat(env.valuesMissing()).isTrue();
                  int numElements = 0;
                  while (skyframeIterableResult.hasNext()) {
                    numElements++;
                    try {
                      assertThat(skyframeIterableResult.nextOrThrow(SomeErrorException.class))
                          .isNull();
                    } catch (SomeErrorException e) {
                      throw new AssertionError("should not have thrown", e);
                    }
                  }
                  assertThat(numElements).isEqualTo(3);
                  return null;
                } else {
                  assertThat(numComputes.get()).isEqualTo(2);
                  SkyValue value1 = skyframeIterableResult.next();
                  assertThat(value1).isNotNull();
                  assertThat(env.valuesMissing()).isFalse();
                  try {
                    SkyValue value2 = skyframeIterableResult.nextOrThrow(SomeErrorException.class);
                    assertThat(value2).isNotNull();
                    assertThat(env.valuesMissing()).isFalse();
                  } catch (SomeErrorException e) {
                    throw new AssertionError("Should not have thrown", e);
                  }
                  try {
                    skyframeIterableResult.nextOrThrow(SomeErrorException.class);
                    throw new AssertionError("Should throw");
                  } catch (SomeErrorException e) {
                    assertThat(env.valuesMissing()).isFalse();
                  }
                  throw new SkyFunctionException(topException, Transience.PERSISTENT) {};
                }
              }
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(topKey));
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(topKey)
        .hasExceptionThat()
        .isSameInstanceAs(topException);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void getOrderedValuesAndExceptionsWithErrors() throws Exception {
    graph = new InMemoryGraphImpl();
    final SkyKey childKey = GraphTester.toSkyKey("error");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester
        .getOrCreate(childKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(childExn, Transience.PERSISTENT);
            });
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              try {
                SkyValue value =
                    env.getOrderedValuesAndExceptions(ImmutableList.of(childKey))
                        .nextOrThrow(SomeOtherErrorException.class);
                assertThat(value).isNull();
              } catch (SomeOtherErrorException e) {
                throw new AssertionError("Should not have thrown", e);
              }
              numComputes.incrementAndGet();
              assertThat(env.valuesMissing()).isTrue();
              return null;
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(parentKey)
        .hasExceptionThat()
        .isSameInstanceAs(childExn);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void declareDependenciesAndCheckIfValuesMissing() throws Exception {
    graph = new InMemoryGraphImpl();
    final SkyKey childKey = GraphTester.toSkyKey("error");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester
        .getOrCreate(childKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(childExn, Transience.PERSISTENT);
            });
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final AtomicInteger numComputes = new AtomicInteger(0);
    BugReporter mockReporter = mock(BugReporter.class);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              boolean valuesMissing =
                  GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                      env,
                      ImmutableList.of(childKey),
                      SomeOtherErrorException.class,
                      /*exceptionClass2=*/ null,
                      mockReporter);
              numComputes.incrementAndGet();
              assertThat(valuesMissing).isTrue();
              return null;
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parentKey));
    ArgumentCaptor<IllegalStateException> exceptionCaptor =
        ArgumentCaptor.forClass(IllegalStateException.class);
    verify(mockReporter).sendBugReport(exceptionCaptor.capture());
    assertThat(exceptionCaptor.getValue()).hasMessageThat().contains("Some value from");
    verifyNoMoreInteractions(mockReporter);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(parentKey)
        .hasExceptionThat()
        .isSameInstanceAs(childExn);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void declareDependenciesAndCheckIfNotValuesMissing() throws Exception {
    graph = new InMemoryGraphImpl();
    final SkyKey otherKey = GraphTester.toSkyKey("other");
    final SkyKey childKey = GraphTester.toSkyKey("error");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester.set(otherKey, new StringValue("other"));
    tester
        .getOrCreate(childKey)
        .setBuilder(
            (skyKey, env) -> {
              throw new GenericFunctionException(childExn, Transience.PERSISTENT);
            });
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              if (numComputes.incrementAndGet() == 1) {
                boolean valuesMissing =
                    GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                        env, ImmutableList.of(otherKey, childKey), SomeErrorException.class);
                assertThat(valuesMissing).isTrue();
              } else {
                boolean valuesMissing =
                    GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                        env, ImmutableList.of(otherKey, childKey), SomeErrorException.class);
                assertThat(valuesMissing).isFalse();
              }
              return null;
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(parentKey)
        .hasExceptionThat()
        .isSameInstanceAs(childExn);
    assertThat(numComputes.get()).isEqualTo(2);
  }

  @Test
  public void duplicateCycles() throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey grandparentKey = GraphTester.toSkyKey("grandparent");
    SkyKey parentKey1 = GraphTester.toSkyKey("parent1");
    SkyKey parentKey2 = GraphTester.toSkyKey("parent2");
    SkyKey loopKey1 = GraphTester.toSkyKey("loop1");
    SkyKey loopKey2 = GraphTester.toSkyKey("loop2");
    tester.getOrCreate(loopKey1).addDependency(loopKey2);
    tester.getOrCreate(loopKey2).addDependency(loopKey1);
    tester.getOrCreate(parentKey1).addDependency(loopKey1);
    tester.getOrCreate(parentKey2).addDependency(loopKey2);
    tester.getOrCreate(grandparentKey).addDependency(parentKey1);
    tester.getOrCreate(grandparentKey).addDependency(parentKey2);

    ErrorInfo errorInfo = evalValueInError(grandparentKey);
    List<ImmutableList<SkyKey>> cycles = Lists.newArrayList();
    for (CycleInfo cycleInfo : errorInfo.getCycleInfo()) {
      cycles.add(cycleInfo.getCycle());
    }
    // Skyframe doesn't automatically dedupe cycles that are the same except for entry point.
    assertThat(cycles).hasSize(2);
    int numUniqueCycles = 0;
    CycleDeduper<SkyKey> cycleDeduper = new CycleDeduper<>();
    for (ImmutableList<SkyKey> cycle : cycles) {
      if (!cycleDeduper.alreadySeen(cycle)) {
        numUniqueCycles++;
      }
    }
    assertThat(numUniqueCycles).isEqualTo(1);
  }

  @Test
  public void signalValueEnqueuedAndEvaluated() throws Exception {
    Set<SkyKey> enqueuedValues = Sets.newConcurrentHashSet();
    Set<SkyKey> evaluatedValues = Sets.newConcurrentHashSet();
    EvaluationProgressReceiver progressReceiver =
        new EvaluationProgressReceiver() {
          @Override
          public void enqueueing(SkyKey skyKey) {
            enqueuedValues.add(skyKey);
          }

          @Override
          public void evaluated(
              SkyKey skyKey,
              @Nullable SkyValue newValue,
              @Nullable ErrorInfo newError,
              Supplier<EvaluationSuccessState> evaluationSuccessState,
              EvaluationState state) {
            evaluatedValues.add(skyKey);
          }
        };

    ExtendedEventHandler reporter =
        new Reporter(
            new EventBus(),
            e -> {
              throw new IllegalStateException();
            });

    MemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.of(GraphTester.NODE_TYPE, tester.getFunction()),
            new SequencedRecordingDifferencer(),
            progressReceiver);

    tester
        .getOrCreate("top1")
        .setComputedValue(CONCATENATE)
        .addDependency("d1")
        .addDependency("d2");
    tester.getOrCreate("top2").setComputedValue(CONCATENATE).addDependency("d3");
    tester.getOrCreate("top3");
    assertThat(enqueuedValues).isEmpty();
    assertThat(evaluatedValues).isEmpty();

    tester.set("d1", new StringValue("1"));
    tester.set("d2", new StringValue("2"));
    tester.set("d3", new StringValue("3"));

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(200)
            .setEventHandler(reporter)
            .build();
    evaluator.evaluate(ImmutableList.of(GraphTester.toSkyKey("top1")), evaluationContext);
    assertThat(enqueuedValues).containsExactlyElementsIn(GraphTester.toSkyKeys("top1", "d1", "d2"));
    assertThat(evaluatedValues)
        .containsExactlyElementsIn(GraphTester.toSkyKeys("top1", "d1", "d2"));
    enqueuedValues.clear();
    evaluatedValues.clear();

    evaluator.evaluate(ImmutableList.of(GraphTester.toSkyKey("top2")), evaluationContext);
    assertThat(enqueuedValues).containsExactlyElementsIn(GraphTester.toSkyKeys("top2", "d3"));
    assertThat(evaluatedValues).containsExactlyElementsIn(GraphTester.toSkyKeys("top2", "d3"));
    enqueuedValues.clear();
    evaluatedValues.clear();

    evaluator.evaluate(ImmutableList.of(GraphTester.toSkyKey("top1")), evaluationContext);
    assertThat(enqueuedValues).isEmpty();
    assertThat(evaluatedValues).containsExactlyElementsIn(GraphTester.toSkyKeys("top1"));
  }

  @Test
  public void runDepOnErrorHaltsNoKeepGoingBuildEagerly(
      @TestParameter boolean childErrorCached, @TestParameter boolean handleChildError)
      throws Exception {
    graph = new InMemoryGraphImpl();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final SkyKey childKey = GraphTester.toSkyKey("child");
    tester.getOrCreate(childKey).setHasError(/*hasError=*/ true);
    // The parent should be built exactly twice: once during normal evaluation and once
    // during error bubbling.
    final AtomicInteger numParentInvocations = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              int invocations = numParentInvocations.incrementAndGet();
              if (handleChildError) {
                try {
                  SkyValue value = env.getValueOrThrow(childKey, SomeErrorException.class);
                  // On the first invocation, either the child error should already be cached and
                  // not propagated, or it should be computed freshly and not propagated. On the
                  // second build (error bubbling), the child error should be propagated.
                  assertWithMessage("bogus non-null value " + value).that(value == null).isTrue();
                  assertWithMessage("parent incorrectly re-computed during normal evaluation")
                      .that(invocations)
                      .isEqualTo(1);
                  assertWithMessage("child error not propagated during error bubbling")
                      .that(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                      .isFalse();
                  return value;
                } catch (SomeErrorException e) {
                  assertWithMessage("child error propagated during normal evaluation")
                      .that(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                      .isTrue();
                  assertThat(invocations).isEqualTo(2);
                  return null;
                }
              } else {
                if (invocations == 1) {
                  assertWithMessage("parent's first computation should be during normal evaluation")
                      .that(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                      .isFalse();
                  return env.getValue(childKey);
                } else {
                  assertThat(invocations).isEqualTo(2);
                  assertWithMessage("parent incorrectly re-computed during normal evaluation")
                      .that(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                      .isTrue();
                  return env.getValue(childKey);
                }
              }
            });
    if (childErrorCached) {
      // Ensure that the child is already in the graph.
      evalValueInError(childKey);
    }
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThat(numParentInvocations.get()).isEqualTo(2);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(parentKey);
  }

  @Test
  public void raceConditionWithNoKeepGoingErrors_FutureError() throws Exception {
    final CountDownLatch errorCommitted = new CountDownLatch(1);
    final CountDownLatch otherStarted = new CountDownLatch(1);
    final CountDownLatch otherParentSignaled = new CountDownLatch(1);
    final SkyKey errorParentKey = GraphTester.toSkyKey("errorParentKey");
    final SkyKey errorKey = GraphTester.toSkyKey("errorKey");
    final SkyKey otherParentKey = GraphTester.toSkyKey("otherParentKey");
    final SkyKey otherKey = GraphTester.toSkyKey("otherKey");
    final AtomicInteger numOtherParentInvocations = new AtomicInteger(0);
    final AtomicInteger numErrorParentInvocations = new AtomicInteger(0);
    tester
        .getOrCreate(otherParentKey)
        .setBuilder(
            (skyKey, env) -> {
              int invocations = numOtherParentInvocations.incrementAndGet();
              assertWithMessage("otherParentKey should not be restarted")
                  .that(invocations)
                  .isEqualTo(1);
              return env.getValue(otherKey);
            });
    tester
        .getOrCreate(otherKey)
        .setBuilder(
            (skyKey, env) -> {
              otherStarted.countDown();
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  errorCommitted, "error didn't get committed to the graph in time");
              return new StringValue("other");
            });
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            (skyKey, env) -> {
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  otherStarted, "other didn't start in time");
              throw new GenericFunctionException(
                  new SomeErrorException("error"), Transience.PERSISTENT);
            });
    tester
        .getOrCreate(errorParentKey)
        .setBuilder(
            (skyKey, env) -> {
              int invocations = numErrorParentInvocations.incrementAndGet();
              try {
                SkyValue value = env.getValueOrThrow(errorKey, SomeErrorException.class);
                assertWithMessage("bogus non-null value " + value).that(value == null).isTrue();
                if (invocations == 1) {
                  return null;
                } else {
                  assertThat(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                      .isFalse();
                  fail("RACE CONDITION: errorParentKey was restarted!");
                  return null;
                }
              } catch (SomeErrorException e) {
                assertWithMessage("child error propagated during normal evaluation")
                    .that(env.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors())
                    .isTrue();
                assertThat(invocations).isEqualTo(2);
                return null;
              }
            });
    graph =
        new NotifyingHelper.NotifyingProcessableGraph(
            new InMemoryGraphImpl(),
            (key, type, order, context) -> {
              if (key.equals(errorKey) && type == EventType.SET_VALUE && order == Order.AFTER) {
                errorCommitted.countDown();
                TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                    otherParentSignaled, "otherParent didn't get signaled in time");
                // We try to give some time for ParallelEvaluator to incorrectly re-evaluate
                // 'otherParentKey'. This test case is testing for a real race condition and the
                // 10ms time was chosen experimentally to give a true positive rate of 99.8%
                // (without a sleep it has a 1% true positive rate). There's no good way to do
                // this without sleeping. We *could* introspect ParallelEvaulator's
                // AbstractQueueVisitor to see if the re-evaluation has been enqueued, but that's
                // relying on pretty low-level implementation details.
                Uninterruptibles.sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
              }
              if (key.equals(otherParentKey) && type == EventType.SIGNAL && order == Order.AFTER) {
                otherParentSignaled.countDown();
              }
            });
    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/ false, ImmutableList.of(otherParentKey, errorParentKey));
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(errorParentKey);
  }

  @Test
  public void cachedErrorsFromKeepGoingUsedOnNoKeepGoing() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey parent1Key = GraphTester.toSkyKey("parent1");
    SkyKey parent2Key = GraphTester.toSkyKey("parent2");
    tester
        .getOrCreate(parent1Key)
        .addDependency(errorKey)
        .setConstantValue(new StringValue("parent1"));
    tester
        .getOrCreate(parent2Key)
        .addDependency(errorKey)
        .setConstantValue(new StringValue("parent2"));
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(parent1Key));
    assertThatEvaluationResult(result).hasSingletonErrorThat(parent1Key);
    result = eval(/*keepGoing=*/ false, ImmutableList.of(parent2Key));
    assertThatEvaluationResult(result).hasSingletonErrorThat(parent2Key);
  }

  @Test
  public void cachedTopLevelErrorsShouldHaltNoKeepGoingBuildEarly() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ true, ImmutableList.of(errorKey));
    assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey);
    SkyKey rogueKey = GraphTester.toSkyKey("rogue");
    tester
        .getOrCreate(rogueKey)
        .setBuilder(
            (skyKey, env) -> {
              // This SkyFunction could do an arbitrarily bad computation, e.g. loop-forever. So we
              // want to make sure that it is never run when we want to fail-fast anyway.
              fail("eval call should have already terminated");
              return null;
            });
    result = eval(/*keepGoing=*/ false, ImmutableList.of(errorKey, rogueKey));
    assertThatEvaluationResult(result).hasErrorMapThat().hasSize(1);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(errorKey);
    assertThat(result.errorMap()).doesNotContainKey(rogueKey);
  }

  // Explicit test that we tolerate a SkyFunction that declares different [sequences of] deps each
  // restart. Such behavior from a SkyFunction isn't desired, but Bazel-on-Skyframe does indeed do
  // this.
  @Test
  public void declaresDifferentDepsAfterRestart() throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey grandChild1Key = GraphTester.toSkyKey("grandChild1");
    tester.getOrCreate(grandChild1Key).setConstantValue(new StringValue("grandChild1"));
    SkyKey child1Key = GraphTester.toSkyKey("child1");
    tester
        .getOrCreate(child1Key)
        .addDependency(grandChild1Key)
        .setConstantValue(new StringValue("child1"));
    SkyKey grandChild2Key = GraphTester.toSkyKey("grandChild2");
    tester.getOrCreate(grandChild2Key).setConstantValue(new StringValue("grandChild2"));
    SkyKey child2Key = GraphTester.toSkyKey("child2");
    tester.getOrCreate(child2Key).setConstantValue(new StringValue("child2"));
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    AtomicInteger numComputes = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              switch (numComputes.incrementAndGet()) {
                case 1:
                  env.getValue(child1Key);
                  Preconditions.checkState(env.valuesMissing());
                  return null;
                case 2:
                  env.getValue(child2Key);
                  Preconditions.checkState(env.valuesMissing());
                  return null;
                case 3:
                  return new StringValue("the third time's the charm!");
                default:
                  throw new IllegalStateException();
              }
            });
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/ false, ImmutableList.of(parentKey));
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result)
        .hasEntryThat(parentKey)
        .isEqualTo(new StringValue("the third time's the charm!"));
  }

  @Test
  public void runUnhandledTransitiveErrors(
      @TestParameter boolean keepGoing, @TestParameter boolean explicitlyPropagateError)
      throws Exception {
    graph = new DeterministicHelper.DeterministicProcessableGraph(new InMemoryGraphImpl());
    tester = new GraphTester();
    SkyKey grandparentKey = GraphTester.toSkyKey("grandparent");
    final SkyKey parentKey = GraphTester.toSkyKey("parent");
    final SkyKey childKey = GraphTester.toSkyKey("child");
    final AtomicBoolean errorPropagated = new AtomicBoolean(false);
    tester
        .getOrCreate(grandparentKey)
        .setBuilder(
            (skyKey, env) -> {
              try {
                return env.getValueOrThrow(parentKey, SomeErrorException.class);
              } catch (SomeErrorException e) {
                errorPropagated.set(true);
                throw new GenericFunctionException(e, Transience.PERSISTENT);
              }
            });
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            (skyKey, env) -> {
              if (explicitlyPropagateError) {
                try {
                  return env.getValueOrThrow(childKey, SomeErrorException.class);
                } catch (SomeErrorException e) {
                  throw new GenericFunctionException(e);
                }
              } else {
                return env.getValue(childKey);
              }
            });
    tester.getOrCreate(childKey).setHasError(/*hasError=*/ true);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(grandparentKey));
    assertThat(errorPropagated.get()).isTrue();
    assertThatEvaluationResult(result).hasSingletonErrorThat(grandparentKey);
  }

  private static class ChildKey extends AbstractSkyKey<String> {
    private static final Interner<ChildKey> interner = BlazeInterners.newWeakInterner();

    private ChildKey(String arg) {
      super(arg);
    }

    static ChildKey create(String arg) {
      return interner.intern(new ChildKey(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return CHILD_TYPE;
    }
  }

  private static class ParentKey extends AbstractSkyKey<String> {
    private static final Interner<ParentKey> interner = BlazeInterners.newWeakInterner();

    private ParentKey(String arg) {
      super(arg);
    }

    private static ParentKey create(String arg) {
      return interner.intern(new ParentKey(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return PARENT_TYPE;
    }
  }

  private static class SkyKeyForSkyKeyComputeStateTests extends AbstractSkyKey<String> {
    private static final SkyFunctionName FUNCTION_NAME =
        SkyFunctionName.createHermetic("SKY_KEY_COMPUTE_STATE_TESTS");

    private SkyKeyForSkyKeyComputeStateTests(String arg) {
      super(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return FUNCTION_NAME;
    }
  }

  // Test for the basic functionality of SkyKeyComputeState.
  @Test
  public void skyKeyComputeState() throws InterruptedException {
    // When we have 3 nodes: key1, key2, key3.
    // (with dependency graph key1 -> key2; key2 -> key3, to be declared later in this test)
    // (and we'll be evaluating key1 later in this test)
    SkyKey key1 = new SkyKeyForSkyKeyComputeStateTests("key1");
    SkyKey key2 = new SkyKeyForSkyKeyComputeStateTests("key2");
    SkyKey key3 = new SkyKeyForSkyKeyComputeStateTests("key3");

    // And an SkyKeyComputeState implementation that tracks global instance counts and per-instance
    // usage counts,
    AtomicInteger globalStateInstanceCounter = new AtomicInteger();
    class State implements SkyKeyComputeState {
      final int instanceCount = globalStateInstanceCounter.incrementAndGet();
      int usageCount = 0;
    }

    // And a SkyFunction for these nodes,
    AtomicLongMap<SkyKey> numCalls = AtomicLongMap.create();
    AtomicReference<WeakReference<State>> stateForKey2Ref = new AtomicReference<>();
    AtomicReference<WeakReference<State>> stateForKey3Ref = new AtomicReference<>();
    SkyFunction skyFunctionForTest =
        // Whose #compute is such that
        (skyKey, env) -> {
          State state = env.getState(State::new);
          state.usageCount++;
          int numCallsForKey = (int) numCalls.incrementAndGet(skyKey);
          // The number of calls to #compute is expected to be equal to the number of usages of
          // the state for that key,
          assertThat(state.usageCount).isEqualTo(numCallsForKey);
          if (skyKey.equals(key1)) {
            // And the semantics for key1 are:

            // The state for key1 is expected to be the first one created (since key1 is expected
            // to be the first node we attempt to compute).
            assertThat(state.instanceCount).isEqualTo(1);
            // And key1 declares a dep on key2,
            if (env.getValue(key2) == null) {
              // (And that dep is expected to be missing on the initial #compute call for key1)
              assertThat(numCallsForKey).isEqualTo(1);
              return null;
            }
            // And if that dep is not missing, then we expect:
            //   - We're on the second #compute call for key1
            assertThat(numCallsForKey).isEqualTo(2);
            //   - The state for key2 should have been eligible for GC. This is because the node
            //     for key2 must have been fully computed, meaning its compute state is no longer
            //     needed, and so ParallelEvaluator ought to have made it eligible for GC.
            GcFinalization.awaitClear(stateForKey2Ref.get());
            return new StringValue("value1");
          } else if (skyKey.equals(key2)) {
            // And the semantics for key2 are:

            // The state for key2 is expected to be the second one created.
            assertThat(state.instanceCount).isEqualTo(2);
            stateForKey2Ref.set(new WeakReference<>(state));
            // And key2 declares a dep on key3,
            if (env.getValue(key3) == null) {
              // (And that dep is expected to be missing on the initial #compute call for key2)
              assertThat(numCallsForKey).isEqualTo(1);
              return null;
            }
            // And if that dep is not missing, then we expect the same sort of things we expected
            // for key1 in this situation.
            assertThat(numCallsForKey).isEqualTo(2);
            GcFinalization.awaitClear(stateForKey3Ref.get());
            return new StringValue("value2");
          } else if (skyKey.equals(key3)) {
            // And the semantics for key3 are:

            // The state for key3 is expected to be the third one created.
            assertThat(state.instanceCount).isEqualTo(3);
            stateForKey3Ref.set(new WeakReference<>(state));
            // And key3 declares no deps.
            return new StringValue("value3");
          }
          throw new IllegalStateException();
        };

    tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.FUNCTION_NAME, skyFunctionForTest);
    graph = new InMemoryGraphImpl();
    // Then, when we evaluate key1,
    SkyValue resultValue = eval(/*keepGoing=*/ true, key1);
    // It successfully produces the value we expect, confirming all our other expectations about
    // the compute states were correct.
    assertThat(resultValue).isEqualTo(new StringValue("value1"));
  }

  // Test for SkyKeyComputeState in the situation of an error for one node causing normal evaluation
  // to fail-fast, but when there are SkyKeyComputeState instances for other inflight nodes.
  @Test
  public void skyKeyComputeState_noKeepGoingWithAnError() throws InterruptedException {
    // When we have 3 nodes: key1, key2, key3.
    // (with dependency graph key1 -> key2; key3, to be declared later in this test)
    // (and we'll be evaluating key1 & key3 in parallel later in this test)
    SkyKey key1 = new SkyKeyForSkyKeyComputeStateTests("key1");
    SkyKey key2 = new SkyKeyForSkyKeyComputeStateTests("key2");
    SkyKey key3 = new SkyKeyForSkyKeyComputeStateTests("key3");

    class State implements SkyKeyComputeState {}

    class SkyFunctionExceptionForTest extends SkyFunctionException {
      public SkyFunctionExceptionForTest(String message) {
        super(new SomeErrorException(message), Transience.PERSISTENT);
      }
    }

    // And a SkyFunction for these nodes,
    AtomicReference<WeakReference<State>> stateForKey1Ref = new AtomicReference<>();
    AtomicReference<WeakReference<State>> stateForKey3Ref = new AtomicReference<>();
    CountDownLatch key3SleepingLatch = new CountDownLatch(1);
    AtomicBoolean onNormalEvaluation = new AtomicBoolean(true);
    SkyFunction skyFunctionForTest =
        // Whose #compute is such that
        (skyKey, env) -> {
          if (onNormalEvaluation.get()) {
            // When we're on the normal evaluation:

            State state = env.getState(State::new);
            if (skyKey.equals(key1)) {
              // For key1:

              stateForKey1Ref.set(new WeakReference<>(state));
              // We declare a dep on key.
              return env.getValue(key2);
            } else if (skyKey.equals(key2)) {
              // For key2:

              // We wait for the thread computing key3 to be sleeping
              key3SleepingLatch.await();
              // And then we throw an error, which will fail the normal evaluation and trigger
              // error bubbling.
              onNormalEvaluation.set(false);
              throw new SkyFunctionExceptionForTest("normal evaluation");
            } else if (skyKey.equals(key3)) {
              // For key3:

              stateForKey3Ref.set(new WeakReference<>(state));
              key3SleepingLatch.countDown();
              // We sleep forever. (To be interrupted by ParallelEvaluator when the normal
              // evaluation fails).
              Thread.sleep(Long.MAX_VALUE);
            }
            throw new IllegalStateException();
          } else {
            // When we're in error bubbling:

            // The states for the nodes from normal evaluation should have been eligible for GC.
            // This is because ParallelEvaluator ought to have them eligible for GC before
            // starting error bubbling.
            GcFinalization.awaitClear(stateForKey1Ref.get());
            GcFinalization.awaitClear(stateForKey3Ref.get());

            // We bubble up a unique error message.
            throw new SkyFunctionExceptionForTest("error bubbling for " + skyKey.argument());
          }
        };

    tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.FUNCTION_NAME, skyFunctionForTest);
    graph = new InMemoryGraphImpl();
    // Then, when we do a nokeep_going evaluation of key1 and key3 in parallel,
    assertThatEvaluationResult(eval(/*keepGoing=*/ false, key1, key3))
        // The evaluation fails (as expected),
        .hasErrorEntryForKeyThat(key1)
        .hasExceptionThat()
        .hasMessageThat()
        // And the error message for key1 is from error bubbling,
        .isEqualTo("error bubbling for key1");
    // Confirming that all our other expectations about the compute states were correct.
  }

  // Demonstrates we're able to drop SkyKeyCompute state intra-evaluation.
  @Test
  public void skyKeyComputeState_unnecessaryTemporaryStateDropperReceiver()
      throws InterruptedException {
    // When we have 2 nodes: key1, key2
    // (with dependency graph key1 -> key2, to be declared later in this test)
    // (and we'll be evaluating key1 later in this test)
    SkyKey key1 = new SkyKeyForSkyKeyComputeStateTests("key1");
    SkyKey key2 = new SkyKeyForSkyKeyComputeStateTests("key2");

    // And an SkyKeyComputeState implementation that tracks global instance counts,
    AtomicInteger globalStateInstanceCounter = new AtomicInteger();
    class State implements SkyKeyComputeState {
      final int instanceCount = globalStateInstanceCounter.incrementAndGet();
    }

    // And a UnnecessaryTemporaryStateDropperReceiver that,
    AtomicReference<UnnecessaryTemporaryStateDropper> dropperRef = new AtomicReference<>();
    UnnecessaryTemporaryStateDropperReceiver dropperReceiver =
        new UnnecessaryTemporaryStateDropperReceiver() {
          @Override
          public void onEvaluationStarted(UnnecessaryTemporaryStateDropper dropper) {
            // Captures the UnnecessaryTemporaryStateDropper (for our use intra-evaluation)
            dropperRef.set(dropper);
          }

          @Override
          public void onEvaluationFinished() {
            // And then throws it away when the evaluation is done.
            dropperRef.set(null);
          }
        };

    AtomicReference<WeakReference<State>> stateForKey1Ref = new AtomicReference<>();

    // And a SkyFunction for these nodes,
    SkyFunction skyFunctionForTest =
        // Whose #compute is such that
        (skyKey, env) -> {
          State state = env.getState(State::new);
          if (skyKey.equals(key1)) {
            // The semantics for key1 are:

            // We declare a dep on key2.
            if (env.getValue(key2) == null) {
              // If key2 is missing, that means we're on the initial #compute call for key1,
              // And so we expect the compute state to be the first instance ever.
              assertThat(state.instanceCount).isEqualTo(1);
              stateForKey1Ref.set(new WeakReference<>(state));

              return null;
            } else {
              // But if key2 is not missing, that means we're on the subsequent #compute call for
              // key1. That means we expect the compute state to be the third instance ever,
              // because...
              assertThat(state.instanceCount).isEqualTo(3);

              return new StringValue("value1");
            }
          } else if (skyKey.equals(key2)) {
            // ... The semantics for key2 are:

            // Drop all compute states.
            dropperRef.get().drop();
            // Confirm the old compute state for key1 was GC'd.
            GcFinalization.awaitClear(stateForKey1Ref.get());
            // Also confirm key2's compute state is the second instance ever.
            assertThat(state.instanceCount).isEqualTo(2);

            return new StringValue("value2");
          }
          throw new IllegalStateException();
        };

    tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.FUNCTION_NAME, skyFunctionForTest);
    graph = new InMemoryGraphImpl();

    ParallelEvaluator parallelEvaluator =
        new ParallelEvaluator(
            graph,
            graphVersion,
            tester.getSkyFunctionMap(),
            storedEventHandler,
            new MemoizingEvaluator.EmittedEventState(),
            InMemoryMemoizingEvaluator.DEFAULT_STORED_EVENT_FILTER,
            ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
            // Doesn't matter for this test case.
            /*keepGoing=*/ false,
            revalidationReceiver,
            GraphInconsistencyReceiver.THROWING,
            // We ought not need more than 1 thread for this test case.
            () -> AbstractQueueVisitor.createExecutorService(1, "test-pool"),
            new SimpleCycleDetector(),
            /*cpuHeavySkyKeysThreadPoolSize=*/ 0,
            /*executionJobsThreadPoolSize=*/ 0,
            dropperReceiver);
    // Then, when we evaluate key1,
    SkyValue resultValue = parallelEvaluator.eval(ImmutableList.of(key1)).get(key1);
    // It successfully produces the value we expect, confirming all our other expectations about
    // the compute states were correct.
    assertThat(resultValue).isEqualTo(new StringValue("value1"));
    // And we threw away the dropper, confirming the #onEvaluationFinished method was called.
    assertThat(dropperRef.get()).isNull();
  }
}
