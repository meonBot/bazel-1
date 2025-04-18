# -*- coding: utf-8 -*-
load("//tools/build_defs/pkg:pkg.bzl", "pkg_tar")
load("//tools/python:private/defs.bzl", "py_binary", "py_library", "py_test")

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//tools/build_defs:__pkg__"],
)

py_library(
    name = "archive",
    srcs = ["archive.py"],
    srcs_version = "PY2AND3",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "archive_testdata",
    srcs = glob(["testdata/**"]),
)

py_test(
    name = "archive_test",
    srcs = [
        "archive_test.py",
        "testenv.py",
    ],
    data = [":archive_testdata"],
    python_version = "PY3",
    srcs_version = "PY3",
    tags = [
        # TODO(laszlocsomor): fix on Windows or describe why it cannot pass.
        "no_windows",
    ],
    deps = [":archive"],
)

py_test(
    name = "path_test",
    srcs = ["path_test.py"],
    data = ["path.bzl"],
    python_version = "PY3",
    srcs_version = "PY3",
)

py_binary(
    name = "build_tar",
    srcs = ["build_tar.py"],
    python_version = "PY3",
    srcs_version = "PY2AND3",
    visibility = ["//visibility:public"],
    deps = [
        ":archive",
        "//third_party/py/abseil",
    ],
)

# tests

genrule(
    name = "generate_files",
    outs = [
        "etc/nsswitch.conf",
        "usr/titi",
    ],
    cmd = "for i in $(OUTS); do echo 1 >$$i; done",
)

[pkg_tar(
    name = "test-tar-%s" % ext[1:],
    srcs = [
        ":etc/nsswitch.conf",
        ":usr/titi",
    ],
    build_tar = ":build_tar",
    extension = "tar%s" % ext,
    mode = "0644",
    modes = {"usr/titi": "0755"},
    owner = "42.24",
    ownername = "titi.tata",
    owners = {"etc/nsswitch.conf": "24.42"},
    package_dir = "/",
    strip_prefix = ".",
    symlinks = {"usr/bin/java": "/path/to/bin/java"},
) for ext in [
    "",
    ".gz",
    ".bz2",
]]

[pkg_tar(
    name = "test-tar-inclusion-%s" % ext,
    build_tar = ":build_tar",
    deps = [":test-tar-%s" % ext],
) for ext in [
    "",
    "gz",
    "bz2",
]]

pkg_tar(
    name = "test-tar-strip_prefix-empty",
    srcs = [
        ":etc/nsswitch.conf",
    ],
    build_tar = ":build_tar",
    strip_prefix = "",
)

pkg_tar(
    name = "test-tar-strip_prefix-none",
    srcs = [
        ":etc/nsswitch.conf",
    ],
    build_tar = ":build_tar",
)

pkg_tar(
    name = "test-tar-strip_prefix-etc",
    srcs = [
        ":etc/nsswitch.conf",
    ],
    build_tar = ":build_tar",
    strip_prefix = "etc",
)

pkg_tar(
    name = "test-tar-strip_prefix-dot",
    srcs = [
        ":etc/nsswitch.conf",
    ],
    build_tar = ":build_tar",
    strip_prefix = ".",
)

pkg_tar(
    name = "test-tar-files_dict",
    build_tar = ":build_tar",
    files = {
        ":etc/nsswitch.conf": "not-etc/mapped-filename.conf",
    },
)

pkg_tar(
    name = "test-tar-mtime",
    srcs = [
        ":etc/nsswitch.conf",
    ],
    build_tar = ":build_tar",
    mtime = 946684740,  # 1999-12-31, 23:59
    portable_mtime = False,
)

sh_test(
    name = "build_test",
    size = "medium",
    srcs = [
        "build_test.sh",
    ],
    data = [
        "testenv.sh",
        ":test-tar-.tar",
        ":test-tar-bz2.tar.bz2",
        ":test-tar-files_dict.tar",
        ":test-tar-gz.tar.gz",
        ":test-tar-inclusion-.tar",
        ":test-tar-inclusion-bz2.tar",
        ":test-tar-inclusion-gz.tar",
        ":test-tar-mtime.tar",
        ":test-tar-strip_prefix-dot.tar",
        ":test-tar-strip_prefix-empty.tar",
        ":test-tar-strip_prefix-etc.tar",
        ":test-tar-strip_prefix-none.tar",
    ],
    tags = [
        # TODO(laszlocsomor): fix on Windows or describe why it cannot pass.
        "no_windows",
    ],
    deps = [
        "//src/test/shell:bashunit",
    ],
)

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]),
    visibility = ["//tools/build_defs:__pkg__"],
)
