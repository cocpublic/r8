// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.missingclasses;

import static com.android.tools.r8.utils.codeinspector.AssertUtils.assertFailsCompilationIf;

import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.R8TestBuilder;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ThrowableConsumer;
import com.android.tools.r8.diagnostic.MissingDefinitionContext;
import com.android.tools.r8.diagnostic.internal.MissingDefinitionMethodContext;
import com.android.tools.r8.references.Reference;
import com.google.common.collect.ImmutableList;
import java.util.Collections;
import org.junit.Test;

public class MissingClassReferencedFromUnusedLambdaReturnTest extends MissingClassesTestBase {

  private static final MissingDefinitionContext[] referencedFrom =
      new MissingDefinitionContext[] {
        MissingDefinitionMethodContext.builder()
            .setMethodContext(
                Reference.method(
                    Reference.classFromClass(Main.class),
                    "lambda$main$0",
                    Collections.emptyList(),
                    Reference.classFromClass(MissingClass.class)))
            .setOrigin(getOrigin(Main.class))
            .build(),
        MissingDefinitionMethodContext.builder()
            .setMethodContext(
                Reference.method(
                    Reference.classFromClass(Main.class),
                    "main",
                    ImmutableList.of(Reference.array(Reference.classFromClass(String.class), 1)),
                    null))
            .setOrigin(getOrigin(Main.class))
            .build(),
      };

  public MissingClassReferencedFromUnusedLambdaReturnTest(TestParameters parameters) {
    super(parameters);
  }

  @Test
  public void testNoRules() throws Exception {
    assertFailsCompilationIf(
        parameters.isCfRuntime(),
        () ->
            compileWithExpectedDiagnostics(
                Main.class,
                parameters.isCfRuntime()
                    ? diagnostics -> inspectDiagnosticsWithNoRules(diagnostics, referencedFrom)
                    : TestDiagnosticMessages::assertNoMessages,
                addInterface()));
  }

  // The lambda is never called, therefore the lambda class' virtual method is dead, and therefore
  // we never trace into lambda$main$0(). Therefore, we need allowUnusedDontWarnPatterns() here.
  @Test
  public void testDontWarnMainClass() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class,
        TestDiagnosticMessages::assertNoMessages,
        addInterface()
            .andThen(
                builder ->
                    builder
                        .addDontWarn(Main.class)
                        .applyIf(
                            parameters.isDexRuntime(),
                            R8TestBuilder::allowUnusedDontWarnPatterns)));
  }

  // The lambda is never called, therefore the lambda class' virtual method is dead, and therefore
  // we never trace into lambda$main$0(). Therefore, we need allowUnusedDontWarnPatterns() here.
  @Test
  public void testDontWarnMissingClass() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class,
        TestDiagnosticMessages::assertNoMessages,
        addInterface()
            .andThen(
                builder ->
                    builder
                        .addDontWarn(MissingClass.class)
                        .applyIf(
                            parameters.isDexRuntime(),
                            R8TestBuilder::allowUnusedDontWarnPatterns)));
  }

  @Test
  public void testIgnoreWarnings() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class,
        parameters.isCfRuntime()
            ? diagnostics -> inspectDiagnosticsWithIgnoreWarnings(diagnostics, referencedFrom)
            : TestDiagnosticMessages::assertNoMessages,
        addInterface()
            .andThen(
                builder ->
                    builder
                        .addIgnoreWarnings()
                        .applyIf(
                            parameters.isCfRuntime(),
                            R8TestBuilder::allowDiagnosticWarningMessages)));
  }

  ThrowableConsumer<R8FullTestBuilder> addInterface() {
    return builder -> builder.addProgramClasses(I.class);
  }

  static class Main {

    public static void main(String[] args) {
      I ignore = () -> null;
    }

    /* private static synthetic MissingClass lambda$main$0() { return null; } */
  }

  interface I {

    MissingClass m();
  }
}
