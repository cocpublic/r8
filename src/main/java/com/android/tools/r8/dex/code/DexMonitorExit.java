// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex.code;

import com.android.tools.r8.ir.code.MonitorType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class DexMonitorExit extends DexFormat11x {

  public static final int OPCODE = 0x1e;
  public static final String NAME = "MonitorExit";
  public static final String SMALI_NAME = "monitor-exit";

  DexMonitorExit(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public DexMonitorExit(int register) {
    super(register);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addMonitor(MonitorType.EXIT, AA);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
