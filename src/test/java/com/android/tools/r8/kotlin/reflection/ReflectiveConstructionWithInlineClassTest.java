// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin.reflection;

import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.KotlinCompilerTool.KotlinCompilerVersion;
import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.KotlinTestParameters;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.kotlin.metadata.KotlinMetadataTestBase;
import com.android.tools.r8.shaking.ProguardKeepAttributes;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.FileUtils;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

// See b/230369515 for context.
@RunWith(Parameterized.class)
public class ReflectiveConstructionWithInlineClassTest extends KotlinTestBase {

  private final TestParameters parameters;
  private static final String EXPECTED_OUTPUT = "Value(rawValue=0)";
  private static final String PKG =
      ReflectiveConstructionWithInlineClassTest.class.getPackage().getName();
  private static final String KOTLIN_FILE = "ReflectiveConstructionWithInlineClass";
  private static final String MAIN_CLASS = PKG + "." + KOTLIN_FILE + "Kt";

  private static final KotlinCompileMemoizer compiledJars =
      getCompileMemoizer(
          Paths.get(
              ToolHelper.TESTS_DIR,
              "java",
              DescriptorUtils.getBinaryNameFromJavaType(PKG),
              KOTLIN_FILE + FileUtils.KT_EXTENSION));

  @Parameters(name = "{0}, {1}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(),
        getKotlinTestParameters()
            // Internal classes are supported from Kotlin 1.5.
            .withCompilersStartingFromIncluding(KotlinCompilerVersion.KOTLINC_1_5_0)
            .withOldCompilersStartingFrom(KotlinCompilerVersion.KOTLINC_1_5_0)
            .withAllTargetVersions()
            .build());
  }

  public ReflectiveConstructionWithInlineClassTest(
      TestParameters parameters, KotlinTestParameters kotlinParameters) {
    super(kotlinParameters);
    this.parameters = parameters;
  }

  @Test
  public void testCf() throws Exception {
    assumeTrue(parameters.isCfRuntime());
    testForJvm()
        .addProgramFiles(compiledJars.getForConfiguration(kotlinc, targetVersion))
        .addProgramFiles(kotlinc.getKotlinStdlibJar())
        .addProgramFiles(kotlinc.getKotlinReflectJar())
        .addLibraryFiles(ToolHelper.getAndroidJar(AndroidApiLevel.LATEST))
        .run(parameters.getRuntime(), MAIN_CLASS)
        .assertSuccessWithOutputLines(EXPECTED_OUTPUT);
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    testForD8(parameters.getBackend())
        .addProgramFiles(compiledJars.getForConfiguration(kotlinc, targetVersion))
        .addProgramFiles(kotlinc.getKotlinStdlibJar())
        .addProgramFiles(kotlinc.getKotlinReflectJar())
        .setMinApi(parameters.getApiLevel())
        .addOptionsModification(
            options -> {
              options.testing.enableD8ResourcesPassThrough = true;
              options.dataResourceConsumer = options.programConsumer.getDataResourceConsumer();
            })
        .run(parameters.getRuntime(), MAIN_CLASS)
        .assertSuccessWithOutputLines(EXPECTED_OUTPUT);
  }

  private R8FullTestBuilder configureR8(R8FullTestBuilder builder) {
    return builder
        .addProgramFiles(compiledJars.getForConfiguration(kotlinc, targetVersion))
        .addProgramFiles(kotlinc.getKotlinStdlibJar())
        .addProgramFiles(kotlinc.getKotlinReflectJar())
        .addProgramFiles(kotlinc.getKotlinAnnotationJar())
        .addLibraryFiles(ToolHelper.getAndroidJar(AndroidApiLevel.LATEST))
        .setMinApi(parameters.getApiLevel())
        .addKeepMainRule(MAIN_CLASS)
        .addKeepClassAndMembersRules(PKG + ".Data")
        .addKeepEnumsRule()
        .addKeepAttributes(ProguardKeepAttributes.RUNTIME_VISIBLE_ANNOTATIONS)
        .allowDiagnosticMessages()
        .allowUnusedDontWarnKotlinReflectJvmInternal();
  }

  @Test
  public void testR8KeepDataClass() throws Exception {
    configureR8(testForR8(parameters.getBackend()))
        .compile()
        .assertNoErrorMessages()
        .apply(KotlinMetadataTestBase::verifyExpectedWarningsFromKotlinReflectAndStdLib)
        .run(parameters.getRuntime(), MAIN_CLASS)
        .assertFailureWithErrorThatThrows(IllegalArgumentException.class);
  }

  @Test
  public void testR8KeepDataClassAndInlineClass() throws Exception {
    configureR8(testForR8(parameters.getBackend()))
        .addKeepRules("-keep class " + PKG + ".Value { *; }")
        .compile()
        .assertNoErrorMessages()
        .apply(KotlinMetadataTestBase::verifyExpectedWarningsFromKotlinReflectAndStdLib)
        .run(parameters.getRuntime(), MAIN_CLASS)
        .assertSuccessWithOutputLines(EXPECTED_OUTPUT);
  }
}
