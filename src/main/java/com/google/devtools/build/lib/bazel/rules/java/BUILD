load("@rules_java//java:defs.bzl", "java_library")
load("//:distdir_deps.bzl", "gen_workspace_stanza")

package(
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]) + [
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/java/proto:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

exports_files(["java_stub_template.txt"])

java_library(
    name = "java",
    srcs = glob(
        ["*.java"],
        exclude = ["BazelJavaSemantics.java"],
    ),
    resources = glob(
        [
            "*.txt",
        ],
    ) + [
        ":jdk.WORKSPACE",
    ],
    deps = [
        ":bazel_java_semantics",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:build_info",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/execution_transition_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/toolchain_type_requirement",
        "//src/main/java/com/google/devtools/build/lib/analysis:rule_definition_environment",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-rules",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//third_party:guava",
    ],
)

java_library(
    name = "bazel_java_semantics",
    srcs = ["BazelJavaSemantics.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/custom_command_line",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/launcher_file_write_action",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/lazy_write_paths_file_action",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/substitution",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/template",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/template_expansion_action",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/test_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:transitive_info_collection",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:shell_escaper",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

gen_workspace_stanza(
    name = "workspace_with_rules_java",
    out = "jdk.WORKSPACE",
    repo_clause = """
    name = "{repo}",
    sha256 = "{sha256}",
    strip_prefix = {strip_prefix},
    urls = {urls},
    """,
    repos = [
        "remote_java_tools",
        "remote_java_tools_linux",
        "remote_java_tools_windows",
        "remote_java_tools_darwin",
        "rules_cc",
        "rules_java",
        "rules_proto",
        "remotejdk11_linux",
        "remotejdk11_linux_aarch64",
        "remotejdk11_linux_ppc64le",
        "remotejdk11_linux_s390x",
        "remotejdk11_macos",
        "remotejdk11_macos_aarch64",
        "remotejdk11_win",
        "remotejdk11_win_arm64",
        "remotejdk17_linux",
        "remotejdk17_macos",
        "remotejdk17_macos_aarch64",
        "remotejdk17_win",
        "remotejdk17_win_arm64",
    ],
    template = "jdk.WORKSPACE.tmpl",
    visibility = ["//:__pkg__"],
)
