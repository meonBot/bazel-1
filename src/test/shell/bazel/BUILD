load("//:distdir_deps.bzl", "gen_workspace_stanza")

package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/test/shell/bazel/android:srcs",
        "//src/test/shell/bazel/apple:srcs",
        "//src/test/shell/bazel/remote:srcs",
        "//src/test/shell/bazel/testdata:srcs",
        "//tools/aquery_differ:srcs",
    ],
    visibility = ["//src/test/shell:__pkg__"],
)

genrule(
    name = "langtools-copy",
    testonly = 1,
    srcs = ["//third_party/java/jdk/langtools:javac_jar"],
    outs = ["langtools.jar"],
    cmd = "cp $< $@",
)

gen_workspace_stanza(
    name = "rules_proto_stanza",
    out = "rules_proto_stanza.txt",
    repos = [
        "rules_proto",
    ],
)

filegroup(
    name = "test-deps",
    testonly = 1,
    srcs = [
        ":test-deps-wo-bazel",
        "//src:bazel",
        "//src/test/shell:bin/bazel",
    ],
    visibility = [
        "//src/main/starlark/tests/builtins_bzl:__subpackages__",
        "//src/test/shell:__subpackages__",
    ],
)

filegroup(
    name = "test-deps-wo-bazel",
    testonly = 1,
    srcs = [
        "cc_api_rules.bzl",
        "remote_helpers.sh",
        "testing_server.py",
        ":langtools-copy",
        "//examples:srcs",
        "//src/conditions:srcs",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:GenClass_deploy.jar",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner:Runner_deploy.jar",
        "//src/main/tools:linux-sandbox",
        "//src/main/tools:process-wrapper",
        "//src/test/shell:bashunit",
        "//src/test/shell:integration_test_setup.sh",
        "//src/test/shell:testenv.sh",
        "//src/tools/singlejar",
        "//third_party:srcs",
        "//third_party/ijar",
        "//third_party/java/jdk/langtools:test-srcs",
        "//tools:srcs",
        "@bazel_skylib//:test_deps",
        "@bazel_tools//tools/jdk:current_java_runtime",
    ],
    visibility = [
        "//src/test/shell:__subpackages__",
    ],
)

sh_test(
    name = "bazel_symlink_test",
    size = "medium",
    srcs = ["bazel_symlink_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_example_test",
    size = "large",
    srcs = ["bazel_example_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
    tags = ["no_windows"],
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
)

sh_test(
    name = "bazel_windows_example_test",
    size = "large",
    srcs = ["bazel_windows_example_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "cpp_darwin_integration_test",
    size = "large",
    srcs = ["cpp_darwin_integration_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",  # darwin-specific test
    ],
)

sh_test(
    name = "bazel_tools_test",
    size = "large",
    srcs = ["bazel_tools_test.sh"],
    data = [
        ":test-deps",
        "//:workspace-file",
    ],
    tags = [
        "no_windows",  # objc-specific test
    ],
)

sh_test(
    name = "bazel_embedded_starlark_test",
    size = "medium",
    srcs = ["bazel_embedded_starlark_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",  # TODO(laszlocsomor): make this run on Windows
    ],
)

sh_test(
    name = "bazel_random_characters_test",
    size = "large",
    srcs = ["bazel_random_characters_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_wrapper_test",
    srcs = ["bazel_wrapper_test.sh"],
    data = [
        ":test-deps",
        "//scripts/packages:wrapper",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",  # wrapper is not used on Windows
    ],
)

sh_test(
    name = "bazel_java_test_no_windows",
    size = "large",
    timeout = "eternal",
    srcs = ["bazel_java_test_no_windows.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_java_test_defaults",
    srcs = ["bazel_java_test_defaults.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_java17_test",
    srcs = ["bazel_java17_test.sh"],
    args = [
        # java_tools zip to test
        "src/java_tools.zip",
        "src/java_tools_prebuilt.zip",
    ],
    data = [
        ":test-deps",
        "//src:java_tools_prebuilt_zip",
        "//src:java_tools_zip",
        "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["local"],
)

sh_test(
    name = "bazel_java_test",
    # TODO(iirina): Investigate if the 'large' and 'eternal' values still apply.
    size = "large",
    timeout = "eternal",
    srcs = ["bazel_java_test.sh"],
    args = [
        "released",
        "released",
    ],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
)

JAVA_VERSIONS = ("11", "17")

JAVA_VERSIONS_COVERAGE = ("11",)

[
    sh_test(
        name = "bazel_java_test_jdk" + java_version + "_toolchain_head",
        size = "large",
        timeout = "eternal",
        srcs = ["bazel_java_test.sh"],
        args = [
            # java_tools zips to test
            "src/java_tools.zip",
            "src/java_tools_prebuilt.zip",
            # --java_language_version value
            java_version,
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":test-deps",
            "//src:java_tools_prebuilt_zip",
            "//src:java_tools_zip",
            "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
            "@bazel_tools//tools/bash/runfiles",
        ],
        exec_compatible_with = ["//:highcpu_machine"],
    )
    for java_version in JAVA_VERSIONS
]

[
    sh_test(
        name = "bazel_java_test_local_java_tools_jdk" + java_version,
        size = "large",
        timeout = "eternal",
        srcs = ["bazel_java_test.sh"],
        args = [
            # java_tools zips to test
            "$(LOCAL_JAVA_TOOLS_ZIP_URL)",
            "$(LOCAL_JAVA_TOOLS_PREBUILT_ZIP_URL)",
            # --java_language_version value
            java_version,
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":test-deps",
            "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
            "@bazel_tools//tools/bash/runfiles",
        ],
        # This test is only run by the java_tools binaries pipeline.
        tags = ["manual"],
    )
    for java_version in JAVA_VERSIONS
]

sh_test(
    name = "bazel_android_tools_test",
    size = "small",
    srcs = ["bazel_android_tools_test.sh"],
    data = [
        ":test-deps",
        "//tools/android/runtime_deps:android_tools.tar.gz",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_java_tools_test",
    size = "medium",
    srcs = ["bazel_java_tools_test.sh"],
    data = [
        ":test-deps",
        "//src:java_tools.zip",
        "//src:java_tools_prebuilt.zip",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_java_tools_dist_test",
    size = "small",
    srcs = ["bazel_java_tools_dist_test.sh"],
    data = [
        ":test-deps",
        "//src:java_tools_dist.zip",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_proto_library_test",
    size = "large",  # Downloads and compiles protobuf for *every* *test* *case*
    srcs = ["bazel_proto_library_test.sh"],
    data = [
        ":rules_proto_stanza.txt",
        ":test-deps",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = ["no_windows"],  # Doesn't work on Windows for unknown reason
)

sh_test(
    name = "bazel_build_event_stream_test",
    size = "medium",
    srcs = ["bazel_build_event_stream_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_ui_test",
    size = "medium",
    srcs = ["bazel_ui_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_rules_test",
    size = "large",
    srcs = ["bazel_rules_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

sh_test(
    name = "bazel_rules_java_test",
    srcs = ["bazel_rules_java_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_rules_cc_test",
    srcs = ["bazel_rules_cc_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_test_test",
    timeout = "long",
    srcs = ["bazel_test_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_spawnstats_test",
    srcs = ["bazel_spawnstats_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_coverage_cc_released_test_gcc",
    srcs = ["bazel_coverage_cc_test_gcc.sh"],
    args = ["released"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_head_test_gcc",
    srcs = ["bazel_coverage_cc_test_gcc.sh"],
    args = [
        "tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage",
    ],
    data = [
        ":test-deps",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_head_test_llvm",
    srcs = ["bazel_coverage_cc_test_llvm.sh"],
    args = [
        "tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage",
    ],
    data = [
        ":test-deps",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_released_test_llvm",
    srcs = ["bazel_coverage_cc_test_llvm.sh"],
    args = ["released"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_java_test",
    srcs = ["bazel_coverage_java_test.sh"],
    args = [
        "released",
        "released",
        "released",
    ],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
    ],
    tags = [
        "manual",
        "no_windows",
    ],
)

# Test java coverage with the java_toolchain in the released java_tools versions.
[
    sh_test(
        name = "bazel_coverage_java_jdk" + java_version + "_toolchain_released_test",
        srcs = ["bazel_coverage_java_test.sh"],
        args = [
            # java_tools zip to test
            "released",
            "released",
            # coverage_report_generator to test
            "released",
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":test-deps",
            "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
        ],
        tags = [
            "manual",
            "no_windows",
        ],
    )
    for java_version in JAVA_VERSIONS_COVERAGE
]

# Test java coverage with the java_toolchain in the java_tools zip built at head.
[
    sh_test(
        name = "bazel_coverage_java_jdk" + java_version + "_toolchain_head_test",
        srcs = ["bazel_coverage_java_test.sh"],
        args = [
            # java_tools zips to test
            "src/java_tools.zip",
            "src/java_tools_prebuilt.zip",
            # coverage output generator to test
            "tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage",
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":test-deps",
            "//src:java_tools_prebuilt_zip",
            "//src:java_tools_zip",
            "//src/test/shell/bazel/testdata:jdk_http_archives_filegroup",
            "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo",
        ],
        tags = ["no_windows"],
    )
    for java_version in JAVA_VERSIONS_COVERAGE
]

sh_test(
    name = "bazel_coverage_sh_test",
    srcs = ["bazel_coverage_sh_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_starlark_test",
    srcs = ["bazel_coverage_starlark_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_cc_code_coverage_test",
    srcs = ["bazel_cc_code_coverage_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_thinlto_test",
    srcs = ["bazel_thinlto_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_layering_check_test",
    srcs = ["bazel_layering_check_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_localtest_test",
    srcs = ["bazel_localtest_test.sh"],
    data = [":test-deps"],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_execute_testlog",
    srcs = ["bazel_execute_testlog.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "allowlist_test",
    size = "medium",
    srcs = ["allowlist_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = ["no_windows"],
)

genquery(
    name = "embedded_tools_deps",
    expression = "kind(\"cc_(binary|library)\", deps(//src:embedded_tools_jdk_allmodules_srcs))",
    scope = ["//src:embedded_tools_jdk_allmodules_srcs"],
)

sh_test(
    name = "embedded_tools_deps_test",
    size = "medium",
    srcs = ["embedded_tools_deps_test.sh"],
    data = [
        ":embedded_tools_deps",
        ":test-deps",
        "//src/test/shell/bazel/testdata:embedded_tools_deps_test_data",
    ],
    tags = ["no_windows"],
)

genquery(
    name = "stardoc_deps",
    expression = "kind(\"^(?!package group$)\", deps(//src/main/java/com/google/devtools/build/skydoc:skydoc_lib))",
    opts = [
        "--notool_deps",
        "--noimplicit_deps",
    ],
    scope = ["//src/main/java/com/google/devtools/build/skydoc:skydoc_lib"],
)

sh_test(
    name = "stardoc_deps_test",
    size = "medium",
    srcs = ["stardoc_deps_test.sh"],
    data = [
        ":stardoc_deps",
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_docgen_test",
    size = "large",
    srcs = ["bazel_docgen_test.sh"],
    data = ["//src/main/java/com/google/devtools/build/lib:generated_docs"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "external_starlark_execute_test",
    size = "large",
    srcs = ["external_starlark_execute_test.sh"],
    data = [":test-deps"],
    shard_count = 2,
    tags = ["no_windows"],
)

sh_test(
    name = "external_correctness_test",
    size = "large",
    srcs = ["external_correctness_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "external_integration_test",
    size = "large",
    srcs = ["external_integration_test.sh"],
    data = [":test-deps"],
    shard_count = 15,
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "external_patching_test",
    size = "medium",
    srcs = ["external_patching_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

sh_test(
    name = "external_path_test",
    size = "medium",
    srcs = ["external_path_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 6,
    tags = ["no_windows"],
)

sh_test(
    name = "git_repository_test",
    size = "large",
    srcs = ["git_repository_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:git-repos",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "starlark_git_repository_test",
    size = "large",
    srcs = ["starlark_git_repository_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:git-repos",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
)

sh_test(
    name = "local_repository_test",
    size = "large",
    srcs = ["local_repository_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = ["no_windows"],
)

sh_test(
    name = "cross_repository_test",
    size = "large",
    srcs = ["cross_repository_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "starlark_prefetching_test",
    srcs = ["starlark_prefetching_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "external_starlark_load_test",
    size = "large",
    srcs = ["external_starlark_load_test.sh"],
    data = [":test-deps"],
    shard_count = 6,
    tags = ["no_windows"],
)

sh_test(
    name = "repository_abort_test",
    size = "large",
    srcs = ["repository_abort_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "starlark_repository_test",
    size = "large",
    srcs = ["starlark_repository_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 6,
    tags = [
        "requires-network",
    ],
)

sh_test(
    name = "starlark_rule_test",
    size = "large",
    srcs = ["starlark_rule_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "ninja_build_test",
    size = "large",
    srcs = ["ninja_build_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "tags_propagation_starlark_test",
    size = "large",
    srcs = ["tags_propagation_starlark_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "tags_propagation_native_test",
    size = "large",
    srcs = ["tags_propagation_native_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "disk_cache_test",
    size = "small",
    srcs = ["disk_cache_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "runfiles_test",
    size = "medium",
    srcs = ["runfiles_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "empty_package_test",
    srcs = ["empty_package.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "location_test",
    srcs = ["location_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

# To run this test, ensure that maven_dependency_plugin() and
# android_repositories() in WORKSPACE are uncommented.
sh_test(
    name = "maven_starlark_test",
    size = "medium",
    srcs = ["maven_starlark_test.sh"],
    data = [
        ":test-deps",
        "//external:android_sdk_for_testing",
        "@m2//:files",
    ],
    tags = [
        "manual",
        "no_windows",
    ],
)

sh_test(
    name = "python_version_test",
    size = "medium",
    srcs = ["python_version_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "workspace_test",
    size = "large",
    srcs = ["workspace_test.sh"],
    data = [":test-deps"],
    shard_count = 5,
    tags = ["no_windows"],
)

sh_test(
    name = "bazelignore_test",
    size = "medium",
    srcs = ["bazelignore_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

filegroup(
    name = "test_WORKSPACE",
    srcs = [
        "@test_WORKSPACE_files//:archives",
    ],
)

sh_test(
    name = "workspace_resolved_test",
    size = "large",
    srcs = ["workspace_resolved_test.sh"],
    data = [
        ":test-deps",
        ":test_WORKSPACE",
    ],
    shard_count = 22,
    tags = [
        "block-network",
        "no_windows",
    ],
)

sh_test(
    name = "cc_integration_test",
    size = "medium",
    srcs = ["cc_integration_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "cc_import_starlark_test",
    size = "medium",
    srcs = ["cc_import_starlark_test.sh"],
    data = [
        ":test-deps",
        "//tools/build_defs/cc:cc_import.bzl",
        "//tools/build_defs/cc/tests:cc_import_tests_files",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_docker_sandboxing_test",
    srcs = ["bazel_docker_sandboxing_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "local",
        "manual",  # TODO(philwo) re-enable once Bazel CI supports Docker again
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxing_test",
    size = "large",
    srcs = ["bazel_sandboxing_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_hermetic_sandboxing_test",
    size = "small",
    srcs = ["bazel_hermetic_sandboxing_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxing_cpp_test",
    srcs = ["bazel_sandboxing_cpp_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "persistent_test_runner_test",
    size = "large",
    srcs = ["persistent_test_runner_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_workspace_status_test",
    size = "large",
    srcs = ["bazel_workspace_status_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "client_test",
    size = "medium",
    srcs = ["client_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "execroot_test",
    size = "medium",
    srcs = ["execroot_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_repository_cache_test",
    size = "large",
    srcs = ["bazel_repository_cache_test.sh"],
    data = [":test-deps"],
    shard_count = 6,
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_with_jdk_test",
    size = "medium",
    srcs = ["bazel_with_jdk_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":test-deps",
        "//src:bazel",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
)

sh_test(
    name = "build_files_test",
    size = "medium",
    srcs = ["build_files_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "license_checking_test",
    size = "medium",
    srcs = ["license_checking_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_bootstrap_distfile_test",
    timeout = "eternal",
    srcs = ["bazel_bootstrap_distfile_test.sh"],
    args = [
        "$(location //:bazel-distfile)",
        "$(location //src:embedded_jdk_allmodules_cached)",
    ],
    data = [
        ":test-deps",
        "//:bazel-distfile",
        "//src:embedded_jdk_allmodules_cached",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = ["block-network"],
)

sh_test(
    name = "bazel_bootstrap_distfile_tar_test",
    timeout = "eternal",
    srcs = ["bazel_bootstrap_distfile_test.sh"],
    args = [
        "$(location //:bazel-distfile-tar)",
        "$(location //src:embedded_jdk_allmodules_cached)",
    ],
    data = [
        ":test-deps",
        "//:bazel-distfile-tar",
        "//src:embedded_jdk_allmodules_cached",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = [
        "block-network",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_determinism_test",
    timeout = "eternal",
    srcs = ["bazel_determinism_test.sh"],
    args = ["$(location //:bazel-distfile)"],
    data = [
        ":test-deps",
        "//:bazel-distfile",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = [
        "no_windows",
        "slow",
    ],
)

sh_test(
    name = "rule_test_test",
    size = "medium",
    srcs = ["rule_test_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 2,
)

sh_test(
    name = "help_test",
    size = "small",
    srcs = ["help_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "java_launcher_test",
    size = "medium",
    srcs = ["java_launcher_test.sh"],
    args = ["$(JAVABASE)"],
    data = [":test-deps"],
    tags = ["no_windows"],
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
)

genquery(
    name = "srcs_list",
    expression = "kind(\"source file\", deps(//:srcs))",
    scope = ["//:srcs"],
)

sh_test(
    name = "srcs_test",
    size = "small",
    srcs = ["srcs_test.sh"],
    data = [
        ":srcs_list",
        "@local_bazel_source_list//:sources",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_strategy_test",
    size = "small",
    srcs = ["bazel_strategy_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

test_suite(
    name = "all_tests",
    visibility = ["//visibility:public"],
)

sh_test(
    name = "bazel_workspaces_test",
    srcs = ["bazel_workspaces_test.sh"],
    data = [
        ":test-deps",
        "//src/tools/workspacelog:parser",
    ],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "jdeps_test",
    size = "large",
    srcs = ["jdeps_test.sh"],
    data = [
        ":jdeps_class_denylist.txt",
        ":test-deps",
        "//src:embedded_jdk_allmodules",
        "//src:jdeps_modules.golden",
        "//src/main/java/com/google/devtools/build/lib/bazel:BazelServer_deploy.jar",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "cc_flags_supplier_test",
    size = "medium",
    srcs = ["cc_flags_supplier_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "generate_xml_test",
    srcs = ["generate_xml_test.sh"],
    data = [
        "//src/test/shell:bashunit",
        "//tools/test:test_xml_generator",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "windows_arg_esc_test",
    srcs = ["windows_arg_esc_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "bazel_execlog_test",
    srcs = ["bazel_execlog_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "new_local_repo_test",
    srcs = ["new_local_repo_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "archive_contents_test",
    srcs = ["archive_contents_test.sh"],
    data = [":test-deps"],
    tags = [
        "requires-network",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "platforms_test",
    srcs = ["platforms_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "platform_mapping_test",
    srcs = ["platform_mapping_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "resource_compiler_toolchain_test",
    srcs = ["resource_compiler_toolchain_test.sh"],
    data = [
        ":test-deps",
        "//src/main/res:srcs_for_testing",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "unicode_filenames_test",
    srcs = ["unicode_filenames_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "run_test",
    srcs = ["run_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "unix_digest_hash_attribute_name_test",
    srcs = ["unix_digest_hash_attribute_name_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "verify_workspace",
    srcs = ["verify_workspace.sh"],
    data = [
        ":test-deps",
        "//:workspace-file",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "check_external_files_test",
    srcs = ["check_external_files_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)
