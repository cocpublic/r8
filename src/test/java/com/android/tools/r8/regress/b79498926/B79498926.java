// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.regress.b79498926;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.origin.Origin;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class B79498926 extends TestBase {

  @Parameter() public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDexRuntimes().build();
  }

  @Test
  public void test() throws CompilationFailedException, IOException {
    Path outDex = temp.getRoot().toPath().resolve("out.zip");
    Path outOat = temp.getRoot().toPath().resolve("out.oat");
    DexIndexedConsumer consumer = new DexIndexedConsumer.ArchiveConsumer(outDex);
    D8.run(
        D8Command.builder()
            .setMode(CompilationMode.RELEASE)
            .addClassProgramData(B79498926Dump.dump(), Origin.unknown())
            .setProgramConsumer(consumer)
            .setDisableDesugaring(true)
            .build());
    ToolHelper.runDex2Oat(outDex, outOat, parameters.getRuntime().asDex().getVm());
  }
}
