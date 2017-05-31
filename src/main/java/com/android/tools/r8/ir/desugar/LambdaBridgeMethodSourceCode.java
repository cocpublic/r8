// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.MoveType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import java.util.ArrayList;
import java.util.List;

// Source code representing synthesized lambda bridge method.
final class LambdaBridgeMethodSourceCode extends SynthesizedLambdaSourceCode {
  private final DexMethod mainMethod;

  LambdaBridgeMethodSourceCode(LambdaClass lambda, DexMethod mainMethod, DexMethod bridgeMethod) {
    super(lambda, bridgeMethod);
    this.mainMethod = mainMethod;
  }

  @Override
  void prepareInstructions() {
    DexType[] currentParams = proto.parameters.values;
    DexType[] enforcedParams = descriptor().enforcedProto.parameters.values;

    // Prepare call arguments.
    List<MoveType> argMoveTypes = new ArrayList<>();
    List<Integer> argRegisters = new ArrayList<>();

    // Always add a receiver representing 'this' of the lambda class.
    argMoveTypes.add(MoveType.OBJECT);
    argRegisters.add(getReceiverRegister());

    // Prepare arguments.
    for (int i = 0; i < currentParams.length; i++) {
      DexType expectedParamType = enforcedParams[i];
      argMoveTypes.add(MoveType.fromDexType(expectedParamType));
      argRegisters.add(enforceParameterType(
          getParamRegister(i), currentParams[i], expectedParamType));
    }

    // Method call to the main functional interface method.
    add(builder -> builder.addInvoke(Invoke.Type.VIRTUAL,
        this.mainMethod, this.mainMethod.proto, argMoveTypes, argRegisters));

    // Does the method have return value?
    if (proto.returnType == factory().voidType) {
      add(IRBuilder::addReturn);
    } else {
      MoveType moveType = MoveType.fromDexType(proto.returnType);
      int tempValue = nextRegister(moveType);
      add(builder -> builder.addMoveResult(moveType, tempValue));
      add(builder -> builder.addReturn(moveType, tempValue));
    }
  }
}
