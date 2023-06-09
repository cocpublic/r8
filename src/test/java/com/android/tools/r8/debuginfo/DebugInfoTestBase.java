// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.debuginfo;

import static org.junit.Assert.assertEquals;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.AndroidAppConsumers;
import com.android.tools.r8.utils.TestDescriptionWatcher;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;

public class DebugInfoTestBase extends TestBase {

  @Rule
  public TestDescriptionWatcher watcher = new TestDescriptionWatcher();

  static AndroidApp compileWithD8(Class... classes) throws CompilationFailedException {
    D8Command.Builder builder = D8Command.builder();
    for (Class clazz : classes) {
      builder.addProgramFiles(ToolHelper.getClassFileForTestClass(clazz));
    }
    AndroidAppConsumers appSink = new AndroidAppConsumers(builder);
    D8.run(builder.setMode(CompilationMode.DEBUG).build());
    return appSink.build();
  }

  public static DebugInfoInspector inspectMethod(
      AndroidApp app, Class type, String returnType, String methodName, String... parameterTypes)
      throws IOException, ExecutionException {
    return new DebugInfoInspector(
        app, type.getCanonicalName(), new MethodSignature(methodName, returnType, parameterTypes));
  }

  protected String runOnArt(AndroidApp app, String main) throws IOException {
    Path out = temp.getRoot().toPath().resolve("out.zip");
    app.writeToZipForTesting(out, OutputMode.DexIndexed);
    return ToolHelper.runArtNoVerificationErrors(ImmutableList.of(out.toString()), main, null);
  }

  protected String runOnJava(Class clazz) throws Exception {
    ProcessResult result = ToolHelper.runJava(clazz);
    if (result.exitCode != 0) {
      System.out.println("Std out:");
      System.out.println(result.stdout);
      System.out.println("Std err:");
      System.out.println(result.stderr);
      assertEquals(0, result.exitCode);
    }
    return result.stdout;
  }
}
