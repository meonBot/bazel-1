load("@rules_java//java:defs.bzl", "java_binary", "java_library")

package(default_visibility = ["//src:__subpackages__"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "profiler",
    srcs = [
        "AutoProfiler.java",
        "CollectLocalResourceUsage.java",
        "MemoryProfiler.java",
        "MetricData.java",
        "MnemonicData.java",
        "PredicateBasedStatRecorder.java",
        "ProfilePhase.java",
        "Profiler.java",
        "ProfilerTask.java",
        "SilentCloseable.java",
        "SingleStatRecorder.java",
        "StatRecorder.java",
        "TimeSeries.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/collect:extrema",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/unix:procmeminfo_parser",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "google-auto-profiler-utils",
    srcs = ["GoogleAutoProfilerUtils.java"],
    deps = [
        ":profiler",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "profiler-output",
    srcs =
        [
            "JsonProfile.java",
            "TraceEvent.java",
        ] + glob([
            "output/*.java",
            "statistics/*.java",
        ]),
    deps = [
        ":profiler",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//third_party:auto_value",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_binary(
    name = "profiler-grapher",
    srcs = glob([
        "grapher/*.java",
    ]),
    main_class = "com.google.devtools.build.lib.profiler.grapher.ProfileGrapher",
    deps = [
        ":profiler",
        "//third_party:gson",
        "//third_party:guava",
    ],
)
