/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.apt;

import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.tools.JavaFileObject;
import javax.tools.Diagnostic.Kind;

import com.mysema.commons.lang.Assert;
import com.mysema.query.codegen.ClassModel;
import com.mysema.query.codegen.ClassModelFactory;
import com.mysema.query.codegen.Serializer;
import com.mysema.query.codegen.Serializers;
import com.mysema.query.codegen.TypeModelFactory;

/**
 * 
 * @author tiwe
 * 
 */
public class Processor {
    
    private final Class<? extends Annotation> entityAnn, superTypeAnn, embeddableAnn, dtoAnn, skipAnn;
    
    private final ProcessingEnvironment env;
    
    private final APTModelFactory typeFactory;
    
    private final ClassModelFactory classModelFactory;
    
    private final String namePrefix = "Q";
    
    private boolean useFields = true, useGetters = true;
    
    @SuppressWarnings("unchecked")
    public Processor(ProcessingEnvironment env,
            Class<? extends Annotation> entityAnn, 
            Class<? extends Annotation> superTypeAnn,
            Class<? extends Annotation> embeddableAnn,
            Class<? extends Annotation> dtoAnn,
            Class<? extends Annotation> skipAnn) {
        this.env = Assert.notNull(env);
        this.entityAnn = Assert.notNull(entityAnn);
        this.superTypeAnn = superTypeAnn;
        this.embeddableAnn = embeddableAnn;
        this.dtoAnn = dtoAnn;
        this.skipAnn = skipAnn;        
        Class<? extends Annotation>[] anns ;
        if (embeddableAnn != null){
            anns = new Class[]{entityAnn, embeddableAnn};
        }else{
            anns = new Class[]{entityAnn};            
        }
        TypeModelFactory factory = new TypeModelFactory(anns);
        this.typeFactory = new APTModelFactory(factory, Arrays.asList(anns));
        this.classModelFactory = new ClassModelFactory(factory);
    }
    
    protected boolean isValidConstructor(ExecutableElement constructor) {
        return constructor.getModifiers().contains(Modifier.PUBLIC);
    }

    protected boolean isValidField(VariableElement field) {
        return useFields
            && field.getAnnotation(skipAnn) == null
            && !field.getModifiers().contains(Modifier.TRANSIENT) 
            && !field.getModifiers().contains(Modifier.STATIC);
    }

    protected boolean isValidGetter(ExecutableElement getter){
        return useGetters
            && getter.getAnnotation(skipAnn) == null
            && !getter.getModifiers().contains(Modifier.STATIC);
    }

    public void process(RoundEnvironment roundEnv) {
        Map<String, ClassModel> superTypes = new HashMap<String, ClassModel>();

        EntityElementVisitor entityVisitor = new EntityElementVisitor(env, namePrefix, classModelFactory, typeFactory){
            @Override
            protected boolean isValidField(VariableElement field) {
                return Processor.this.isValidField(field);
            }

            @Override
            protected boolean isValidGetter(ExecutableElement method) {
                return Processor.this.isValidGetter(method);
            }
            
        }; 
        
        // populate super type mappings
        if (superTypeAnn != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(superTypeAnn)) {
//                ClassModel model = getClassModel(element);
                ClassModel model = element.accept(entityVisitor, null);
                superTypes.put(model.getName(), model);
            }
        }

        // ENTITIES
        
        // populate entity type mappings
        Map<String, ClassModel> entityTypes = new HashMap<String, ClassModel>();
        for (Element element : roundEnv.getElementsAnnotatedWith(entityAnn)) {
            ClassModel model = element.accept(entityVisitor, null);
            entityTypes.put(model.getName(), model);
        }
        // add super type fields
        for (ClassModel entityType : entityTypes.values()) {
            entityType.addSupertypeFields(entityTypes, superTypes);
        }
        // serialize entity types
        if (!entityTypes.isEmpty()) {
            serialize(Serializers.DOMAIN, entityTypes);
        }
        
        // EMBEDDABLES (optional)
        
        if (embeddableAnn != null){
            // populate entity type mappings
            Map<String, ClassModel> embeddables = new HashMap<String, ClassModel>();
            for (Element element : roundEnv.getElementsAnnotatedWith(embeddableAnn)) {
                ClassModel model = element.accept(entityVisitor, null);
                embeddables.put(model.getName(), model);
            }
            // add super type fields
            for (ClassModel embeddable : embeddables.values()) {
                embeddable.addSupertypeFields(embeddables, superTypes);
            }
            // serialize entity types
            if (!embeddables.isEmpty()) {
                serialize(Serializers.EMBEDDABLE, embeddables);
            }            
        }

        // DTOS (optional)
        
        if (dtoAnn != null){
            DTOElementVisitor dtoVisitor = new DTOElementVisitor(env, namePrefix, classModelFactory, typeFactory){
                @Override
                protected boolean isValidConstructor(ExecutableElement constructor) {
                    return Processor.this.isValidConstructor(constructor);
                }
                
            };
            Map<String, ClassModel> dtos = new HashMap<String, ClassModel>();
            for (Element element : roundEnv.getElementsAnnotatedWith(dtoAnn)) {
                ClassModel model = element.accept(dtoVisitor, null);
                dtos.put(model.getName(), model);
            }
            // serialize entity types
            if (!dtos.isEmpty()) {
                serialize(Serializers.DTO, dtos);
            }    
        }         
        
    }
    
    private void serialize(Serializer serializer, Map<String, ClassModel> types) {
        Messager msg = env.getMessager();
        for (ClassModel type : types.values()) {
            msg.printMessage(Kind.NOTE, type.getName() + " is processed");
            try {
                String packageName = type.getPackageName();                    
                String className = packageName + "." + namePrefix + type.getSimpleName();
                JavaFileObject fileObject = env.getFiler().createSourceFile(className);
                Writer writer = fileObject.openWriter();
                try {
                    serializer.serialize(type, writer);    
                }finally{
                    if (writer != null) writer.close();
                }                
            } catch (Exception e) {
                msg.printMessage(Kind.ERROR, e.getMessage());
            }
        }
    }

    public Processor setUseFields(boolean useFields){
        this.useFields = useFields;
        return this;
    }
    
    public Processor setUseGetters(boolean useGetters) {
        this.useGetters = useGetters;        
        return this;
    }

}
