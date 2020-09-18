// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.repackage.testclasses.repackagewithcollisionstest.first.first;

import com.android.tools.r8.NeverClassInline;

@NeverClassInline
public class Foo {

  public Foo() {
    System.out.println("first.first.Foo");
  }

  @NeverClassInline
  public static class Bar {

    public Bar() {
      System.out.println("first.first.Foo$Bar");
    }
  }
}
