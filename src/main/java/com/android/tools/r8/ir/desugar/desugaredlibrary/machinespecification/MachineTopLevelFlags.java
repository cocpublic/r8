// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.desugaredlibrary.machinespecification;

import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

public class MachineTopLevelFlags {

  private final AndroidApiLevel requiredCompilationAPILevel;
  private final String synthesizedLibraryClassesPackagePrefix;
  private final String identifier;
  private final String jsonSource;
  // Setting supportAllCallbacksFromLibrary reduces the number of generated call-backs,
  // more specifically:
  // - no call-back is generated for emulated interface method overrides (forEach, etc.)
  // - no call-back is generated inside the desugared library itself.
  // Such setting decreases significantly the desugared library dex file, but virtual calls from
  // within the library to desugared library classes instances as receiver may be incorrect, for
  // example the method forEach in Iterable may be executed over a concrete implementation.
  private final boolean supportAllCallbacksFromLibrary;
  private final List<String> extraKeepRules;

  public static MachineTopLevelFlags empty() {
    return new MachineTopLevelFlags(
        AndroidApiLevel.B, "unused", null, null, false, ImmutableList.of());
  }

  public MachineTopLevelFlags(
      AndroidApiLevel requiredCompilationAPILevel,
      String synthesizedLibraryClassesPackagePrefix,
      String identifier,
      String jsonSource,
      boolean supportAllCallbacksFromLibrary,
      List<String> extraKeepRules) {
    this.requiredCompilationAPILevel = requiredCompilationAPILevel;
    this.synthesizedLibraryClassesPackagePrefix = synthesizedLibraryClassesPackagePrefix;
    this.identifier = identifier;
    this.jsonSource = jsonSource;
    this.supportAllCallbacksFromLibrary = supportAllCallbacksFromLibrary;
    this.extraKeepRules = extraKeepRules;
  }

  public AndroidApiLevel getRequiredCompilationApiLevel() {
    return requiredCompilationAPILevel;
  }

  public String getSynthesizedLibraryClassesPackagePrefix() {
    return synthesizedLibraryClassesPackagePrefix;
  }

  public String getIdentifier() {
    return identifier;
  }

  public String getJsonSource() {
    return jsonSource;
  }

  public boolean supportAllCallbacksFromLibrary() {
    return supportAllCallbacksFromLibrary;
  }

  public List<String> getExtraKeepRules() {
    return extraKeepRules;
  }

  public String getExtraKeepRulesConcatenated() {
    return String.join("\n", extraKeepRules);
  }

  public MachineTopLevelFlags withPostPrefix(String postPrefix) {
    assert postPrefix.endsWith(String.valueOf(DescriptorUtils.JAVA_PACKAGE_SEPARATOR));
    String prefix =
        DescriptorUtils.getJavaTypeFromBinaryName(synthesizedLibraryClassesPackagePrefix);
    String cleanPostPrefix = DescriptorUtils.getJavaTypeFromBinaryName(postPrefix);
    String newPrefix = prefix + cleanPostPrefix;
    String newPrefixWithSlash = DescriptorUtils.getPackageBinaryNameFromJavaType(newPrefix);
    List<String> newKeepRules = new ArrayList<>(extraKeepRules.size());
    for (String kr : extraKeepRules) {
      // TODO(b/278046666): Consider changing the ProguardRuleParser to avoid invalid replacements.
      newKeepRules.add(kr.replace(prefix, newPrefix));
    }
    return new MachineTopLevelFlags(
        requiredCompilationAPILevel,
        newPrefixWithSlash,
        identifier,
        jsonSource,
        supportAllCallbacksFromLibrary,
        newKeepRules);
  }
}
