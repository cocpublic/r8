// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import static com.android.tools.r8.kotlin.KotlinMetadataUtils.consume;
import static com.android.tools.r8.kotlin.KotlinMetadataUtils.rewriteList;
import static com.android.tools.r8.utils.FunctionUtils.forEachApply;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.shaking.EnqueuerMetadataTraceable;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.Consumer;
import kotlinx.metadata.KmType;
import kotlinx.metadata.KmTypeParameter;
import kotlinx.metadata.KmVariance;
import kotlinx.metadata.jvm.JvmExtensionsKt;

// Provides access to Kotlin information about a type-parameter.
public class KotlinTypeParameterInfo implements EnqueuerMetadataTraceable {

  private static final List<KotlinTypeParameterInfo> EMPTY_TYPE_PARAMETERS = ImmutableList.of();
  private static final List<KotlinTypeInfo> EMPTY_UPPER_BOUNDS = ImmutableList.of();

  private final int flags;
  private final int id;
  private final String name;
  private final KmVariance variance;
  private final List<KotlinTypeInfo> originalUpperBounds;
  private final List<KotlinAnnotationInfo> annotations;

  private KotlinTypeParameterInfo(
      int flags,
      int id,
      String name,
      KmVariance variance,
      List<KotlinTypeInfo> originalUpperBounds,
      List<KotlinAnnotationInfo> annotations) {
    this.flags = flags;
    this.id = id;
    this.name = name;
    this.variance = variance;
    this.originalUpperBounds = originalUpperBounds;
    this.annotations = annotations;
  }

  private static KotlinTypeParameterInfo create(
      KmTypeParameter kmTypeParameter, DexItemFactory factory, Reporter reporter) {
    return new KotlinTypeParameterInfo(
        kmTypeParameter.getFlags(),
        kmTypeParameter.getId(),
        kmTypeParameter.getName(),
        kmTypeParameter.getVariance(),
        getUpperBounds(kmTypeParameter.getUpperBounds(), factory, reporter),
        KotlinAnnotationInfo.create(JvmExtensionsKt.getAnnotations(kmTypeParameter), factory));
  }

  static List<KotlinTypeParameterInfo> create(
      List<KmTypeParameter> kmTypeParameters, DexItemFactory factory, Reporter reporter) {
    if (kmTypeParameters.isEmpty()) {
      return EMPTY_TYPE_PARAMETERS;
    }
    ImmutableList.Builder<KotlinTypeParameterInfo> builder = ImmutableList.builder();
    for (KmTypeParameter kmTypeParameter : kmTypeParameters) {
      builder.add(create(kmTypeParameter, factory, reporter));
    }
    return builder.build();
  }

  private static List<KotlinTypeInfo> getUpperBounds(
      List<KmType> upperBounds, DexItemFactory factory, Reporter reporter) {
    if (upperBounds.isEmpty()) {
      return EMPTY_UPPER_BOUNDS;
    }
    ImmutableList.Builder<KotlinTypeInfo> builder = ImmutableList.builder();
    for (KmType upperBound : upperBounds) {
      builder.add(KotlinTypeInfo.create(upperBound, factory, reporter));
    }
    return builder.build();
  }

  boolean rewrite(Consumer<KmTypeParameter> consumer, AppView<?> appView) {
    KmTypeParameter kmTypeParameter =
        consume(new KmTypeParameter(flags, name, id, variance), consumer);
    boolean rewritten =
        rewriteList(
            appView,
            originalUpperBounds,
            kmTypeParameter.getUpperBounds(),
            KotlinTypeInfo::rewrite);
    rewritten |=
        rewriteList(
            appView,
            annotations,
            JvmExtensionsKt.getAnnotations(kmTypeParameter),
            KotlinAnnotationInfo::rewrite);
    return rewritten;
  }

  @Override
  public void trace(DexDefinitionSupplier definitionSupplier) {
    forEachApply(originalUpperBounds, upperBound -> upperBound::trace, definitionSupplier);
    forEachApply(annotations, annotation -> annotation::trace, definitionSupplier);
  }
}
