// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.mappingcompose;

import static com.android.tools.r8.mappingcompose.ComposeHelpers.doubleToSingleQuote;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.MappingComposer;
import com.android.tools.r8.utils.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ComposeOutlineTest extends TestBase {

  @Parameter() public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  private static final String mappingFoo =
      StringUtils.unixLines(
          "# { id: 'com.android.tools.r8.mapping', version: '2.1' }",
          "outline.Class -> a:",
          "    1:2:int some.inlinee():75:76 -> a",
          "    1:2:int outline():0 -> a",
          "    # { 'id':'com.android.tools.r8.outline' }",
          "outline.Callsite -> x:",
          "    4:4:int outlineCaller(int):23 -> s",
          "    5:5:int foo.bar.baz.outlineCaller(int):98:98 -> s",
          "    5:5:int outlineCaller(int):24 -> s",
          "    27:27:int outlineCaller(int):0:0 -> s",
          "    # { 'id':'com.android.tools.r8.outlineCallsite', 'positions': { '1': 4, '2': 5 },"
              + " 'outline':'La;a()I' }");
  private static final String mappingBar = StringUtils.unixLines("a -> b:");
  private static final String mappingBaz =
      StringUtils.unixLines(
          "b -> c:",
          "    4:5:int a():1:2 -> m",
          "x -> y:",
          "    8:9:int s(int):4:5 -> o",
          "    42:42:int s(int):27:27 -> o");
  private static final String mappingResult =
      StringUtils.unixLines(
          "# {'id':'com.android.tools.r8.mapping','version':'2.1'}",
          "outline.Callsite -> y:",
          "    8:8:int outlineCaller(int):23 -> o",
          "    9:9:int foo.bar.baz.outlineCaller(int):98:98 -> o",
          "    9:9:int outlineCaller(int):24 -> o",
          "    42:42:int outlineCaller(int):0:0 -> o",
          "    #"
              + " {'id':'com.android.tools.r8.outlineCallsite','positions':{'4':8,'5':9},'outline':'Lc;m()I'}",
          "outline.Class -> c:",
          "    4:5:int some.inlinee():75:76 -> m",
          "    4:5:int outline():0 -> m",
          "    # {'id':'com.android.tools.r8.outline'}");

  @Test
  public void testCompose() throws Exception {
    ClassNameMapper mappingForFoo = ClassNameMapper.mapperFromString(mappingFoo);
    ClassNameMapper mappingForBar = ClassNameMapper.mapperFromString(mappingBar);
    ClassNameMapper mappingForBaz = ClassNameMapper.mapperFromString(mappingBaz);
    String composed = MappingComposer.compose(mappingForFoo, mappingForBar, mappingForBaz);
    assertEquals(mappingResult, doubleToSingleQuote(composed));
  }
}
