# Description:
#   A grab-bag of testing utilities.

load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_testonly = 1,
    default_visibility = [
        "//src:__subpackages__",
        "//tools:__subpackages__",
    ],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "testutil",
    testonly = 1,
    srcs = [
        "BazelTestSuiteBuilder.java",
        "BlazeTestUtils.java",
        "BuildRuleBuilder.java",
        "BuildRuleWithDefaultsBuilder.java",
        "DebuggingEventHandler.java",
        "FakeAttributeMapper.java",
        "FoundationTestCase.java",
        "ManualClock.java",
        "ManualSleeper.java",
        "Scratch.java",
        "TestFileOutErr.java",
        "TestRuleClassProvider.java",
        "TestSpec.java",
        "TimestampGranularityUtils.java",
        "UnknownRuleConfiguredTarget.java",
    ],
    runtime_deps = ["//src/main/java/com/google/devtools/build/lib/unix"],
    deps = [
        ":JunitUtils",
        ":TestConstants",
        ":TestSuite",
        ":TestUtils",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:common_prerequisite_validator",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/toolchain_type_requirement",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:rule_definition_environment",
        "//src/main/java/com/google/devtools/build/lib/analysis:template_variable_info",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/rules:core_rules",
        "//src/main/java/com/google/devtools/build/lib/rules/config",
        "//src/main/java/com/google/devtools/build/lib/rules/platform",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/net/starlark/java/syntax",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "BazelPackageBuilderHelperForTesting",
    testonly = 0,
    srcs = ["BazelPackageLoadingListenerForTesting.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages:BazelPackageLoader",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages:PackageLoader",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_interrupting_bug_reporter",
    srcs = ["TestInterruptingBugReporter.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_library(
    name = "TestRunnableWrapper",
    srcs = ["TestRunnableWrapper.java"],
    deps = [
        ":ThrowingRecordableRunnableWrapper",
        "//third_party:guava",
    ],
)

java_library(
    name = "ThrowingRecordableRunnableWrapper",
    srcs = ["ThrowableRecordingRunnableWrapper.java"],
    deps = [
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "TestThread",
    srcs = ["TestThread.java"],
    deps = [
        "//third_party:guava",
        "//third_party:truth",
    ],
)

java_library(
    name = "TestUtils",
    srcs = ["TestUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "TestSuite",
    srcs = [
        "ClasspathSuite.java",
        "CustomSuite.java",
        "TestSuiteBuilder.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:classpath",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_library(
    name = "EventUtils",
    srcs = [
        "EventIterableSubject.java",
        "EventIterableSubjectFactory.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:truth",
    ],
)

java_library(
    name = "JunitUtils",
    srcs = [
        "MoreAsserts.java",
    ],
    deps = [
        ":TestConstants",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

# Library exposing Blaze constants for TAP's usage.
java_library(
    name = "TestConstants",
    srcs = [
        "TestConstants.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline:cmdline-primitives",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//third_party:guava",
    ],
)

java_library(
    name = "SkyframeExecutorTestHelper",
    srcs = [
        "SkyframeExecutorTestHelper.java",
    ],
    deps = ["//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster"],
)

java_library(
    name = "TestPackageFactoryBuilderFactory",
    srcs = [
        "TestPackageFactoryBuilderFactory.java",
    ],
    deps = [
        ":PackageFactoryBuilderFactoryForBazelUnitTests",
        "//src/main/java/com/google/devtools/build/lib/packages:builder_factory_for_testing",
    ],
)

java_library(
    name = "PackageFactoryBuilderFactoryForBazelUnitTests",
    srcs = ["PackageFactoryBuilderFactoryForBazelUnitTests.java"],
    deps = [
        ":BazelPackageBuilderHelperForTesting",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:builder_factory_for_testing",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages:PackageFactoryBuilderWithSkyframeForTesting",
        "//src/main/java/com/google/devtools/build/lib/vfs",
    ],
)

java_library(
    name = "TestUtilTests_lib",
    srcs = [
        "ManualSleeperTest.java",
        "MoreAssertsTest.java",
        "TestInterruptingBugReporterTest.java",
    ],
    deps = [
        ":JunitUtils",
        ":test_interrupting_bug_reporter",
        ":testutil",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "FakeResourceOwner_lib",
    srcs = [
        "FakeResourceOwner.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:middleman_type",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_test(
    name = "TestUtilTests",
    size = "small",
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":TestUtilTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
