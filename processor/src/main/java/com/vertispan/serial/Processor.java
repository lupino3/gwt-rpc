package com.vertispan.serial;

import com.google.auto.service.AutoService;
import com.google.common.base.Charsets;
import com.squareup.javapoet.*;
import com.squareup.javapoet.TypeSpec.Builder;
import com.vertispan.gwtapt.JTypeUtils;
import com.vertispan.serial.impl.TypeSerializerImpl;
import com.vertispan.serial.model.SerializableTypeModel;
import com.vertispan.serial.model.SerializableTypeModel.Field;
import com.vertispan.serial.model.SerializableTypeModel.Property;
import com.vertispan.serial.processor.*;

import javax.annotation.Generated;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @todo this should probably be at least 2-4 other classes, not just one.
 */
@AutoService(javax.annotation.processing.Processor.class)
public class Processor extends AbstractProcessor {
    private static final String knownTypesFilename = "knownTypes.txt";

    private TypeElement serializationStreamReader;
    private TypeElement serializationStreamWriter;
    private TypeElement typeSerializer;
    private TypeElement fieldSerializer;
    private TypeElement serializationWiring;

    private Filer filer;
    private Messager messager;
    private Types types;
    private Elements elements;

    private Set<String> allTypes = new HashSet<>();


    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // Somewhat unusually, we request all types. It makes this processor more expensive than the usual one, but
        // we should be bound to only our project's sources.
        return Collections.singleton("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Collections.singleton("serial.knownSubtypes");
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        types = processingEnv.getTypeUtils();
        elements = processingEnv.getElementUtils();

        try {
            String knownSubtypes = processingEnv.getOptions().get("serial.knownSubtypes");
            if (knownSubtypes != null) {
                allTypes.addAll(readTypes(knownSubtypes));
            }
        } catch (IOException e) {
            messager.printMessage(Kind.ERROR, "Failed to read from " + knownTypesFilename);
            e.printStackTrace();
        }

        serializationStreamReader = elements.getTypeElement(SerializationStreamReader.class.getName());
        serializationStreamWriter = elements.getTypeElement(SerializationStreamWriter.class.getName());
        typeSerializer = elements.getTypeElement(TypeSerializer.class.getName());
        fieldSerializer = elements.getTypeElement(FieldSerializer.class.getName());
        serializationWiring = elements.getTypeElement(SerializationWiring.class.getName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // for each type we notice (not created by this processor?), note it in a file so we can work from it
        // later, during incremental updates to individual files (which could include creation of new files)

        // first though, we read the existing file, so that we can write updates to it.
        try {
            try {
                FileObject resource = processingEnv.getFiler().getResource(StandardLocation.SOURCE_OUTPUT, "", knownTypesFilename);
                Set<String> oldTypes = readTypes(resource);
                allTypes.addAll(oldTypes);
            } catch (IOException e) {
                //didn't exist yet, ignore
            }

            // then examine all classes we have been given
            Set<String> newTypes = collectTypes(roundEnv.getRootElements());
            allTypes.addAll(newTypes);

            if (roundEnv.processingOver()) {
                // write the new list of types to the file
                FileObject updated = processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, "", knownTypesFilename);
                writeTypes(updated, allTypes);
                //TODO seems poor form to think no one might process based on us...
                return false;
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Failed to update " + knownTypesFilename + ": " + e);
        }


        // continue processing with the full list of types if there was a change
        //TODO this is a little amateurish, try to keep the data collection more separate from the codegen...
        Map<TypeElement, Set<TypeElement>> subtypes = buildTypeTree(allTypes);
        for (Element element : roundEnv.getElementsAnnotatedWith(serializationWiring)) {

            SerializingTypes serializingTypes = new SerializingTypes(processingEnv.getTypeUtils(), processingEnv.getElementUtils(), subtypes);
            SerializableTypeOracleBuilder readStob = new SerializableTypeOracleBuilder(
                    processingEnv.getElementUtils(),
                    messager, serializingTypes
            );
            SerializableTypeOracleBuilder writeStob = new SerializableTypeOracleBuilder(
                    processingEnv.getElementUtils(),
                    messager, serializingTypes
            );

            // For each method that isn't createSerializer, it either reads or it writes. Methods of type
            // void are for writing, and take data as well as a writer, methods that return a type are for
            // reading, and only take a reader.
            // Pretty gross, but it will get us off the ground...

            for (ExecutableElement method : ElementFilter.methodsIn(element.getEnclosedElements())) {
                if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }
                if (isReadMethod(method)) {
                    readStob.addRootType(method.getReturnType());
                } else if (isWriteMethod(method)) {
                    for (VariableElement param : method.getParameters()) {
                        if (processingEnv.getTypeUtils().isSameType(param.asType(), serializationStreamWriter.asType())) {
                            continue;//that said, it should be the last param
                        }

                        writeStob.addRootType(param.asType());
                    }
                } else {
                    //confirm is createSerializer method - no params, returns a Serializer, otherwise error
                    if (!isSerializerFactoryMethod(method)) {
                        processingEnv.getMessager().printMessage(Kind.ERROR, "Not a serializer factory method, write method, or read method", method);
                    }
                }
            }

            SerializableTypeOracle writeOracle;
            SerializableTypeOracle readOracle;
            try {
                writeOracle = writeStob.build();
                readOracle = readStob.build();
            } catch (UnableToCompleteException e) {
//                throw new RuntimeException(e);
                //already logged a message, just give up
                return false;
            }

            try {
                writeImpl(writeOracle, readOracle, element, serializingTypes);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }


        return false;
    }

    private void writeImpl(SerializableTypeOracle writeOracle, SerializableTypeOracle readOracle, Element serializationInterface, SerializingTypes serializingTypes) throws IOException {

        SerializableTypeOracleUnion bidiOracle = new SerializableTypeOracleUnion(readOracle, writeOracle);
        //write field serializers
        TypeMirror[] allTypes = bidiOracle.getSerializableTypes();
        for (TypeMirror serializableType : allTypes) {
            if (serializableType.getKind() == TypeKind.ARRAY) {
                writeArraySerializer(serializableType, serializingTypes, bidiOracle);
                continue;
            }
            assert serializableType.getKind() == TypeKind.DECLARED : serializableType.getKind();
            //get the element itself and write it
            writeFieldSerializer(((TypeElement) ((DeclaredType) serializableType).asElement()), serializingTypes, bidiOracle);
        }


        String prefix = serializationInterface.getSimpleName().toString();
        String packageName = elements.getPackageOf(serializationInterface).getQualifiedName().toString();
        //write interface impl
        //TODO it would be lovely to have a metamodel...
        writeSerializerImpl(prefix, packageName, serializationInterface);

        //write type serializer, pointing at required field serializers and their appropriate use in each direction
        //TODO consider only doing this once, later, so we can be sure classes are still needed? not sure...
        writeTypeSerializer(bidiOracle, prefix, packageName);
        
    }

    private void writeSerializerImpl(String prefix, String packageName, Element serializationInterface) throws IOException {
        Builder implTypeBuilder = TypeSpec.classBuilder(prefix + "_Impl")
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", Processor.class.getCanonicalName()).build())
                .addSuperinterface(ClassName.get(serializationInterface.asType()))
                .addModifiers(Modifier.PUBLIC);

        //exactly one method should return a TypeSerializer

        //the rest are either read or write methods
        //TODO again, metamodel?
        

        for (ExecutableElement method : ElementFilter.methodsIn(serializationInterface.getEnclosedElements())) {
            if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.DEFAULT)) {
                continue;
            }
            if (types.isSameType(method.getReturnType(), typeSerializer.asType())) {
                implTypeBuilder.addMethod(MethodSpec.methodBuilder(method.getSimpleName().toString())
                        .returns(ClassName.get(typeSerializer))
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return new $L_TypeSerializer()", prefix)
                        .build());
            } else {
                //read or write
                if (method.getReturnType().getKind() == TypeKind.VOID) {
                    TypeMirror writtenType = method.getParameters().get(0).asType();
                    //write(OneObject, SSW)
                    implTypeBuilder.addMethod(MethodSpec.methodBuilder(method.getSimpleName().toString())
                            .returns(TypeName.VOID)
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(ClassName.get(writtenType), "instance")
                            .addParameter(SerializationStreamWriter.class, "writer")
                            .beginControlFlow("try")
                            .addStatement("writer.write$L(instance)", SerializableTypeModel.getStreamMethodSuffix(writtenType))
                            .nextControlFlow("catch ($T ex)", SerializationException.class)
                            .addStatement("throw new IllegalStateException(ex)")
                            .endControlFlow()
                            .build());
                } else {
                    TypeMirror readType = method.getReturnType();
                    //OneObject read(SSR)
                    implTypeBuilder.addMethod(MethodSpec.methodBuilder(method.getSimpleName().toString())
                            .returns(ClassName.get(readType))
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(SerializationStreamReader.class, "reader")
                            .beginControlFlow("try")
                            .addStatement("return ($T) reader.read$L()", ClassName.get(readType), SerializableTypeModel.getStreamMethodSuffix(readType))
                            .nextControlFlow("catch ($T ex)", SerializationException.class)
                            .addStatement("throw new IllegalStateException(ex)")
                            .endControlFlow()
                            .build());
                }
            }

        }


        JavaFile.builder(packageName, implTypeBuilder.build()).build().writeTo(filer);
    }

    private void writeTypeSerializer(SerializableTypeOracleUnion bidiOracle, String prefix, String packageName) throws IOException {
        Builder typeSerializer = TypeSpec.classBuilder(prefix + "_TypeSerializer")
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", Processor.class.getCanonicalName()).build())
                .superclass(TypeSerializerImpl.class)
                .addModifiers(Modifier.PUBLIC);

        //TODO this isn't optimal, but is easy to write quickly
        typeSerializer.addField(FieldSpec.builder(
                ParameterizedTypeName.get(Map.class, String.class, FieldSerializer.class),
                "fieldSerializer",
                Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC).initializer("new $T()", ClassName.get(HashMap.class)).build());

        CodeBlock.Builder clinit = CodeBlock.builder();
        for (TypeMirror serializableType : bidiOracle.getSerializableTypes()) {
            clinit.addStatement("fieldSerializer.put($S, new $T())", ClassName.get(serializableType).toString(), getFieldSerializer(serializableType, bidiOracle));
        }
        typeSerializer.addStaticBlock(clinit.build());

        typeSerializer.addMethod(MethodSpec.methodBuilder("serializer")
                .addModifiers(Modifier.PROTECTED)
                .addAnnotation(Override.class)
                .addParameter(String.class, "name")
                .returns(FieldSerializer.class)
                .addStatement("return fieldSerializer.get(name)")
                .build());
        JavaFile.builder(packageName, typeSerializer.build()).build().writeTo(filer);
    }

    private void writeArraySerializer(TypeMirror arrayType, SerializingTypes serializingTypes, SerializableTypeOracle stob) throws IOException {
        int rank = JTypeUtils.getRank(arrayType);
        assert rank > 0;
        TypeMirror componentType = JTypeUtils.getLeafType(arrayType);

        String packageName = arraySerializerPackage(componentType);
        ClassName fieldSerializerName = getFieldSerializer(arrayType, null);

        TypeSpec.Builder fieldSerializerType = TypeSpec.classBuilder(fieldSerializerName)
                .addSuperinterface(ClassName.get(fieldSerializer))
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", Processor.class.getCanonicalName()).build())
                .addModifiers(Modifier.PUBLIC);//TODO originating element if it is an element

        //write deserialize method
        MethodSpec.Builder deserializeMethodBuilder = MethodSpec.methodBuilder("deserialize")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(SerializationStreamReader.class, "reader")
                .addParameter(ClassName.get(arrayType), "instance")
                .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                .addException(SerializationException.class);

        //TODO for readObject, share the Object_Array_CustomFieldSerializer
        deserializeMethodBuilder
                .beginControlFlow("for (int i = 0, n = instance.length; i < n; ++i)")
                .addStatement("instance[i] = reader.read$L()", SerializableTypeModel.getStreamMethodSuffix(componentType))
                .endControlFlow();

        fieldSerializerType.addMethod(deserializeMethodBuilder.build());


        //write serialize
        MethodSpec.Builder serializeMethodBuilder = MethodSpec.methodBuilder("serialize")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(SerializationStreamWriter.class, "writer")
                .addParameter(ClassName.get(arrayType), "instance")
                .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                .addException(SerializationException.class);

        serializeMethodBuilder
                .addStatement("writer.writeInt(instance.length)")
                .beginControlFlow("for (int i = 0, n = instance.length; i < n; ++i)")
                .addStatement("writer.write$L(instance[i])", SerializableTypeModel.getStreamMethodSuffix(componentType))
                .endControlFlow();

        fieldSerializerType.addMethod(serializeMethodBuilder.build());

        //write instantiate
        StringBuilder extraArrayRank = new StringBuilder();
        for (int i = 0; i < rank - 1; ++i) {
            extraArrayRank.append("[]");
        }
        MethodSpec.Builder instantiateMethodBuilder = MethodSpec.methodBuilder("instantiate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(arrayType))
                .addParameter(SerializationStreamReader.class, "reader")
                .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                .addException(SerializationException.class)
                .addStatement("int length = reader.readInt()")
                .addStatement("reader.claimItems(length)")
                .addStatement("return new $T[length]$L", ClassName.get(componentType), extraArrayRank);

        fieldSerializerType.addMethod(instantiateMethodBuilder.build());

        writeInstanceMethods(fieldSerializerType, SerializableTypeModel.array(arrayType), true, true, true);

        JavaFile file = JavaFile.builder(packageName, fieldSerializerType.build()).build();

        try {
            file.writeTo(filer);
        } catch (FilerException ignore) {
            // someone already wrote this type - doesn't matter, should be consistent no matter who did it
        }
    }

    private String arraySerializerPackage(TypeMirror componentType) {
        if (componentType.getKind().isPrimitive()) {
            return elements.getPackageOf(typeSerializer).getQualifiedName().toString();
        }
        assert componentType.getKind() == TypeKind.DECLARED;
        return elements.getPackageOf(types.asElement(componentType)).getQualifiedName().toString();
    }

    private ClassName getFieldSerializer(TypeMirror type, SerializableTypeOracleUnion bidiOracle) {
        if (type.getKind() == TypeKind.ARRAY) {
            //TODO no more null check
            if (bidiOracle == null) {
                return ClassName.get(
                        arraySerializerPackage(JTypeUtils.getLeafType(type)),
                        arraySerializerName(JTypeUtils.getLeafType(type), JTypeUtils.getRank(type))
                );
            } else {
                return ClassName.get(
                        arraySerializerPackage(JTypeUtils.getLeafType(type)),
                        arraySerializerName(JTypeUtils.getLeafType(type), JTypeUtils.getRank(type)),
                        bidiOracle.getSpecificFieldSerializer(type)
                );
            }
        }
        assert type.getKind() == TypeKind.DECLARED;
        return getFieldSerializer((TypeElement) types.asElement(type), bidiOracle);
    }
    private ClassName getFieldSerializer(TypeElement type, SerializableTypeOracleUnion bidiOracle) {
        //TODO push this into STM, share the impl

        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        String outerClassName = "FieldSerializer";
        Element elt = type;
        do {
            outerClassName = elt.getSimpleName() + "_" + outerClassName;
            elt = elt.getEnclosingElement();
        } while (elt.getKind() != ElementKind.PACKAGE);
        //TODO no more null check
        if (bidiOracle == null) {
            return ClassName.get(packageName, outerClassName);
        } else {
            return ClassName.get(packageName, outerClassName, bidiOracle.getSpecificFieldSerializer(type.asType()));
        }
    }

    private String arraySerializerName(TypeMirror componentType, int rank) {
        if (componentType.getKind().isPrimitive()) {
            return componentType.getKind().name() + "_ArrayRank_" + rank + "_FieldSerializer";
        }
        assert componentType.getKind() == TypeKind.DECLARED;
        return types.asElement(componentType).getSimpleName() + "_ArrayRank_" + rank + "_FieldSerializer";
    }

    private void writeFieldSerializer(TypeElement typeElement, SerializingTypes serializingTypes, SerializableTypeOracle stob) throws IOException {
        //collect fields (err, properties for now) 
        SerializableTypeModel model = SerializableTypeModel.create(serializingTypes, typeElement);

        TypeSpec.Builder fieldSerializerType = TypeSpec.classBuilder(model.getFieldSerializerName())
                .addSuperinterface(ClassName.get(fieldSerializer))
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", Processor.class.getCanonicalName()).build())
                .addModifiers(Modifier.PUBLIC)
                .addOriginatingElement(typeElement);
        boolean writeSerialize = false, writeDeserialize = false, writeInstantiate = false;
        if (typeElement.getKind() != ElementKind.ENUM) {
            assert typeElement.getKind() == ElementKind.CLASS;

            writeSerialize = true;
            writeDeserialize = true;

            //write field accessors for violator stuff
            //TODO support private fields, not just the easy stuff. (then we can support final and other good fun)

            //write deserialize method
            MethodSpec.Builder deserializeMethodBuilder = MethodSpec.methodBuilder("deserialize")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(TypeName.VOID)
                    .addParameter(SerializationStreamReader.class, "reader")
//                    .addParameter(ClassName.get(typeElement), "instance")//this will be handled once we know what type we are dealing with below
                    .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                    .addException(SerializationException.class);

            if (model.getCustomFieldSerializer() != null) {
                deserializeMethodBuilder.addParameter(model.getDeserializeMethodParamType(), "instance");
                deserializeMethodBuilder.addStatement("$L.deserialize(reader, instance)", model.getCustomFieldSerializer());
            } else {
                deserializeMethodBuilder.addParameter(ClassName.get(typeElement), "instance");
                for (Property property : model.getProperties()) {
                    deserializeMethodBuilder.addStatement("instance.$L(($T) reader.read$L())", property.getSetter().getSimpleName(), property.getTypeName(), property.getStreamMethodName());
                }
                for (Field field : model.getFields()) {
                    deserializeMethodBuilder.addStatement("instance.$L = ($T) reader.read$L()", field.getField().getSimpleName(), field.getTypeName(), field.getStreamMethodName());
                }

                //walk up to superclass, if any
                if (typeElement.getSuperclass().getKind() != TypeKind.NONE && stob.isSerializable(typeElement.getSuperclass())) {
                    deserializeMethodBuilder.addStatement("$L.deserialize(reader, instance)", getFieldSerializer(typeElement.getSuperclass(), null));
                }
            }

            fieldSerializerType.addMethod(deserializeMethodBuilder.build());

            //write serialize
            MethodSpec.Builder serializeMethodBuilder = MethodSpec.methodBuilder("serialize")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(TypeName.VOID)
                    .addParameter(SerializationStreamWriter.class, "writer")
//                    .addParameter(ClassName.get(typeElement), "instance")//this will be handled once we know what type we are dealing with below
                    .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                    .addException(SerializationException.class);

            if (model.getCustomFieldSerializer() != null) {
                serializeMethodBuilder.addParameter(model.getSerializeMethodParamType(), "instance");
                serializeMethodBuilder.addStatement("$L.serialize(writer, instance)", model.getCustomFieldSerializer());
            } else {
                serializeMethodBuilder.addParameter(ClassName.get(typeElement), "instance");
                for (Property property : model.getProperties()) {
                    serializeMethodBuilder.addStatement("writer.write$L(instance.$L())", property.getStreamMethodName(), property.getGetter().getSimpleName());
                }
                for (Field field : model.getFields()) {
                    serializeMethodBuilder.addStatement("writer.write$L(instance.$L)", field.getStreamMethodName(), field.getField().getSimpleName());
                }

                //walk up to superclass, if any
                if (typeElement.getSuperclass().getKind() != TypeKind.NONE && stob.isSerializable(typeElement.getSuperclass())) {
                    deserializeMethodBuilder.addStatement("$L.serialize(writer, instance)", getFieldSerializer(typeElement.getSuperclass(), null));
                }
            }
            
            fieldSerializerType.addMethod(serializeMethodBuilder.build());
        }
        //maybe write instantiate (if not abstract, and has default ctor) OR is an enum
        MethodSpec.Builder instantiateMethodBuilder = MethodSpec.methodBuilder("instantiate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Object.class)// could be ClassName.get(typeElement), if visible...
                .addParameter(SerializationStreamReader.class, "reader")
                .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                .addException(SerializationException.class);

        if (model.getCustomFieldSerializer() != null && CustomFieldSerializerValidator.hasInstantiationMethod(types, model.getCustomFieldSerializer(), typeElement.asType())) {
            instantiateMethodBuilder.addStatement("return $L.instantiate(reader)", model.getCustomFieldSerializer());
        } else {
            if (typeElement.getKind() == ElementKind.ENUM || (!typeElement.getModifiers().contains(Modifier.ABSTRACT) && JTypeUtils.isDefaultInstantiable(typeElement))) {
                writeInstantiate = true;
                //TODO support private and delegate to violator

                if (typeElement.getKind() == ElementKind.ENUM) {
                    instantiateMethodBuilder.addStatement("return $T.values()[reader.readInt()]", ClassName.get(typeElement));
                } else {
                    instantiateMethodBuilder.addStatement("return new $T()", ClassName.get(typeElement));
                }
            } else {
                //TODO actually handle writeInstantiate
                instantiateMethodBuilder.addStatement("throw new IllegalStateException(\"Not instantiable\")");
            }
        }
        fieldSerializerType.addMethod(instantiateMethodBuilder.build());

        writeInstanceMethods(fieldSerializerType, model, writeSerialize, writeDeserialize, writeInstantiate);

        String packageName = elements.getPackageOf(typeElement).getQualifiedName().toString();
        JavaFile file = JavaFile.builder(packageName, fieldSerializerType.build()).build();

        try {
            file.writeTo(filer);
        } catch (FilerException ignore) {
            // someone already wrote this type - doesn't matter, should be consistent no matter who did it
        }
    }


    /**
     * Write instance methods for field serializer, if required, in subclasses which support the operations needed.
     *
     * This exists so that the compiler can prune serialization code for objects that can only be deserialized, or
     * deserialization code for objects that can only be serialized. In classic RPC, this was managed through a
     * js/jsni map of method references, but that is not going to fly for J2CL, so instead the goal here is to
     * produce multiple field serializer types which each only contain the methods required for a particular use
     * case.
     *  @param fieldSerializerType the being built
     * @param dataType the type being de/serialized
     * @param writeSerialize whether or not it is necessary to serialize fields
     * @param writeDeserialize whether or not it is necessary to deserialize fields
     * @param writeInstantiate whether or not instantiation is supported
     */
    private void writeInstanceMethods(Builder fieldSerializerType, SerializableTypeModel dataType, boolean writeSerialize, boolean writeDeserialize, boolean writeInstantiate) {

        // cases that can be supported:
        //  * write object only
        //  * read object only, with instantiate
        //  * read object only, as superclass
        //  * write object, read object with instantiate
        //  * write object, read object as superclass
        //
        // this seems excessive, but it allows the compiler to remove unused methods based on the STOB,
        // rather than the native map approach of gwt2's rpc

//        writeInnerFieldSerializer(fieldSerializerType, dataType, true, true, false, false, "_WriteOnlyInstantiate");
        writeInnerFieldSerializer(fieldSerializerType, dataType, true, false, false, false, "WriteOnly");
        writeInnerFieldSerializer(fieldSerializerType, dataType, false, false, true, true, "ReadOnlyInstantiate");
        writeInnerFieldSerializer(fieldSerializerType, dataType, false, false, true, false, "ReadOnlySuperclass");
        writeInnerFieldSerializer(fieldSerializerType, dataType, true, true, true, true, "WriteInstantiateReadInstantiate");
//        writeInnerFieldSerializer(fieldSerializerType, dataType, true, false, true, true, "_WriteSuperclassReadInstantiate");
        writeInnerFieldSerializer(fieldSerializerType, dataType, true, true, true, false, "WriteInstantiateReadSuperclass");
//        writeInnerFieldSerializer(fieldSerializerType, dataType, true, false, true, false, "_WriteSuperclassReadSuperclass");


        // Now that we have those, the base class does nothing

    }

    private void writeInnerFieldSerializer(Builder fieldSerializerType, SerializableTypeModel dataType, boolean write, boolean ignore, boolean read, boolean instantiate, String nestedTypeName) {
        TypeSpec.Builder inner = TypeSpec.classBuilder(nestedTypeName).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        inner.superclass(ClassName.get("", fieldSerializerType.build().name));
        if (write) {
            inner.addMethod(MethodSpec.methodBuilder("serial")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addParameter(SerializationStreamWriter.class, "writer")
                    .addParameter(Object.class, "instance")
                    .returns(TypeName.VOID)
                    .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                    .addException(SerializationException.class)
                    .addStatement("serialize(writer, ($T) instance)", dataType.getSerializeMethodParamType())
                    .build());
        }
        if (read) {
            inner.addMethod(MethodSpec.methodBuilder("deserial")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addParameter(SerializationStreamReader.class, "reader")
                    .addParameter(Object.class, "instance")
                    .returns(TypeName.VOID)
                    .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                    .addException(SerializationException.class)
                    .addStatement("deserialize(reader, ($T)instance)", dataType.getDeserializeMethodParamType())
                    .build());
        }
        if (instantiate) {
            inner.addMethod(MethodSpec.methodBuilder("create")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addParameter(SerializationStreamReader.class, "reader")
                    .returns(Object.class)
                    .addException(com.google.gwt.user.client.rpc.SerializationException.class)
                    .addException(SerializationException.class)
                    .addStatement("return instantiate(reader)")
                    .build());
        }

        fieldSerializerType.addType(inner.build());
    }

    // returns an object, to be read from the reader. Note that objects don't _have_ to be read this way, but it is an easy option
    private boolean isReadMethod(ExecutableElement method) {
        return method.getParameters().size() == 1 && types.isSameType(method.getParameters().get(0).asType(), serializationStreamReader.asType());
    }

    // returns void, takes several objects to write, with the final parameter being the writer
    private boolean isWriteMethod(ExecutableElement method) {
        return method.getReturnType().getKind() == TypeKind.VOID
                && method.getParameters().size() > 1
                && types.isSameType(method.getParameters().get(method.getParameters().size() - 1).asType(), serializationStreamWriter.asType());
    }

    // zero-arg method, returns the serializer that can be used
    private boolean isSerializerFactoryMethod(ExecutableElement method) {
        return method.getParameters().isEmpty() && types.isSameType(method.getReturnType(), typeSerializer.asType());
    }

    private Map<TypeElement, Set<TypeElement>> buildTypeTree(Set<String> allTypes) {
        // not sure we can do it sooner, so lets remove all unreachable types here, and build the tree
        Set<TypeElement> existingTypes = allTypes.stream()
                .map(typeName -> {
                    try {
                        TypeElement typeElement = elements.getTypeElement(typeName);
                        if (typeElement == null && typeName.contains("$")) {
                            typeElement = elements.getTypeElement(typeName.replaceAll("\\$", "."));
                        }
                        return typeElement;
                    } catch (Exception e) {
                        //ignore this type, we can't see or load the type, possibly something emulated?
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // for each type, if not already present, put it and all parents in the map
        HashMap<TypeElement, Set<TypeElement>> map = new HashMap<>();
        existingTypes.forEach(type -> appendWithParent(type, map));

        return map;
    }
    private void appendWithParent(TypeElement type, Map<TypeElement, Set<TypeElement>> map) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() == TypeKind.NONE) {
            //top of the tree, we're done
            return;
        }
        assert superclass.getKind() == TypeKind.DECLARED;
        TypeElement superclassElement = (TypeElement) ((DeclaredType) superclass).asElement();
        boolean added = map.computeIfAbsent(superclassElement, ignore -> new HashSet<>()).add(type);
        if (!added) {
            //we've already looked at this type, which means we've already added interfaces and parents too, give up early
            return;
        }
        appendWithParent(superclassElement, map);

        List<? extends TypeMirror> interfaces = type.getInterfaces();
        for (TypeMirror anInterface : interfaces) {
            TypeElement interfaceElement = (TypeElement) ((DeclaredType) anInterface).asElement();
            map.computeIfAbsent(interfaceElement, ignore -> new HashSet<>()).add(type);

            appendWithParent(interfaceElement, map);
        }


    }

    private void writeTypes(FileObject updated, Set<String> allTypes) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(updated.openOutputStream(), Charsets.UTF_8))) {
            writer.append("# Generated file, describing known types in the current project to\n");
            writer.append("# allow incremental code generation\n");

            for (String type : allTypes) {
                writer.write(type);
                writer.newLine();
            }
        }
    }

    private Set<String> collectTypes(Set<? extends Element> rootElements) {
        Set<TypeElement> types = new HashSet<>();
        new Scanner().scan(rootElements, types);
        return types.stream().map(elt -> elt.getQualifiedName().toString()).collect(Collectors.toSet());
    }

    private Set<String> readTypes(FileObject resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openInputStream(), Charsets.UTF_8))) {
            Set<String> lines = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                lines.add(line);
            }
            return lines;
        }
    }
    private Collection<? extends String> readTypes(String knownSubtypes) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(knownSubtypes), Charsets.UTF_8))) {
            Set<String> lines = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                lines.add(line);
            }
            return lines;
        }
    }


    /**
     * Simple scanner to find only enums and classes - things the user might create or edit which we need to notice
     * from the bottom of the type hierarchy, up.
     */
    private static class Scanner extends ElementScanner8<Void, Set<TypeElement>> {
        @Override
        public Void visitType(TypeElement e, Set<TypeElement> typeElements) {
            if (e.getKind() == ElementKind.CLASS || e.getKind() == ElementKind.ENUM) {
                // mark classes and enums seen, since someone might avoid referencing either kind
                // directly, but have them extend/implement a serializable type
                typeElements.add(e);
            }
            return super.visitType(e, typeElements);
        }
    }
}
