// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner.trivial;

import com.android.tools.r8.NoHorizontalClassMerging;

@NoHorizontalClassMerging
public class ClassWithFinal {
  public String doNothing() {
    return "nothing at all";
  }

  @Override
  protected void finalize() {
    doNothing();
  }
}
