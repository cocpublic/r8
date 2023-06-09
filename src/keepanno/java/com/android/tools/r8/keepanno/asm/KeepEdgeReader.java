// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno.asm;

import com.android.tools.r8.keepanno.ast.AccessVisibility;
import com.android.tools.r8.keepanno.ast.AnnotationConstants;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Binding;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Condition;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Edge;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.FieldAccess;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.ForApi;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Item;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Kind;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.MemberAccess;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.MethodAccess;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Option;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.Target;
import com.android.tools.r8.keepanno.ast.AnnotationConstants.UsesReflection;
import com.android.tools.r8.keepanno.ast.KeepBindings;
import com.android.tools.r8.keepanno.ast.KeepClassReference;
import com.android.tools.r8.keepanno.ast.KeepCondition;
import com.android.tools.r8.keepanno.ast.KeepConsequences;
import com.android.tools.r8.keepanno.ast.KeepEdge;
import com.android.tools.r8.keepanno.ast.KeepEdgeException;
import com.android.tools.r8.keepanno.ast.KeepEdgeMetaInfo;
import com.android.tools.r8.keepanno.ast.KeepExtendsPattern;
import com.android.tools.r8.keepanno.ast.KeepFieldAccessPattern;
import com.android.tools.r8.keepanno.ast.KeepFieldNamePattern;
import com.android.tools.r8.keepanno.ast.KeepFieldPattern;
import com.android.tools.r8.keepanno.ast.KeepFieldTypePattern;
import com.android.tools.r8.keepanno.ast.KeepItemKind;
import com.android.tools.r8.keepanno.ast.KeepItemPattern;
import com.android.tools.r8.keepanno.ast.KeepItemReference;
import com.android.tools.r8.keepanno.ast.KeepMemberAccessPattern;
import com.android.tools.r8.keepanno.ast.KeepMemberPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodAccessPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodNamePattern;
import com.android.tools.r8.keepanno.ast.KeepMethodParametersPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodReturnTypePattern;
import com.android.tools.r8.keepanno.ast.KeepOptions;
import com.android.tools.r8.keepanno.ast.KeepOptions.KeepOption;
import com.android.tools.r8.keepanno.ast.KeepPreconditions;
import com.android.tools.r8.keepanno.ast.KeepQualifiedClassNamePattern;
import com.android.tools.r8.keepanno.ast.KeepTarget;
import com.android.tools.r8.keepanno.ast.KeepTypePattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class KeepEdgeReader implements Opcodes {

  public static int ASM_VERSION = ASM9;

  public static Set<KeepEdge> readKeepEdges(byte[] classFileBytes) {
    ClassReader reader = new ClassReader(classFileBytes);
    Set<KeepEdge> edges = new HashSet<>();
    reader.accept(new KeepEdgeClassVisitor(edges::add), ClassReader.SKIP_CODE);
    return edges;
  }

  private static class KeepEdgeClassVisitor extends ClassVisitor {
    private final Parent<KeepEdge> parent;
    private String className;

    KeepEdgeClassVisitor(Parent<KeepEdge> parent) {
      super(ASM_VERSION);
      this.parent = parent;
    }

    private static String binaryNameToTypeName(String binaryName) {
      return binaryName.replace('/', '.');
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, signature, superName, interfaces);
      this.className = binaryNameToTypeName(name);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      // Skip any visible annotations as @KeepEdge is not runtime visible.
      if (visible) {
        return null;
      }
      if (descriptor.equals(Edge.DESCRIPTOR)) {
        return new KeepEdgeVisitor(parent, this::setContext);
      }
      if (descriptor.equals(AnnotationConstants.UsesReflection.DESCRIPTOR)) {
        KeepItemPattern classItem =
            KeepItemPattern.builder()
                .setClassPattern(KeepQualifiedClassNamePattern.exact(className))
                .build();
        return new UsesReflectionVisitor(parent, this::setContext, classItem);
      }
      if (descriptor.equals(AnnotationConstants.ForApi.DESCRIPTOR)) {
        return new ForApiClassVisitor(parent, this::setContext, className);
      }
      return null;
    }

    private void setContext(KeepEdgeMetaInfo.Builder builder) {
      builder.setContextFromClassDescriptor(KeepEdgeReaderUtils.javaTypeToDescriptor(className));
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      return new KeepEdgeMethodVisitor(parent, className, name, descriptor);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      return new KeepEdgeFieldVisitor(parent, className, name, descriptor);
    }
  }

  private static class KeepEdgeMethodVisitor extends MethodVisitor {
    private final Parent<KeepEdge> parent;
    private final String className;
    private final String methodName;
    private final String methodDescriptor;

    KeepEdgeMethodVisitor(
        Parent<KeepEdge> parent, String className, String methodName, String methodDescriptor) {
      super(ASM_VERSION);
      this.parent = parent;
      this.className = className;
      this.methodName = methodName;
      this.methodDescriptor = methodDescriptor;
    }

    private KeepItemPattern createItemContext() {
      String returnTypeDescriptor = Type.getReturnType(methodDescriptor).getDescriptor();
      Type[] argumentTypes = Type.getArgumentTypes(methodDescriptor);
      KeepMethodParametersPattern.Builder builder = KeepMethodParametersPattern.builder();
      for (Type type : argumentTypes) {
        builder.addParameterTypePattern(KeepTypePattern.fromDescriptor(type.getDescriptor()));
      }
      KeepMethodReturnTypePattern returnTypePattern =
          "V".equals(returnTypeDescriptor)
              ? KeepMethodReturnTypePattern.voidType()
              : KeepMethodReturnTypePattern.fromType(
                  KeepTypePattern.fromDescriptor(returnTypeDescriptor));
      return KeepItemPattern.builder()
          .setClassPattern(KeepQualifiedClassNamePattern.exact(className))
          .setMemberPattern(
              KeepMethodPattern.builder()
                  .setNamePattern(KeepMethodNamePattern.exact(methodName))
                  .setReturnTypePattern(returnTypePattern)
                  .setParametersPattern(builder.build())
                  .build())
          .build();
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      // Skip any visible annotations as @KeepEdge is not runtime visible.
      if (visible) {
        return null;
      }
      if (descriptor.equals(Edge.DESCRIPTOR)) {
        return new KeepEdgeVisitor(parent, this::setContext);
      }
      if (descriptor.equals(AnnotationConstants.UsesReflection.DESCRIPTOR)) {
        return new UsesReflectionVisitor(parent, this::setContext, createItemContext());
      }
      if (descriptor.equals(AnnotationConstants.ForApi.DESCRIPTOR)) {
        return new ForApiMemberVisitor(parent, this::setContext, createItemContext());
      }
      return null;
    }

    private void setContext(KeepEdgeMetaInfo.Builder builder) {
      builder.setContextFromMethodDescriptor(
          KeepEdgeReaderUtils.javaTypeToDescriptor(className), methodName, methodDescriptor);
    }
  }

  private static class KeepEdgeFieldVisitor extends FieldVisitor {
    private final Parent<KeepEdge> parent;
    private final String className;
    private final String fieldName;
    private final String fieldDescriptor;

    KeepEdgeFieldVisitor(
        Parent<KeepEdge> parent, String className, String fieldName, String fieldDescriptor) {
      super(ASM_VERSION);
      this.parent = parent;
      this.className = className;
      this.fieldName = fieldName;
      this.fieldDescriptor = fieldDescriptor;
    }

    private KeepItemPattern createItemContext() {
      KeepFieldTypePattern typePattern =
          KeepFieldTypePattern.fromType(KeepTypePattern.fromDescriptor(fieldDescriptor));
      return KeepItemPattern.builder()
          .setClassPattern(KeepQualifiedClassNamePattern.exact(className))
          .setMemberPattern(
              KeepFieldPattern.builder()
                  .setNamePattern(KeepFieldNamePattern.exact(fieldName))
                  .setTypePattern(typePattern)
                  .build())
          .build();
    }

    private void setContext(KeepEdgeMetaInfo.Builder builder) {
      builder.setContextFromFieldDescriptor(
          KeepEdgeReaderUtils.javaTypeToDescriptor(className), fieldName, fieldDescriptor);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      // Skip any visible annotations as @KeepEdge is not runtime visible.
      if (visible) {
        return null;
      }
      if (descriptor.equals(Edge.DESCRIPTOR)) {
        return new KeepEdgeVisitor(parent, this::setContext);
      }
      if (descriptor.equals(AnnotationConstants.UsesReflection.DESCRIPTOR)) {
        return new UsesReflectionVisitor(parent, this::setContext, createItemContext());
      }
      if (descriptor.equals(AnnotationConstants.ForApi.DESCRIPTOR)) {
        return new ForApiMemberVisitor(parent, this::setContext, createItemContext());
      }
      return null;
    }
  }

  // Interface for providing AST result(s) for a sub-tree back up to its parent.
  private interface Parent<T> {
    void accept(T result);
  }

  private abstract static class AnnotationVisitorBase extends AnnotationVisitor {

    AnnotationVisitorBase() {
      super(ASM_VERSION);
    }

    public abstract String getAnnotationName();

    private String errorMessagePrefix() {
      return " @" + getAnnotationName() + ": ";
    }

    @Override
    public void visit(String name, Object value) {
      throw new KeepEdgeException(
          "Unexpected value in" + errorMessagePrefix() + name + " = " + value);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
      throw new KeepEdgeException("Unexpected annotation in" + errorMessagePrefix() + name);
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
      throw new KeepEdgeException("Unexpected enum in" + errorMessagePrefix() + name);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      throw new KeepEdgeException("Unexpected array in" + errorMessagePrefix() + name);
    }
  }

  private static class KeepEdgeVisitor extends AnnotationVisitorBase {
    private final Parent<KeepEdge> parent;
    private final KeepEdge.Builder builder = KeepEdge.builder();
    private final KeepEdgeMetaInfo.Builder metaInfoBuilder = KeepEdgeMetaInfo.builder();

    KeepEdgeVisitor(Parent<KeepEdge> parent, Consumer<KeepEdgeMetaInfo.Builder> addContext) {
      this.parent = parent;
      addContext.accept(metaInfoBuilder);
    }

    @Override
    public String getAnnotationName() {
      return "KeepEdge";
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Edge.description) && value instanceof String) {
        metaInfoBuilder.setDescription((String) value);
        return;
      }
      super.visit(name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      if (name.equals(Edge.bindings)) {
        return new KeepBindingsVisitor(getAnnotationName(), builder::setBindings);
      }
      if (name.equals(Edge.preconditions)) {
        return new KeepPreconditionsVisitor(getAnnotationName(), builder::setPreconditions);
      }
      if (name.equals(Edge.consequences)) {
        return new KeepConsequencesVisitor(getAnnotationName(), builder::setConsequences);
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      parent.accept(builder.setMetaInfo(metaInfoBuilder.build()).build());
    }
  }

  /**
   * Parsing of @KeepForApi on a class context.
   *
   * <p>When used on a class context the annotation allows the member related content of a normal
   * item. This parser extends the base item visitor and throws an error if any class specific
   * properties are encountered.
   */
  private static class ForApiClassVisitor extends KeepItemVisitorBase {
    private final String className;
    private final Parent<KeepEdge> parent;
    private final KeepEdge.Builder builder = KeepEdge.builder();
    private final KeepConsequences.Builder consequences = KeepConsequences.builder();
    private final KeepEdgeMetaInfo.Builder metaInfoBuilder = KeepEdgeMetaInfo.builder();

    ForApiClassVisitor(
        Parent<KeepEdge> parent, Consumer<KeepEdgeMetaInfo.Builder> addContext, String className) {
      this.className = className;
      this.parent = parent;
      addContext.accept(metaInfoBuilder);
      // The class context/holder is the annotated class.
      visit(Item.className, className);
      // The default kind is to target the class and its members.
      visitEnum(null, Kind.DESCRIPTOR, Kind.CLASS_AND_MEMBERS);
    }

    @Override
    public String getAnnotationName() {
      return ForApi.CLASS.getSimpleName();
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Edge.description) && value instanceof String) {
        metaInfoBuilder.setDescription((String) value);
        return;
      }
      super.visit(name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      if (name.equals(ForApi.additionalTargets)) {
        return new KeepConsequencesVisitor(
            getAnnotationName(),
            additionalConsequences -> {
              additionalConsequences.forEachTarget(consequences::addTarget);
            });
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      if (!getKind().equals(KeepItemKind.ONLY_CLASS) && isDefaultMemberDeclaration()) {
        // If no member declarations have been made, set public & protected as the default.
        AnnotationVisitor v = visitArray(Item.memberAccess);
        v.visitEnum(null, MemberAccess.DESCRIPTOR, MemberAccess.PUBLIC);
        v.visitEnum(null, MemberAccess.DESCRIPTOR, MemberAccess.PROTECTED);
      }
      super.visitEnd();
      KeepItemReference item = getItemReference();
      if (item.isBindingReference()) {
        throw new KeepEdgeException("@KeepForApi cannot reference bindings");
      }
      KeepItemPattern itemPattern = item.asItemPattern();
      String descriptor = AnnotationConstants.getDescriptorFromClassTypeName(className);
      String itemDescriptor =
          itemPattern.getClassReference().asClassNamePattern().getExactDescriptor();
      if (!descriptor.equals(itemDescriptor)) {
        throw new KeepEdgeException("@KeepForApi must reference its class context " + className);
      }
      if (itemPattern.getKind().equals(KeepItemKind.ONLY_MEMBERS)) {
        throw new KeepEdgeException("@KeepForApi kind must include its class");
      }
      if (!itemPattern.getExtendsPattern().isAny()) {
        throw new KeepEdgeException("@KeepForApi cannot define an 'extends' pattern.");
      }
      consequences.addTarget(KeepTarget.builder().setItemPattern(itemPattern).build());
      parent.accept(
          builder
              .setMetaInfo(metaInfoBuilder.build())
              .setConsequences(consequences.build())
              .build());
    }
  }

  /**
   * Parsing of @KeepForApi on a member context.
   *
   * <p>When used on a member context the annotation does not allow member related patterns.
   */
  private static class ForApiMemberVisitor extends AnnotationVisitorBase {
    private final Parent<KeepEdge> parent;
    private final KeepEdge.Builder builder = KeepEdge.builder();
    private final KeepEdgeMetaInfo.Builder metaInfoBuilder = KeepEdgeMetaInfo.builder();

    private final KeepConsequences.Builder consequences = KeepConsequences.builder();

    ForApiMemberVisitor(
        Parent<KeepEdge> parent,
        Consumer<KeepEdgeMetaInfo.Builder> addContext,
        KeepItemPattern context) {
      this.parent = parent;
      addContext.accept(metaInfoBuilder);
      consequences.addTarget(
          KeepTarget.builder()
              .setItemPattern(
                  KeepItemPattern.builder()
                      .copyFrom(context)
                      .setKind(KeepItemKind.CLASS_AND_MEMBERS)
                      .build())
              .build());
    }

    @Override
    public String getAnnotationName() {
      return ForApi.CLASS.getSimpleName();
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Edge.description) && value instanceof String) {
        metaInfoBuilder.setDescription((String) value);
        return;
      }
      super.visit(name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      if (name.equals(ForApi.additionalTargets)) {
        return new KeepConsequencesVisitor(
            getAnnotationName(),
            additionalConsequences -> {
              additionalConsequences.forEachTarget(consequences::addTarget);
            });
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      parent.accept(
          builder
              .setMetaInfo(metaInfoBuilder.build())
              .setConsequences(consequences.build())
              .build());
    }
  }

  private static class UsesReflectionVisitor extends AnnotationVisitorBase {
    private final Parent<KeepEdge> parent;
    private final KeepEdge.Builder builder = KeepEdge.builder();
    private final KeepPreconditions.Builder preconditions = KeepPreconditions.builder();
    private final KeepEdgeMetaInfo.Builder metaInfoBuilder = KeepEdgeMetaInfo.builder();

    UsesReflectionVisitor(
        Parent<KeepEdge> parent,
        Consumer<KeepEdgeMetaInfo.Builder> addContext,
        KeepItemPattern context) {
      this.parent = parent;
      preconditions.addCondition(KeepCondition.builder().setItemPattern(context).build());
      addContext.accept(metaInfoBuilder);
    }

    @Override
    public String getAnnotationName() {
      return UsesReflection.CLASS.getSimpleName();
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Edge.description) && value instanceof String) {
        metaInfoBuilder.setDescription((String) value);
        return;
      }
      super.visit(name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      if (name.equals(AnnotationConstants.UsesReflection.value)) {
        return new KeepConsequencesVisitor(getAnnotationName(), builder::setConsequences);
      }
      if (name.equals(AnnotationConstants.UsesReflection.additionalPreconditions)) {
        return new KeepPreconditionsVisitor(
            getAnnotationName(),
            additionalPreconditions -> {
              additionalPreconditions.forEach(preconditions::addCondition);
            });
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      parent.accept(
          builder
              .setMetaInfo(metaInfoBuilder.build())
              .setPreconditions(preconditions.build())
              .build());
    }
  }

  private static class KeepBindingsVisitor extends AnnotationVisitorBase {
    private final String annotationName;
    private final Parent<KeepBindings> parent;
    private final KeepBindings.Builder builder = KeepBindings.builder();

    public KeepBindingsVisitor(String annotationName, Parent<KeepBindings> parent) {
      this.annotationName = annotationName;
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
      if (descriptor.equals(AnnotationConstants.Binding.DESCRIPTOR)) {
        return new KeepBindingVisitor(builder);
      }
      return super.visitAnnotation(name, descriptor);
    }

    @Override
    public void visitEnd() {
      parent.accept(builder.build());
    }
  }

  private static class KeepPreconditionsVisitor extends AnnotationVisitorBase {
    private final String annotationName;
    private final Parent<KeepPreconditions> parent;
    private final KeepPreconditions.Builder builder = KeepPreconditions.builder();

    public KeepPreconditionsVisitor(String annotationName, Parent<KeepPreconditions> parent) {
      this.annotationName = annotationName;
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
      if (descriptor.equals(Condition.DESCRIPTOR)) {
        return new KeepConditionVisitor(builder::addCondition);
      }
      return super.visitAnnotation(name, descriptor);
    }

    @Override
    public void visitEnd() {
      parent.accept(builder.build());
    }
  }

  private static class KeepConsequencesVisitor extends AnnotationVisitorBase {
    private final String annotationName;
    private final Parent<KeepConsequences> parent;
    private final KeepConsequences.Builder builder = KeepConsequences.builder();

    public KeepConsequencesVisitor(String annotationName, Parent<KeepConsequences> parent) {
      this.annotationName = annotationName;
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
      if (descriptor.equals(Target.DESCRIPTOR)) {
        return KeepTargetVisitor.create(builder::addTarget);
      }
      return super.visitAnnotation(name, descriptor);
    }

    @Override
    public void visitEnd() {
      parent.accept(builder.build());
    }
  }

  abstract static class Declaration<T> {
    abstract String kind();

    abstract boolean isDefault();

    abstract T getValue();

    boolean tryParse(String name, Object value) {
      return false;
    }

    AnnotationVisitor tryParseArray(String name, Consumer<T> onValue) {
      return null;
    }
  }

  private abstract static class SingleDeclaration<T> extends Declaration<T> {
    private String declarationName = null;
    private T declarationValue = null;
    private AnnotationVisitor declarationVisitor = null;

    abstract T getDefaultValue();

    abstract T parse(String name, Object value);

    AnnotationVisitor parseArray(String name, Consumer<T> setValue) {
      return null;
    }

    @Override
    boolean isDefault() {
      return !hasDeclaration();
    }

    private boolean hasDeclaration() {
      return declarationValue != null || declarationVisitor != null;
    }

    private void error(String name) {
      throw new KeepEdgeException(
          "Multiple declarations defining "
              + kind()
              + ": '"
              + declarationName
              + "' and '"
              + name
              + "'");
    }

    @Override
    public final T getValue() {
      return declarationValue == null ? getDefaultValue() : declarationValue;
    }

    @Override
    final boolean tryParse(String name, Object value) {
      T result = parse(name, value);
      if (result != null) {
        if (hasDeclaration()) {
          error(name);
        }
        declarationName = name;
        declarationValue = result;
        return true;
      }
      return false;
    }

    @Override
    AnnotationVisitor tryParseArray(String name, Consumer<T> setValue) {
      AnnotationVisitor visitor = parseArray(name, setValue.andThen(v -> declarationValue = v));
      if (visitor != null) {
        if (hasDeclaration()) {
          error(name);
        }
        declarationName = name;
        declarationVisitor = visitor;
        return visitor;
      }
      return null;
    }
  }

  private static class ClassDeclaration extends SingleDeclaration<KeepClassReference> {
    @Override
    String kind() {
      return "class";
    }

    KeepClassReference wrap(KeepQualifiedClassNamePattern namePattern) {
      return KeepClassReference.fromClassNamePattern(namePattern);
    }

    @Override
    KeepClassReference getDefaultValue() {
      return wrap(KeepQualifiedClassNamePattern.any());
    }

    @Override
    KeepClassReference parse(String name, Object value) {
      if (name.equals(Item.classFromBinding) && value instanceof String) {
        return KeepClassReference.fromBindingReference((String) value);
      }
      if (name.equals(Item.classConstant) && value instanceof Type) {
        return wrap(KeepQualifiedClassNamePattern.exact(((Type) value).getClassName()));
      }
      if (name.equals(Item.className) && value instanceof String) {
        return wrap(KeepQualifiedClassNamePattern.exact(((String) value)));
      }
      return null;
    }
  }

  private static class ExtendsDeclaration extends SingleDeclaration<KeepExtendsPattern> {

    @Override
    String kind() {
      return "extends";
    }

    @Override
    KeepExtendsPattern getDefaultValue() {
      return KeepExtendsPattern.any();
    }

    @Override
    KeepExtendsPattern parse(String name, Object value) {
      if (name.equals(Item.extendsClassConstant) && value instanceof Type) {
        return KeepExtendsPattern.builder()
            .classPattern(KeepQualifiedClassNamePattern.exact(((Type) value).getClassName()))
            .build();
      }
      if (name.equals(Item.extendsClassName) && value instanceof String) {
        return KeepExtendsPattern.builder()
            .classPattern(KeepQualifiedClassNamePattern.exact(((String) value)))
            .build();
      }
      return null;
    }
  }

  private static class MethodDeclaration extends Declaration<KeepMethodPattern> {
    private final String annotationName;
    private KeepMethodAccessPattern.Builder accessBuilder = null;
    private KeepMethodPattern.Builder builder = null;

    private MethodDeclaration(String annotationName) {
      this.annotationName = annotationName;
    }

    private KeepMethodPattern.Builder getBuilder() {
      if (builder == null) {
        builder = KeepMethodPattern.builder();
      }
      return builder;
    }

    @Override
    String kind() {
      return "method";
    }

    @Override
    boolean isDefault() {
      return accessBuilder == null && builder == null;
    }

    @Override
    KeepMethodPattern getValue() {
      if (accessBuilder != null) {
        getBuilder().setAccessPattern(accessBuilder.build());
      }
      return builder != null ? builder.build() : null;
    }

    @Override
    boolean tryParse(String name, Object value) {
      if (name.equals(Item.methodName) && value instanceof String) {
        String methodName = (String) value;
        if (!Item.methodNameDefaultValue.equals(methodName)) {
          getBuilder().setNamePattern(KeepMethodNamePattern.exact(methodName));
        }
        return true;
      }
      if (name.equals(Item.methodReturnType) && value instanceof String) {
        String returnType = (String) value;
        if (!Item.methodReturnTypeDefaultValue.equals(returnType)) {
          getBuilder()
              .setReturnTypePattern(KeepEdgeReaderUtils.methodReturnTypeFromString(returnType));
        }
        return true;
      }
      return false;
    }

    @Override
    AnnotationVisitor tryParseArray(String name, Consumer<KeepMethodPattern> ignored) {
      if (name.equals(Item.methodAccess)) {
        accessBuilder = KeepMethodAccessPattern.builder();
        return new MethodAccessVisitor(annotationName, accessBuilder);
      }
      if (name.equals(Item.methodParameters)) {
        return new StringArrayVisitor(
            annotationName,
            params -> {
              if (Arrays.asList(Item.methodParametersDefaultValue).equals(params)) {
                return;
              }
              KeepMethodParametersPattern.Builder builder = KeepMethodParametersPattern.builder();
              for (String param : params) {
                builder.addParameterTypePattern(KeepEdgeReaderUtils.typePatternFromString(param));
              }
              KeepMethodParametersPattern result = builder.build();
              getBuilder().setParametersPattern(result);
            });
      }
      return null;
    }
  }

  private static class FieldDeclaration extends Declaration<KeepFieldPattern> {
    private final String annotationName;
    private KeepFieldAccessPattern.Builder accessBuilder = null;
    private KeepFieldPattern.Builder builder = null;

    public FieldDeclaration(String annotationName) {
      this.annotationName = annotationName;
    }

    private KeepFieldPattern.Builder getBuilder() {
      if (builder == null) {
        builder = KeepFieldPattern.builder();
      }
      return builder;
    }

    @Override
    String kind() {
      return "field";
    }

    @Override
    boolean isDefault() {
      return accessBuilder == null && builder == null;
    }

    @Override
    KeepFieldPattern getValue() {
      if (accessBuilder != null) {
        getBuilder().setAccessPattern(accessBuilder.build());
      }
      return builder != null ? builder.build() : null;
    }

    @Override
    boolean tryParse(String name, Object value) {
      if (name.equals(Item.fieldName) && value instanceof String) {
        String fieldName = (String) value;
        if (!Item.fieldNameDefaultValue.equals(fieldName)) {
          getBuilder().setNamePattern(KeepFieldNamePattern.exact(fieldName));
        }
        return true;
      }
      if (name.equals(Item.fieldType) && value instanceof String) {
        String fieldType = (String) value;
        if (!Item.fieldTypeDefaultValue.equals(fieldType)) {
          getBuilder()
              .setTypePattern(
                  KeepFieldTypePattern.fromType(
                      KeepEdgeReaderUtils.typePatternFromString(fieldType)));
        }
        return true;
      }
      return false;
    }

    @Override
    AnnotationVisitor tryParseArray(String name, Consumer<KeepFieldPattern> onValue) {
      if (name.equals(Item.fieldAccess)) {
        accessBuilder = KeepFieldAccessPattern.builder();
        return new FieldAccessVisitor(annotationName, accessBuilder);
      }
      return super.tryParseArray(name, onValue);
    }
  }

  private static class MemberDeclaration extends Declaration<KeepMemberPattern> {
    private final String annotationName;
    private KeepMemberAccessPattern.Builder accessBuilder = null;
    private final MethodDeclaration methodDeclaration;
    private final FieldDeclaration fieldDeclaration;

    MemberDeclaration(String annotationName) {
      this.annotationName = annotationName;
      methodDeclaration = new MethodDeclaration(annotationName);
      fieldDeclaration = new FieldDeclaration(annotationName);
    }

    @Override
    String kind() {
      return "member";
    }

    @Override
    public boolean isDefault() {
      return accessBuilder == null && methodDeclaration.isDefault() && fieldDeclaration.isDefault();
    }

    @Override
    public KeepMemberPattern getValue() {
      KeepMethodPattern method = methodDeclaration.getValue();
      KeepFieldPattern field = fieldDeclaration.getValue();
      if (accessBuilder != null) {
        if (method != null || field != null) {
          throw new KeepEdgeException(
              "Cannot define common member access as well as field or method pattern");
        }
        return KeepMemberPattern.memberBuilder().setAccessPattern(accessBuilder.build()).build();
      }
      if (method != null && field != null) {
        throw new KeepEdgeException("Cannot define both a field and a method pattern");
      }
      if (method != null) {
        return method;
      }
      if (field != null) {
        return field;
      }
      return KeepMemberPattern.none();
    }

    @Override
    boolean tryParse(String name, Object value) {
      return methodDeclaration.tryParse(name, value) || fieldDeclaration.tryParse(name, value);
    }

    @Override
    AnnotationVisitor tryParseArray(String name, Consumer<KeepMemberPattern> ignored) {
      if (name.equals(Item.memberAccess)) {
        accessBuilder = KeepMemberAccessPattern.memberBuilder();
        return new MemberAccessVisitor(annotationName, accessBuilder);
      }
      AnnotationVisitor visitor = methodDeclaration.tryParseArray(name, v -> {});
      if (visitor != null) {
        return visitor;
      }
      return fieldDeclaration.tryParseArray(name, v -> {});
    }
  }

  private abstract static class KeepItemVisitorBase extends AnnotationVisitorBase {
    private String memberBindingReference = null;
    private KeepItemKind kind = null;
    private final ClassDeclaration classDeclaration = new ClassDeclaration();
    private final ExtendsDeclaration extendsDeclaration = new ExtendsDeclaration();
    private final MemberDeclaration memberDeclaration;

    // Constructed item available once visitEnd has been called.
    private KeepItemReference itemReference = null;

    KeepItemVisitorBase() {
      memberDeclaration = new MemberDeclaration(getAnnotationName());
    }

    public KeepItemReference getItemReference() {
      if (itemReference == null) {
        throw new KeepEdgeException("Item reference not finalized. Missing call to visitEnd()");
      }
      return itemReference;
    }

    public KeepItemKind getKind() {
      return kind;
    }

    public boolean isDefaultMemberDeclaration() {
      return memberDeclaration.isDefault();
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
      if (!descriptor.equals(AnnotationConstants.Kind.DESCRIPTOR)) {
        super.visitEnum(name, descriptor, value);
      }
      switch (value) {
        case Kind.DEFAULT:
          // The default value is obtained by not assigning a kind (e.g., null in the builder).
          break;
        case Kind.ONLY_CLASS:
          kind = KeepItemKind.ONLY_CLASS;
          break;
        case Kind.ONLY_MEMBERS:
          kind = KeepItemKind.ONLY_MEMBERS;
          break;
        case Kind.CLASS_AND_MEMBERS:
          kind = KeepItemKind.CLASS_AND_MEMBERS;
          break;
        default:
          super.visitEnum(name, descriptor, value);
      }
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Item.memberFromBinding) && value instanceof String) {
        memberBindingReference = (String) value;
        return;
      }
      if (classDeclaration.tryParse(name, value)
          || extendsDeclaration.tryParse(name, value)
          || memberDeclaration.tryParse(name, value)) {
        return;
      }
      super.visit(name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      AnnotationVisitor visitor = memberDeclaration.tryParseArray(name, v -> {});
      if (visitor != null) {
        return visitor;
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      if (memberBindingReference != null) {
        if (!classDeclaration.getValue().equals(classDeclaration.getDefaultValue())
            || !memberDeclaration.getValue().isNone()
            || !extendsDeclaration.getValue().isAny()
            || kind != null) {
          throw new KeepEdgeException(
              "Cannot define an item explicitly and via a member-binding reference");
        }
        itemReference = KeepItemReference.fromBindingReference(memberBindingReference);
      } else {
        KeepMemberPattern memberPattern = memberDeclaration.getValue();
        // If the kind is not set (default) then the content of the members determines the kind.
        if (kind == null) {
          kind = memberPattern.isNone() ? KeepItemKind.ONLY_CLASS : KeepItemKind.ONLY_MEMBERS;
        }
        // If the kind is a member kind and no member pattern is set then set members to all.
        if (!kind.equals(KeepItemKind.ONLY_CLASS) && memberPattern.isNone()) {
          memberPattern = KeepMemberPattern.allMembers();
        }
        itemReference =
            KeepItemReference.fromItemPattern(
                KeepItemPattern.builder()
                    .setKind(kind)
                    .setClassReference(classDeclaration.getValue())
                    .setExtendsPattern(extendsDeclaration.getValue())
                    .setMemberPattern(memberPattern)
                    .build());
      }
    }
  }

  private static class KeepBindingVisitor extends KeepItemVisitorBase {

    private final KeepBindings.Builder builder;
    private String bindingName;

    public KeepBindingVisitor(KeepBindings.Builder builder) {
      this.builder = builder;
    }

    @Override
    public String getAnnotationName() {
      return Binding.CLASS.getSimpleName();
    }

    @Override
    public void visit(String name, Object value) {
      if (name.equals(Binding.bindingName) && value instanceof String) {
        bindingName = (String) value;
        return;
      }
      super.visit(name, value);
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      KeepItemReference item = getItemReference();
      // The language currently disallows aliasing bindings, thus a binding should directly be
      // defined by a reference to another binding.
      if (item.isBindingReference()) {
        throw new KeepEdgeException(
            "Invalid binding reference to '"
                + item.asBindingReference()
                + "' in binding definition of '"
                + bindingName
                + "'");
      }
      builder.addBinding(bindingName, item.asItemPattern());
    }
  }

  private static class StringArrayVisitor extends AnnotationVisitorBase {
    private final String annotationName;
    private final Consumer<List<String>> fn;
    private final List<String> strings = new ArrayList<>();

    public StringArrayVisitor(String annotationName, Consumer<List<String>> fn) {
      this.annotationName = annotationName;
      this.fn = fn;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    @Override
    public void visit(String name, Object value) {
      if (value instanceof String) {
        strings.add((String) value);
      } else {
        super.visit(name, value);
      }
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      fn.accept(strings);
    }
  }

  private static class OptionsDeclaration extends SingleDeclaration<KeepOptions> {

    private final String annotationName;

    public OptionsDeclaration(String annotationName) {
      this.annotationName = annotationName;
    }

    @Override
    String kind() {
      return "options";
    }

    @Override
    KeepOptions getDefaultValue() {
      return KeepOptions.keepAll();
    }

    @Override
    KeepOptions parse(String name, Object value) {
      return null;
    }

    @Override
    AnnotationVisitor parseArray(String name, Consumer<KeepOptions> setValue) {
      if (name.equals(AnnotationConstants.Target.disallow)) {
        return new KeepOptionsVisitor(
            annotationName,
            options -> setValue.accept(KeepOptions.disallowBuilder().addAll(options).build()));
      }
      if (name.equals(AnnotationConstants.Target.allow)) {
        return new KeepOptionsVisitor(
            annotationName,
            options -> setValue.accept(KeepOptions.allowBuilder().addAll(options).build()));
      }
      return null;
    }
  }

  private static class KeepTargetVisitor extends KeepItemVisitorBase {

    private final Parent<KeepTarget> parent;
    private final KeepTarget.Builder builder = KeepTarget.builder();
    private final OptionsDeclaration optionsDeclaration =
        new OptionsDeclaration(getAnnotationName());

    static KeepTargetVisitor create(Parent<KeepTarget> parent) {
      return new KeepTargetVisitor(parent);
    }

    private KeepTargetVisitor(Parent<KeepTarget> parent) {
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return Target.CLASS.getSimpleName();
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      AnnotationVisitor visitor = optionsDeclaration.tryParseArray(name, builder::setOptions);
      if (visitor != null) {
        return visitor;
      }
      return super.visitArray(name);
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      parent.accept(builder.setItemReference(getItemReference()).build());
    }
  }

  private static class KeepConditionVisitor extends KeepItemVisitorBase {

    private final Parent<KeepCondition> parent;

    public KeepConditionVisitor(Parent<KeepCondition> parent) {
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return Condition.CLASS.getSimpleName();
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      parent.accept(KeepCondition.builder().setItemReference(getItemReference()).build());
    }
  }

  private static class KeepOptionsVisitor extends AnnotationVisitorBase {

    private final String annotationName;
    private final Parent<Collection<KeepOption>> parent;
    private final Set<KeepOption> options = new HashSet<>();

    public KeepOptionsVisitor(String annotationName, Parent<Collection<KeepOption>> parent) {
      this.annotationName = annotationName;
      this.parent = parent;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    @Override
    public void visitEnum(String ignore, String descriptor, String value) {
      if (!descriptor.equals(AnnotationConstants.Option.DESCRIPTOR)) {
        super.visitEnum(ignore, descriptor, value);
      }
      KeepOption option;
      switch (value) {
        case Option.SHRINKING:
          option = KeepOption.SHRINKING;
          break;
        case Option.OPTIMIZATION:
          option = KeepOption.OPTIMIZING;
          break;
        case Option.OBFUSCATION:
          option = KeepOption.OBFUSCATING;
          break;
        case Option.ACCESS_MODIFICATION:
          option = KeepOption.ACCESS_MODIFICATION;
          break;
        case Option.ANNOTATION_REMOVAL:
          option = KeepOption.ANNOTATION_REMOVAL;
          break;
        default:
          super.visitEnum(ignore, descriptor, value);
          return;
      }
      options.add(option);
    }

    @Override
    public void visitEnd() {
      parent.accept(options);
      super.visitEnd();
    }
  }

  private static class MemberAccessVisitor extends AnnotationVisitorBase {
    private final String annotationName;
    KeepMemberAccessPattern.BuilderBase<?, ?> builder;

    public MemberAccessVisitor(
        String annotationName, KeepMemberAccessPattern.BuilderBase<?, ?> builder) {
      this.annotationName = annotationName;
      this.builder = builder;
    }

    @Override
    public String getAnnotationName() {
      return annotationName;
    }

    static boolean withNormalizedAccessFlag(String flag, BiPredicate<String, Boolean> fn) {
      boolean allow = !flag.startsWith(MemberAccess.NEGATION_PREFIX);
      return allow
          ? fn.test(flag, true)
          : fn.test(flag.substring(MemberAccess.NEGATION_PREFIX.length()), false);
    }

    @Override
    public void visitEnum(String ignore, String descriptor, String value) {
      if (!descriptor.equals(AnnotationConstants.MemberAccess.DESCRIPTOR)) {
        super.visitEnum(ignore, descriptor, value);
      }
      boolean handled =
          withNormalizedAccessFlag(
              value,
              (flag, allow) -> {
                AccessVisibility visibility = getAccessVisibilityFromString(flag);
                if (visibility != null) {
                  builder.setAccessVisibility(visibility, allow);
                  return true;
                }
                switch (flag) {
                  case MemberAccess.STATIC:
                    builder.setStatic(allow);
                    return true;
                  case MemberAccess.FINAL:
                    builder.setFinal(allow);
                    return true;
                  case MemberAccess.SYNTHETIC:
                    builder.setSynthetic(allow);
                    return true;
                  default:
                    return false;
                }
              });
      if (!handled) {
        super.visitEnum(ignore, descriptor, value);
      }
    }

    private AccessVisibility getAccessVisibilityFromString(String value) {
      switch (value) {
        case MemberAccess.PUBLIC:
          return AccessVisibility.PUBLIC;
        case MemberAccess.PROTECTED:
          return AccessVisibility.PROTECTED;
        case MemberAccess.PACKAGE_PRIVATE:
          return AccessVisibility.PACKAGE_PRIVATE;
        case MemberAccess.PRIVATE:
          return AccessVisibility.PRIVATE;
        default:
          return null;
      }
    }
  }

  private static class MethodAccessVisitor extends MemberAccessVisitor {

    KeepMethodAccessPattern.Builder builder;

    public MethodAccessVisitor(String annotationName, KeepMethodAccessPattern.Builder builder) {
      super(annotationName, builder);
      this.builder = builder;
    }

    @Override
    public void visitEnum(String ignore, String descriptor, String value) {
      if (!descriptor.equals(AnnotationConstants.MethodAccess.DESCRIPTOR)) {
        super.visitEnum(ignore, descriptor, value);
      }
      boolean handled =
          withNormalizedAccessFlag(
              value,
              (flag, allow) -> {
                switch (flag) {
                  case MethodAccess.SYNCHRONIZED:
                    builder.setSynchronized(allow);
                    return true;
                  case MethodAccess.BRIDGE:
                    builder.setBridge(allow);
                    return true;
                  case MethodAccess.NATIVE:
                    builder.setNative(allow);
                    return true;
                  case MethodAccess.ABSTRACT:
                    builder.setAbstract(allow);
                    return true;
                  case MethodAccess.STRICT_FP:
                    builder.setStrictFp(allow);
                    return true;
                  default:
                    return false;
                }
              });
      if (!handled) {
        // Continue visitation with the "member" descriptor to allow matching the common values.
        super.visitEnum(ignore, MemberAccess.DESCRIPTOR, value);
      }
    }
  }

  private static class FieldAccessVisitor extends MemberAccessVisitor {

    KeepFieldAccessPattern.Builder builder;

    public FieldAccessVisitor(String annotationName, KeepFieldAccessPattern.Builder builder) {
      super(annotationName, builder);
      this.builder = builder;
    }

    @Override
    public void visitEnum(String ignore, String descriptor, String value) {
      if (!descriptor.equals(AnnotationConstants.FieldAccess.DESCRIPTOR)) {
        super.visitEnum(ignore, descriptor, value);
      }
      boolean handled =
          withNormalizedAccessFlag(
              value,
              (flag, allow) -> {
                switch (flag) {
                  case FieldAccess.VOLATILE:
                    builder.setVolatile(allow);
                    return true;
                  case FieldAccess.TRANSIENT:
                    builder.setTransient(allow);
                    return true;
                  default:
                    return false;
                }
              });
      if (!handled) {
        // Continue visitation with the "member" descriptor to allow matching the common values.
        super.visitEnum(ignore, MemberAccess.DESCRIPTOR, value);
      }
    }
  }
}
