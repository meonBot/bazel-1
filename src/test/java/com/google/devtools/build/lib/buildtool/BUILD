load("@rules_java//java:defs.bzl", "java_library", "java_test")
load("@bazel_skylib//rules:build_test.bzl", "build_test")

package(
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]) + [
        "//src/test/java/com/google/devtools/build/lib/buildtool/util:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "JobsConverterTest",
    srcs = [
        "JobsConverterTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:build-request-options",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SymlinkForestTest",
    srcs = [
        "SymlinkForestTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

test_suite(
    name = "BuildtoolTests",
    tests = [
        ":JobsConverterTest",
        ":SymlinkForestTest",
    ],
)

java_test(
    name = "ActionListenerIntegrationTest",
    srcs = ["ActionListenerIntegrationTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:extra_action_artifacts_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:extra_actions_base_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CompileAfterOptionChangeTest",
    srcs = ["CompileAfterOptionChangeTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CompileOneDependencyIntegrationTest",
    srcs = ["CompileOneDependencyIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ContextProviderInitializationTest",
    srcs = ["ContextProviderInitializationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_builder",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_lifecycle_listener",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "CorruptedActionCacheTest",
    srcs = ["CorruptedActionCacheTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "DanglingSymlinkTest",
    srcs = ["DanglingSymlinkTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "UnusedInputsFailureIntegrationTest",
    srcs = ["UnusedInputsFailureIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:detailed_exceptions",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_testparameterinjector//:testparameterinjector",
    ],
)

java_test(
    name = "DirectoryArtifactWarningTest",
    srcs = ["DirectoryArtifactWarningTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "EditDuringBuildTest",
    srcs = ["EditDuringBuildTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "EnvironmentRestrictedBuildTest",
    srcs = ["EnvironmentRestrictedBuildTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "GenQueryIntegrationTest",
    srcs = ["GenQueryIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "IncludeValidationTest",
    srcs = ["IncludeValidationTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "InconsistentFilesystemTest",
    srcs = ["InconsistentFilesystemTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "InstrumentationFilterSupportTest",
    srcs = [
        "InstrumentationFilterSupportTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "KeepGoingTest",
    srcs = ["KeepGoingTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "LabelCrossesPackageBoundaryTest",
    srcs = ["LabelCrossesPackageBoundaryTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "MiscAnalysisTest",
    srcs = ["MiscAnalysisTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "MissingInputActionTest",
    srcs = ["MissingInputActionTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/bazel:modules",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "NoOutputActionTest",
    srcs = ["NoOutputActionTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "OutputArtifactConflictTest",
    srcs = ["OutputArtifactConflictTest.java"],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_testparameterinjector//:testparameterinjector",
    ],
)

java_test(
    name = "PackageGroupIntegrationTest",
    srcs = ["PackageGroupIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "ProgressReportingTest",
    srcs = ["ProgressReportingTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "QueryIntegrationTest",
    srcs = ["QueryIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2/query/output",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/skyframe:per_build_syscall_cache",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:build_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/events:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/skyframe:testutil",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/protobuf:protobuf_java",
        "@com_google_testparameterinjector//:testparameterinjector",
    ],
)

java_test(
    name = "SymlinkDependencyAnalysisTest",
    srcs = ["SymlinkDependencyAnalysisTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "TransitiveDataDependencyTest",
    srcs = ["TransitiveDataDependencyTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "TargetCompleteEventTest",
    srcs = ["TargetCompleteEventTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "BuildResultTestCase",
    srcs = ["BuildResultTestCase.java"],
    shard_count = 4,
    tags = [
        "manual",
        "no_windows",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = ["//src/test/java/com/google/devtools/build/lib:test_runner"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "AqueryBuildToolTest",
    srcs = ["AqueryBuildToolTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

# TODO(b/19011399): Remove any manual tags used to disable the tests on bazel,
#  and enable manual tests inside Google if appropriate.
# Keep in sync with Copybara transformation that makes these tests manual.

build_test(
    name = "all_manual_tests",
    targets = [
        ":ActionListenerIntegrationTest",
        ":BuildResultTestCase",
        ":CompileAfterOptionChangeTest",
        ":IncludeValidationTest",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/bazel/rules/android:AndroidTests",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/bazel/rules/python:PythonTests",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/bazel/rules/sh:ShTests",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/packages:BazelDocumentationTests",
        "//src/test/java/com/google/devtools/build/lib/rules/android:AndroidDataBindingV2Test",
    ],
)

java_test(
    name = "ShutdownCommandTest",
    srcs = ["ShutdownCommandTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SubcommandEventTest",
    srcs = ["SubcommandEventTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SkymeldBuildIntegrationTest",
    srcs = ["SkymeldBuildIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_testparameterinjector//:testparameterinjector",
    ],
)
