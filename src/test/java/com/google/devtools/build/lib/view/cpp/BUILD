load("@rules_java//java:defs.bzl", "java_test")

licenses(["notice"])

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "CppTemplateTest",
    srcs = ["CppTemplateTest.java"],
    tags = ["manual"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_testparameterinjector//:testparameterinjector",
    ],
)
