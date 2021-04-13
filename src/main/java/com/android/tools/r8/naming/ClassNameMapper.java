// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.naming.ClassNameMapper.MissingFileAction.MISSING_FILE_IS_ERROR;
import static com.android.tools.r8.utils.DescriptorUtils.descriptorToJavaType;
import static com.android.tools.r8.utils.FunctionUtils.ignoreArgument;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.mappinginformation.MappingInformation;
import com.android.tools.r8.naming.mappinginformation.ScopeReference;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.utils.BiMapContainer;
import com.android.tools.r8.utils.ChainableStringConsumer;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;

public class ClassNameMapper implements ProguardMap {

  public enum MissingFileAction {
    MISSING_FILE_IS_EMPTY_MAP,
    MISSING_FILE_IS_ERROR
  }

  public static class Builder extends ProguardMap.Builder {
    private final Map<String, ClassNamingForNameMapper.Builder> mapping = new HashMap<>();
    private final Map<ScopeReference, List<MappingInformation>> scopedMappingInfo = new HashMap<>();

    private Builder() {

    }

    @Override
    public ClassNamingForNameMapper.Builder classNamingBuilder(
        String renamedName, String originalName, Position position) {
      ClassNamingForNameMapper.Builder classNamingBuilder =
          ClassNamingForNameMapper.builder(renamedName, originalName);
      mapping.put(renamedName, classNamingBuilder);
      return classNamingBuilder;
    }

    @Override
    public void addMappingInformation(
        ScopeReference reference,
        MappingInformation info,
        Consumer<MappingInformation> onProhibitedAddition) {
      List<MappingInformation> additionalMappings =
          scopedMappingInfo.computeIfAbsent(reference, ignoreArgument(ArrayList::new));
      for (MappingInformation existing : additionalMappings) {
        if (!existing.allowOther(info)) {
          onProhibitedAddition.accept(existing);
        }
      }
      additionalMappings.add(info);
    }

    @Override
    public ClassNameMapper build() {
      return new ClassNameMapper(buildClassNameMappings(), buildScopedMappingInfo());
    }

    private ImmutableMap<ScopeReference, ImmutableList<MappingInformation>>
        buildScopedMappingInfo() {
      ImmutableMap.Builder<ScopeReference, ImmutableList<MappingInformation>> builder =
          ImmutableMap.builder();
      scopedMappingInfo.forEach((ref, infos) -> builder.put(ref, ImmutableList.copyOf(infos)));
      return builder.build();
    }

    private ImmutableMap<String, ClassNamingForNameMapper> buildClassNameMappings() {
      // Ensure that all scoped references have at least the identity in the final mapping.
      for (ScopeReference reference : scopedMappingInfo.keySet()) {
        if (!reference.isGlobalScope()) {
          mapping.computeIfAbsent(
              reference.getHolderReference().getTypeName(),
              t -> ClassNamingForNameMapper.builder(t, t));
        }
      }
      ImmutableMap.Builder<String, ClassNamingForNameMapper> builder = ImmutableMap.builder();
      builder.orderEntriesByValue(Comparator.comparing(x -> x.originalName));
      mapping.forEach(
          (renamedName, valueBuilder) -> builder.put(renamedName, valueBuilder.build()));
      return builder.build();
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ClassNameMapper mapperFromFile(Path path) throws IOException {
    return mapperFromFile(path, MISSING_FILE_IS_ERROR);
  }

  public static ClassNameMapper mapperFromFile(Path path, MissingFileAction missingFileAction)
      throws IOException {
    assert missingFileAction == MissingFileAction.MISSING_FILE_IS_EMPTY_MAP
        || missingFileAction == MISSING_FILE_IS_ERROR;
    if (missingFileAction == MissingFileAction.MISSING_FILE_IS_EMPTY_MAP
        && !path.toFile().exists()) {
      return mapperFromString("");
    }
    return mapperFromBufferedReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), null);
  }

  public static ClassNameMapper mapperFromString(String contents) throws IOException {
    return mapperFromBufferedReader(CharSource.wrap(contents).openBufferedStream(), null);
  }

  public static ClassNameMapper mapperFromString(
      String contents, DiagnosticsHandler diagnosticsHandler) throws IOException {
    return mapperFromBufferedReader(
        CharSource.wrap(contents).openBufferedStream(), diagnosticsHandler);
  }

  public static ClassNameMapper mapperFromString(
      String contents, DiagnosticsHandler diagnosticsHandler, boolean allowEmptyMappedRanges)
      throws IOException {
    return mapperFromBufferedReader(
        CharSource.wrap(contents).openBufferedStream(), diagnosticsHandler, allowEmptyMappedRanges);
  }

  private static ClassNameMapper mapperFromBufferedReader(
      BufferedReader reader, DiagnosticsHandler diagnosticsHandler) throws IOException {
    return mapperFromBufferedReader(reader, diagnosticsHandler, false);
  }

  public static ClassNameMapper mapperFromBufferedReader(
      BufferedReader reader, DiagnosticsHandler diagnosticsHandler, boolean allowEmptyMappedRanges)
      throws IOException {
    try (ProguardMapReader proguardReader =
        new ProguardMapReader(
            reader,
            diagnosticsHandler != null ? diagnosticsHandler : new Reporter(),
            allowEmptyMappedRanges)) {
      ClassNameMapper.Builder builder = ClassNameMapper.builder();
      proguardReader.parse(builder);
      return builder.build();
    }
  }

  private final ImmutableMap<String, ClassNamingForNameMapper> classNameMappings;
  private final ImmutableMap<ScopeReference, ImmutableList<MappingInformation>>
      additionalMappingInfo;
  private BiMapContainer<String, String> nameMapping;
  private final Map<Signature, Signature> signatureMap = new HashMap<>();

  private ClassNameMapper(
      ImmutableMap<String, ClassNamingForNameMapper> classNameMappings,
      ImmutableMap<ScopeReference, ImmutableList<MappingInformation>> additionalMappingInfo) {
    this.classNameMappings = classNameMappings;
    this.additionalMappingInfo = additionalMappingInfo;
  }

  public Map<String, ClassNamingForNameMapper> getClassNameMappings() {
    return classNameMappings;
  }

  public List<MappingInformation> getAdditionalMappingInfo(ScopeReference reference) {
    return additionalMappingInfo.getOrDefault(reference, ImmutableList.of());
  }

  private Signature canonicalizeSignature(Signature signature) {
    Signature result = signatureMap.get(signature);
    if (result != null) {
      return result;
    }
    signatureMap.put(signature, signature);
    return signature;
  }

  public MethodSignature getRenamedMethodSignature(DexMethod method) {
    DexType[] parameters = method.proto.parameters.values;
    String[] parameterTypes = new String[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      parameterTypes[i] = deobfuscateType(parameters[i].toDescriptorString());
    }
    String returnType = deobfuscateType(method.proto.returnType.toDescriptorString());

    MethodSignature signature = new MethodSignature(method.name.toString(), returnType,
        parameterTypes);
    return (MethodSignature) canonicalizeSignature(signature);
  }

  public FieldSignature getRenamedFieldSignature(DexField field) {
    String type = deobfuscateType(field.type.toDescriptorString());
    return (FieldSignature) canonicalizeSignature(new FieldSignature(field.name.toString(), type));
  }

  /**
   * Deobfuscate a class name.
   *
   * <p>Returns the deobfuscated name if a mapping was found. Otherwise it returns the passed in
   * name.
   */
  public String deobfuscateClassName(String obfuscatedName) {
    ClassNamingForNameMapper classNaming = classNameMappings.get(obfuscatedName);
    if (classNaming == null) {
      return obfuscatedName;
    }
    return classNaming.originalName;
  }

  private String deobfuscateType(String asString) {
    return descriptorToJavaType(asString, this);
  }

  @Override
  public boolean hasMapping(DexType type) {
    String decoded = descriptorToJavaType(type.descriptor.toString());
    return classNameMappings.containsKey(decoded);
  }

  @Override
  public ClassNamingForNameMapper getClassNaming(DexType type) {
    String decoded = descriptorToJavaType(type.descriptor.toString());
    return classNameMappings.get(decoded);
  }

  public ClassNamingForNameMapper getClassNaming(String obfuscatedName) {
    return classNameMappings.get(obfuscatedName);
  }

  public boolean isEmpty() {
    return classNameMappings.isEmpty();
  }

  public ClassNameMapper sorted() {
    ImmutableMap.Builder<String, ClassNamingForNameMapper> builder = ImmutableMap.builder();
    builder.orderEntriesByValue(Comparator.comparing(x -> x.originalName));
    classNameMappings.forEach(builder::put);
    return new ClassNameMapper(builder.build(), additionalMappingInfo);
  }

  public boolean verifyIsSorted() {
    Iterator<Entry<String, ClassNamingForNameMapper>> iterator =
        getClassNameMappings().entrySet().iterator();
    Iterator<Entry<String, ClassNamingForNameMapper>> sortedIterator =
        sorted().getClassNameMappings().entrySet().iterator();
    while (iterator.hasNext()) {
      Entry<String, ClassNamingForNameMapper> entry = iterator.next();
      Entry<String, ClassNamingForNameMapper> otherEntry = sortedIterator.next();
      assert entry.getKey().equals(otherEntry.getKey());
      assert entry.getValue() == otherEntry.getValue();
    }
    return true;
  }

  public void write(ChainableStringConsumer consumer) {
    // Classes should be sorted by their original name such that the generated Proguard map is
    // deterministic (and easy to navigate manually).
    assert verifyIsSorted();
    for (ClassNamingForNameMapper naming : getClassNameMappings().values()) {
      List<MappingInformation> additionalMappingInfo =
          getAdditionalMappingInfo(
              ScopeReference.fromClassReference(Reference.classFromTypeName(naming.renamedName)));
      naming.write(consumer, additionalMappingInfo);
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    write(ChainableStringConsumer.wrap(builder::append));
    return builder.toString();
  }

  public BiMapContainer<String, String> getObfuscatedToOriginalMapping() {
    if (nameMapping == null) {
      ImmutableBiMap.Builder<String, String> builder = ImmutableBiMap.builder();
      for (String name : classNameMappings.keySet()) {
        builder.put(name, classNameMappings.get(name).originalName);
      }
      BiMap<String, String> classNameMappings = builder.build();
      nameMapping = new BiMapContainer<>(classNameMappings, classNameMappings.inverse());
    }
    return nameMapping;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ClassNameMapper
        && classNameMappings.equals(((ClassNameMapper) o).classNameMappings);
  }

  @Override
  public int hashCode() {
    return 31 * classNameMappings.hashCode();
  }

  public String originalNameOf(IndexedDexItem item) {
    if (item instanceof DexField) {
      return lookupName(getRenamedFieldSignature((DexField) item), ((DexField) item).holder);
    } else if (item instanceof DexMethod) {
      return lookupName(getRenamedMethodSignature((DexMethod) item), ((DexMethod) item).holder);
    } else if (item instanceof DexType) {
      return descriptorToJavaType(((DexType) item).toDescriptorString(), this);
    } else {
      return item.toString();
    }
  }

  private String lookupName(Signature signature, DexType clazz) {
    String decoded = descriptorToJavaType(clazz.descriptor.toString());
    ClassNamingForNameMapper classNaming = getClassNaming(decoded);
    if (classNaming == null) {
      return decoded + " " + signature.toString();
    }
    MemberNaming memberNaming = classNaming.lookup(signature);
    if (memberNaming == null) {
      return classNaming.originalName + " " + signature.toString();
    }
    return classNaming.originalName + " " + memberNaming.signature.toString();
  }

  public MethodSignature originalSignatureOf(DexMethod method) {
    String decoded = descriptorToJavaType(method.holder.descriptor.toString());
    MethodSignature memberSignature = getRenamedMethodSignature(method);
    ClassNaming classNaming = getClassNaming(decoded);
    if (classNaming == null) {
      return memberSignature;
    }
    MemberNaming memberNaming = classNaming.lookup(memberSignature);
    if (memberNaming == null) {
      return memberSignature;
    }
    return (MethodSignature) memberNaming.signature;
  }

  public FieldSignature originalSignatureOf(DexField field) {
    String decoded = descriptorToJavaType(field.holder.descriptor.toString());
    FieldSignature memberSignature = getRenamedFieldSignature(field);
    ClassNaming classNaming = getClassNaming(decoded);
    if (classNaming == null) {
      return memberSignature;
    }
    MemberNaming memberNaming = classNaming.lookup(memberSignature);
    if (memberNaming == null) {
      return memberSignature;
    }
    return (FieldSignature) memberNaming.signature;
  }

  public String originalNameOf(DexType clazz) {
    return deobfuscateType(clazz.descriptor.toString());
  }
}
