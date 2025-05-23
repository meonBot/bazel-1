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

package com.google.devtools.build.lib.rules.objc;

import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.apple.DottedVersionConverter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import java.util.List;

/** Command-line options for building Objective-C targets. */
public class ObjcCommandLineOptions extends FragmentOptions {
  @Option(
      name = "ios_simulator_version",
      defaultValue = "null",
      converter = DottedVersionConverter.class,
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help =
          "The version of iOS to run on the simulator when running or testing. This is ignored "
              + "for ios_test rules if a target device is specified in the rule.")
  public DottedVersion.Option iosSimulatorVersion;

  @Option(
      name = "ios_simulator_device",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help =
          "The device to simulate when running an iOS application in the simulator, e.g. "
              + "'iPhone 6'. You can get a list of devices by running 'xcrun simctl list "
              + "devicetypes' on the machine the simulator will be run on.")
  public String iosSimulatorDevice;

  @Option(
      name = "watchos_simulator_version",
      defaultValue = "null",
      converter = DottedVersionConverter.class,
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help = "The version of watchOS to run on the simulator when running or testing.")
  public DottedVersion.Option watchosSimulatorVersion;

  @Option(
      name = "watchos_simulator_device",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help =
          "The device to simulate when running an watchOS application in the simulator, e.g. "
              + "'Apple Watch - 38mm'. You can get a list of devices by running 'xcrun simctl list "
              + "devicetypes' on the machine the simulator will be run on.")
  public String watchosSimulatorDevice;

  @Option(
      name = "tvos_simulator_version",
      defaultValue = "null",
      converter = DottedVersionConverter.class,
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help = "The version of tvOS to run on the simulator when running or testing.")
  public DottedVersion.Option tvosSimulatorVersion;

  @Option(
      name = "tvos_simulator_device",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TESTING,
      effectTags = {OptionEffectTag.TEST_RUNNER},
      help =
          "The device to simulate when running an tvOS application in the simulator, e.g. "
              + "'Apple TV 1080p'. You can get a list of devices by running 'xcrun simctl list "
              + "devicetypes' on the machine the simulator will be run on.")
  public String tvosSimulatorDevice;

  @Option(
    name = "objc_generate_linkmap",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help = "Specifies whether to generate a linkmap file."
  )
  public boolean generateLinkmap;

  @Option(
      name = "objccopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      help = "Additional options to pass to Objective C compilation.")
  public List<String> copts;

  @Option(
    name = "ios_memleaks",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.TESTING,
    effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
    help = "Enable checking for memory leaks in ios_test targets."
  )
  public boolean runMemleaks;

  @Option(
    name = "experimental_enable_objc_cc_deps",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
    help =
        "Allows objc_* rules to depend on cc_library and causes any objc dependencies to be "
            + "built with --cpu set to \"ios_<--ios_cpu>\" for any values in --ios_multi_cpu."
  )
  public boolean enableCcDeps;

  @Option(
    name = "experimental_objc_fastbuild_options",
    defaultValue = "-O0,-DDEBUG=1",
    converter = CommaSeparatedOptionListConverter.class,
    documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
    effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
    help = "Uses these strings as objc fastbuild compiler options."
  )
  public List<String> fastbuildOptions;

  @Option(
      name = "objc_enable_binary_stripping",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      help =
          "Whether to perform symbol and dead-code strippings on linked binaries. Binary "
              + "strippings will be performed if both this flag and --compilation_mode=opt are "
              + "specified.")
  public boolean enableBinaryStripping;

  @Option(
    name = "ios_signing_cert_name",
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.SIGNING,
    effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
    help =
        "Certificate name to use for iOS signing. If not set will fall back to provisioning "
            + "profile. May be the certificate's keychain identity preference or (substring) of "
            + "the certificate's common name, as per codesign's man page (SIGNING IDENTITIES)."
  )
  public String iosSigningCertName;

  @Option(
    name = "objc_debug_with_GLIBCXX",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
    effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
    help =
        "If set, and compilation mode is set to 'dbg', define GLIBCXX_DEBUG, "
            + " GLIBCXX_DEBUG_PEDANTIC and GLIBCPP_CONCEPT_CHECKS."
  )
  public boolean debugWithGlibcxx;

  @Option(
    name = "device_debug_entitlements",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.SIGNING,
    effectTags = {OptionEffectTag.CHANGES_INPUTS},
    help =
        "If set, and compilation mode is not 'opt', objc apps will include debug entitlements "
            + "when signing."
  )
  public boolean deviceDebugEntitlements;

  @Option(
      name = "incompatible_avoid_hardcoded_objc_compilation_flags",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
        OptionEffectTag.EXECUTION,
        OptionEffectTag.ACTION_COMMAND_LINES,
      },
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Prevents Bazel from adding compiler options to Objective-C compilation actions. Options"
              + " set in the crosstool are still applied.")
  public boolean incompatibleAvoidHardcodedObjcCompilationFlags;

  /** @deprecated delete when we are sure it's not used anywhere. */
  @Deprecated
  @Option(
      name = "incompatible_disable_native_apple_binary_rule",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {
        OptionEffectTag.EAGERNESS_TO_EXIT,
      },
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "No-op. Kept here for backwards compatibility.")
  public boolean incompatibleDisableNativeAppleBinaryRule;
}
