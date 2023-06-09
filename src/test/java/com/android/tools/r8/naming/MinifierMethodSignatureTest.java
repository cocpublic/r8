// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming;

import static com.android.tools.r8.DiagnosticsMatcher.diagnosticMessage;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresentAndRenamed;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.ProguardKeepAttributes;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

@RunWith(Parameterized.class)
public class MinifierMethodSignatureTest extends TestBase {

  private final String genericSignature = "<T:Ljava/lang/Throwable;>(TT;LMethods<TT;>.Inner;)TT;";
  private final String parameterizedReturnSignature = "()LMethods<TX;>.Inner;";
  private final String parameterizedArgumentsSignature = "(TX;LMethods<TX;>.Inner;)V";
  private final String parametrizedThrowsSignature = "()V^TX;";

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public MinifierMethodSignatureTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void originalJavacSignatures() throws Exception {
    // Test using the signatures generated by javac.
    runTest(ImmutableMap.of(), this::noWarnings, this::noInspection);
  }

  @Test
  public void signatureEmpty() throws Exception {
    testSingleMethod(
        "generic",
        "",
        this::noWarnings,
        inspector -> {
          noSignatureAttribute(lookupGeneric(inspector));
        });
  }

  @Test
  public void signatureInvalid() throws Exception {
    testSingleMethod(
        "generic",
        "X",
        diagnostics -> {
          diagnostics.assertWarningsCount(1);
          diagnostics.assertWarningsMatch(
              diagnosticMessage(
                  allOf(
                      containsString("Invalid signature 'X' for method generic"),
                      containsString("Expected ( at position 1"))));
        },
        inspector -> noSignatureAttribute(lookupGeneric(inspector)));
  }

  @Test
  public void classNotFound() throws Exception {
    String signature = "<T:LNotFound;>(TT;LAlsoNotFound$InnerNotFound$InnerAlsoNotFound;)TT;";
    testSingleMethod(
        "generic",
        signature,
        this::noWarnings,
        inspector -> {
          ClassSubject methods = inspector.clazz("Methods");
          MethodSubject method =
              methods.method(
                  "java.lang.Throwable",
                  "generic",
                  ImmutableList.of("java.lang.Throwable", "Methods$Inner"));
          assertThat(inspector.clazz("NotFound"), not(isPresent()));
          assertEquals(signature, method.getOriginalSignatureAttribute());
        });
  }

  @Test
  public void multipleWarnings() throws Exception {
    runTest(
        ImmutableMap.of(
            "generic", "X",
            "parameterizedReturn", "X",
            "parameterizedArguments", "X"),
        diagnostics -> {
          diagnostics.assertWarningsCount(3);
        },
        inspector -> {
          noSignatureAttribute(lookupGeneric(inspector));
          noSignatureAttribute(lookupParameterizedReturn(inspector));
          noSignatureAttribute(lookupParameterizedArguments(inspector));
        });
  }

  private void testSingleMethod(
      String name,
      String signature,
      Consumer<TestDiagnosticMessages> diagnostics,
      Consumer<CodeInspector> inspector)
      throws Exception {
    ImmutableMap<String, String> signatures = ImmutableMap.of(name, signature);
    runTest(signatures, diagnostics, inspector);
  }

  private void isOriginUnknown(Origin origin) {
    assertSame(Origin.unknown(), origin);
  }

  private void noWarnings(TestDiagnosticMessages messages) {
    messages.assertNoWarnings();
  }

  private void noInspection(CodeInspector inspector) {}

  private void noSignatureAttribute(MethodSubject method) {
    assertThat(method, isPresent());
    assertNull(method.getFinalSignatureAttribute());
    assertNull(method.getOriginalSignatureAttribute());
  }

  public void runTest(
      ImmutableMap<String, String> signatures,
      Consumer<TestDiagnosticMessages> diagnostics,
      Consumer<CodeInspector> inspect)
      throws Exception {

    R8TestCompileResult compileResult =
        testForR8(parameters.getBackend())
            .addProgramClassFileData(dumpMethods(signatures), dumpInner())
            .addKeepAttributes(
                ProguardKeepAttributes.INNER_CLASSES,
                ProguardKeepAttributes.ENCLOSING_METHOD,
                ProguardKeepAttributes.SIGNATURE)
            .addKeepAllClassesRuleWithAllowObfuscation()
            .setMinApi(parameters)
            .addOptionsModification(
                internalOptions ->
                    internalOptions.testing.disableMappingToOriginalProgramVerification = true)
            .allowDiagnosticMessages()
            .compile();

    CodeInspector inspector = compileResult.inspector();

    // All classes are kept, and renamed.
    ClassSubject clazz = inspector.clazz("Methods");
    assertThat(clazz, isPresentAndRenamed());
    assertThat(inspector.clazz("Methods$Inner"), isPresentAndRenamed());

    MethodSubject generic = lookupGeneric(inspector);
    MethodSubject parameterizedReturn = lookupParameterizedReturn(inspector);
    MethodSubject parameterizedArguments = lookupParameterizedArguments(inspector);
    MethodSubject parametrizedThrows =
        clazz.method("void", "parametrizedThrows", ImmutableList.of());

    // Check that all methods have been renamed
    assertThat(generic, isPresentAndRenamed());
    assertThat(parameterizedReturn, isPresentAndRenamed());
    assertThat(parameterizedArguments, isPresentAndRenamed());
    assertThat(parametrizedThrows, isPresentAndRenamed());

    // Test that methods have their original signature if the default was provided.
    if (!signatures.containsKey("generic")) {
      assertEquals(genericSignature, generic.getOriginalSignatureAttribute());
    }
    if (!signatures.containsKey("parameterizedReturn")) {
      assertEquals(
          parameterizedReturnSignature, parameterizedReturn.getOriginalSignatureAttribute());
    }
    if (!signatures.containsKey("parameterizedArguments")) {
      assertEquals(
          parameterizedArgumentsSignature, parameterizedArguments.getOriginalSignatureAttribute());
    }
    if (!signatures.containsKey("parametrizedThrows")) {
      assertEquals(parametrizedThrowsSignature, parametrizedThrows.getOriginalSignatureAttribute());
    }

    inspect.accept(inspector);
    compileResult.getDiagnosticMessages().assertNoErrors();
    compileResult.getDiagnosticMessages().assertNoInfos();
    diagnostics.accept(compileResult.getDiagnosticMessages());
  }

  /*

  class Methods<X extends Throwable> {
    class Inner {
    }
    public static <T extends Throwable> T generic(T a, Methods<T>.Inner b) { return null; }
    public Methods<X>.Inner parameterizedReturn() { return null; }
    public void parameterizedArguments(X a, Methods<X>.Inner b) { }
    public void parametrizedThrows() throws X { }
  }

  */
  private byte[] dumpMethods(Map<String, String> signatures) {

    ClassWriter cw = new ClassWriter(0);
    MethodVisitor mv;
    String signature;

    cw.visit(V1_8, ACC_SUPER, "Methods", "<X:Ljava/lang/Throwable;>Ljava/lang/Object;",
        "java/lang/Object", null);

    cw.visitInnerClass("Methods$Inner", "Methods", "Inner", 0);

    {
      mv = cw.visitMethod(0, "<init>", "()V", null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      signature = signatures.get("generic");
      signature = signature == null ? genericSignature : signature;
      mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "generic",
          "(Ljava/lang/Throwable;LMethods$Inner;)Ljava/lang/Throwable;",
          signature, null);
      mv.visitCode();
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(1, 2);
      mv.visitEnd();
    }
    {
      signature = signatures.get("parameterizedReturn");
      signature = signature == null ? parameterizedReturnSignature : signature;
      mv = cw.visitMethod(ACC_PUBLIC, "parameterizedReturn", "()LMethods$Inner;",
          signature, null);
      mv.visitCode();
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      signature = signatures.get("parameterizedArguments");
      signature = signature == null ? parameterizedArgumentsSignature : signature;
      mv = cw.visitMethod(ACC_PUBLIC, "parameterizedArguments",
          "(Ljava/lang/Throwable;LMethods$Inner;)V", signature, null);
      mv.visitCode();
      mv.visitInsn(RETURN);
      mv.visitMaxs(0, 3);
      mv.visitEnd();
    }
    {
      signature = signatures.get("parametrizedThrows");
      signature = signature == null ? parametrizedThrowsSignature : signature;
      mv = cw.visitMethod(ACC_PUBLIC, "parametrizedThrows", "()V", signature,
          new String[] { "java/lang/Throwable" });
      mv.visitCode();
      mv.visitInsn(RETURN);
      mv.visitMaxs(0, 1);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }

  private byte[] dumpInner() {

    ClassWriter cw = new ClassWriter(0);
    FieldVisitor fv;
    MethodVisitor mv;

    cw.visit(V1_8, ACC_SUPER, "Methods$Inner", null, "java/lang/Object", null);

    cw.visitInnerClass("Methods$Inner", "Methods", "Inner", 0);

    {
      fv = cw.visitField(ACC_FINAL + ACC_SYNTHETIC, "this$0", "LMethods;", null, null);
      fv.visitEnd();
    }
    {
      mv = cw.visitMethod(0, "<init>", "(LMethods;)V", null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitFieldInsn(PUTFIELD, "Methods$Inner", "this$0", "LMethods;");
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(2, 2);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }

  private MethodSubject lookupGeneric(CodeInspector inspector) {
    ClassSubject clazz = inspector.clazz("Methods");
    return clazz.method(
        "java.lang.Throwable", "generic", ImmutableList.of("java.lang.Throwable", "Methods$Inner"));
  }

  private MethodSubject lookupParameterizedReturn(CodeInspector inspector) {
    ClassSubject clazz = inspector.clazz("Methods");
    return clazz.method(
        "Methods$Inner", "parameterizedReturn", ImmutableList.of());
  }

  private MethodSubject lookupParameterizedArguments(CodeInspector inspector) {
    ClassSubject clazz = inspector.clazz("Methods");
    return clazz.method(
        "void", "parameterizedArguments", ImmutableList.of("java.lang.Throwable", "Methods$Inner"));
  }

}
