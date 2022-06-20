/*
 * Copyright (C) 2007, 2008, 2009, 2011, 2012, 2013, 2014, 2015, 2022 XStream Committers.
 * All rights reserved.
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 *
 * Created on 07. November 2007 by Joerg Schaible
 */
package com.thoughtworks.xstream.mapper;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.thoughtworks.xstream.InitializationException;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAliasType;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import com.thoughtworks.xstream.annotations.XStreamConverters;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.annotations.XStreamInclude;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.ConverterLookup;
import com.thoughtworks.xstream.converters.ConverterMatcher;
import com.thoughtworks.xstream.converters.ConverterRegistry;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.converters.SingleValueConverterWrapper;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.ClassLoaderReference;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.core.util.DependencyInjectionFactory;
import com.thoughtworks.xstream.core.util.TypedNull;


/**
 * A mapper that uses annotations to prepare the remaining mappers in the chain.
 *
 * @author J&ouml;rg Schaible
 * @since 1.3
 */
public class AnnotationMapper extends MapperWrapper implements AnnotationConfiguration {

    private boolean locked;
    private transient Object[] arguments;
    private final ConverterRegistry converterRegistry;
    private transient ClassAliasingMapper classAliasingMapper;
    private transient DefaultImplementationsMapper defaultImplementationsMapper;
    private transient ImplicitCollectionMapper implicitCollectionMapper;
    private transient FieldAliasingMapper fieldAliasingMapper;
    private transient ElementIgnoringMapper elementIgnoringMapper;
    private transient AttributeMapper attributeMapper;
    private transient LocalConversionMapper localConversionMapper;
    private final Map<Class<?>, Map<List<Object>, Converter>> converterCache = new HashMap<>();
    private final Set<Class<?>> annotatedTypes = Collections.synchronizedSet(new HashSet<Class<?>>());

    /**
     * Construct an AnnotationMapper.
     *
     * @param wrapped the next {@link Mapper} in the chain
     * @since 1.4.5
     */
    public AnnotationMapper(
            final Mapper wrapped, final ConverterRegistry converterRegistry, final ConverterLookup converterLookup,
            final ClassLoaderReference classLoaderReference, final ReflectionProvider reflectionProvider) {
        super(wrapped);
        this.converterRegistry = converterRegistry;
        annotatedTypes.add(Object.class);
        setupMappers();
        locked = true;

        final ClassLoader classLoader = classLoaderReference.getReference();
        arguments = new Object[]{
            this, classLoaderReference, reflectionProvider, converterLookup, new JVM(), classLoader != null
                ? classLoader
                : new TypedNull<>(ClassLoader.class)};
    }

    /**
     * Construct an AnnotationMapper.
     *
     * @param wrapped the next {@link Mapper} in the chain
     * @since 1.3
     * @deprecated As of 1.4.5 use
     *             {@link #AnnotationMapper(Mapper, ConverterRegistry, ConverterLookup, ClassLoaderReference, ReflectionProvider)}
     */
    @Deprecated
    public AnnotationMapper(
            final Mapper wrapped, final ConverterRegistry converterRegistry, final ConverterLookup converterLookup,
            final ClassLoader classLoader, final ReflectionProvider reflectionProvider, final JVM jvm) {
        this(wrapped, converterRegistry, converterLookup, new ClassLoaderReference(classLoader), reflectionProvider);
    }

    @Override
    public String realMember(final Class<?> type, final String serialized) {
        if (!locked) {
            processAnnotation(type);
        }
        return super.realMember(type, serialized);
    }

    @Override
    public String serializedClass(final Class<?> type) {
        if (!locked) {
            processAnnotation(type);
        }
        return super.serializedClass(type);
    }

    @Override
    public Class<?> defaultImplementationOf(final Class<?> type) {
        if (!locked) {
            processAnnotation(type);
        }
        final Class<?> defaultImplementation = super.defaultImplementationOf(type);
        if (!locked) {
            processAnnotation(defaultImplementation);
        }
        return defaultImplementation;
    }

    @Override
    public Converter getLocalConverter(final Class<?> definedIn, final String fieldName) {
        if (!locked) {
            processAnnotation(definedIn);
        }
        return super.getLocalConverter(definedIn, fieldName);
    }

    @Override
    public void autodetectAnnotations(final boolean mode) {
        locked = !mode;
    }

    @Override
    public void processAnnotations(final Class<?>... initialTypes) {
        if (initialTypes == null || initialTypes.length == 0) {
            return;
        }
        locked = true;

        final Set<Class<?>> types = new UnprocessedTypesSet();
        for (final Class<?> initialType : initialTypes) {
            types.add(initialType);
        }
        processTypes(types);
    }

    private void processAnnotation(final Class<?> initialType) {
        if (initialType == null) {
            return;
        }

        final Set<Class<?>> types = new UnprocessedTypesSet();
        types.add(initialType);
        processTypes(types);
    }

    private void processTypes(final Set<Class<?>> types) {
        while (!types.isEmpty()) {
            final Iterator<Class<?>> iter = types.iterator();
            final Class<?> type = iter.next();
            iter.remove();

            synchronized (type) {
                if (annotatedTypes.contains(type)) {
                    continue;
                }
                try {
                    if (type.isPrimitive()) {
                        continue;
                    }

                    addParametrizedTypes(type, types);

                    processConverterAnnotations(type);
                    processAliasAnnotation(type, types);
                    processAliasTypeAnnotation(type);

                    if (type.isInterface()) {
                        continue;
                    }

                    final Field[] fields = type.getDeclaredFields();
                    for (final Field field : fields) {
                        if (field.isEnumConstant()
                            || (field.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) > 0) {
                            continue;
                        }

                        addParametrizedTypes(field.getGenericType(), types);

                        if (field.isSynthetic()) {
                            continue;
                        }

                        processFieldAliasAnnotation(field);
                        processAsAttributeAnnotation(field);
                        processImplicitAnnotation(field);
                        processOmitFieldAnnotation(field);
                        processLocalConverterAnnotation(field);
                    }
                } finally {
                    annotatedTypes.add(type);
                }
            }
        }
    }

    private void addParametrizedTypes(Type type, final Set<Class<?>> types) {
        final Set<Type> processedTypes = new HashSet<>();
        final Set<Type> localTypes = new LinkedHashSet<Type>() {
            private static final long serialVersionUID = 20151010L;

            @Override
            public boolean add(final Type o) {
                if (o instanceof Class) {
                    return types.add((Class<?>)o);
                }
                return o == null || processedTypes.contains(o) ? false : super.add(o);
            }

        };
        while (type != null) {
            processedTypes.add(type);
            if (type instanceof Class) {
                final Class<?> clazz = (Class<?>)type;
                types.add(clazz);
                if (!clazz.isPrimitive()) {
                    final TypeVariable<?>[] typeParameters = clazz.getTypeParameters();
                    for (final TypeVariable<?> typeVariable : typeParameters) {
                        localTypes.add(typeVariable);
                    }
                    localTypes.add(clazz.getGenericSuperclass());
                    for (final Type iface : clazz.getGenericInterfaces()) {
                        localTypes.add(iface);
                    }
                }
            } else if (type instanceof TypeVariable) {
                final TypeVariable<?> typeVariable = (TypeVariable<?>)type;
                final Type[] bounds = typeVariable.getBounds();
                for (final Type bound : bounds) {
                    localTypes.add(bound);
                }
            } else if (type instanceof ParameterizedType) {
                final ParameterizedType parametrizedType = (ParameterizedType)type;
                localTypes.add(parametrizedType.getRawType());
                final Type[] actualArguments = parametrizedType.getActualTypeArguments();
                for (final Type actualArgument : actualArguments) {
                    localTypes.add(actualArgument);
                }
            } else if (type instanceof GenericArrayType) {
                final GenericArrayType arrayType = (GenericArrayType)type;
                localTypes.add(arrayType.getGenericComponentType());
            }

            if (!localTypes.isEmpty()) {
                final Iterator<Type> iter = localTypes.iterator();
                type = iter.next();
                iter.remove();
            } else {
                type = null;
            }
        }
    }

    private void processConverterAnnotations(final Class<?> type) {
        if (converterRegistry != null) {
            final XStreamConverters convertersAnnotation = type.getAnnotation(XStreamConverters.class);
            final XStreamConverter converterAnnotation = type.getAnnotation(XStreamConverter.class);
            final List<XStreamConverter> annotations = convertersAnnotation != null
                ? new ArrayList<>(Arrays.asList(convertersAnnotation.value()))
                : new ArrayList<>();
            if (converterAnnotation != null) {
                annotations.add(converterAnnotation);
            }
            for (final XStreamConverter annotation : annotations) {
                final Converter converter = cacheConverter(annotation, converterAnnotation != null ? type : null);
                if (converter != null) {
                    if (converterAnnotation != null || converter.canConvert(type)) {
                        converterRegistry.registerConverter(converter, annotation.priority());
                    } else {
                        throw new InitializationException("Converter "
                            + annotation.value().getName()
                            + " cannot handle annotated class "
                            + type.getName());
                    }
                }
            }
        }
    }

    private void processAliasAnnotation(final Class<?> type, final Set<Class<?>> types) {
        final XStreamAlias aliasAnnotation = type.getAnnotation(XStreamAlias.class);
        if (aliasAnnotation != null) {
            if (classAliasingMapper == null) {
                throw new InitializationException("No " + ClassAliasingMapper.class.getName() + " available");
            }
            classAliasingMapper.addClassAlias(aliasAnnotation.value(), type);
            if (aliasAnnotation.impl() != Void.class) {
                // Alias for Interface/Class with an impl
                defaultImplementationsMapper.addDefaultImplementation(aliasAnnotation.impl(), type);
                if (type.isInterface()) {
                    types.add(aliasAnnotation.impl()); // alias Interface's impl
                }
            }
        }
    }

    private void processAliasTypeAnnotation(final Class<?> type) {
        final XStreamAliasType aliasAnnotation = type.getAnnotation(XStreamAliasType.class);
        if (aliasAnnotation != null) {
            if (classAliasingMapper == null) {
                throw new InitializationException("No " + ClassAliasingMapper.class.getName() + " available");
            }
            classAliasingMapper.addTypeAlias(aliasAnnotation.value(), type);
        }
    }

    private void processFieldAliasAnnotation(final Field field) {
        final XStreamAlias aliasAnnotation = field.getAnnotation(XStreamAlias.class);
        if (aliasAnnotation != null) {
            if (fieldAliasingMapper == null) {
                throw new InitializationException("No " + FieldAliasingMapper.class.getName() + " available");
            }
            fieldAliasingMapper.addFieldAlias(aliasAnnotation.value(), field.getDeclaringClass(), field.getName());
        }
    }

    private void processAsAttributeAnnotation(final Field field) {
        final XStreamAsAttribute asAttributeAnnotation = field.getAnnotation(XStreamAsAttribute.class);
        if (asAttributeAnnotation != null) {
            if (attributeMapper == null) {
                throw new InitializationException("No " + AttributeMapper.class.getName() + " available");
            }
            attributeMapper.addAttributeFor(field);
        }
    }

    private void processImplicitAnnotation(final Field field) {
        final XStreamImplicit implicitAnnotation = field.getAnnotation(XStreamImplicit.class);
        if (implicitAnnotation != null) {
            if (implicitCollectionMapper == null) {
                throw new InitializationException("No " + ImplicitCollectionMapper.class.getName() + " available");
            }
            final String fieldName = field.getName();
            final String itemFieldName = implicitAnnotation.itemFieldName();
            final String keyFieldName = implicitAnnotation.keyFieldName();
            final boolean isMap = Map.class.isAssignableFrom(field.getType());
            Class<?> itemType = null;
            if (!field.getType().isArray()) {
                final Type genericType = field.getGenericType();
                if (genericType instanceof ParameterizedType) {
                    final Type[] actualTypeArguments = ((ParameterizedType)genericType).getActualTypeArguments();
                    final Type typeArgument = actualTypeArguments[isMap ? 1 : 0];
                    itemType = getClass(typeArgument);
                }
            }
            if (isMap) {
                implicitCollectionMapper.add(field.getDeclaringClass(), fieldName, itemFieldName != null
                    && !"".equals(itemFieldName) ? itemFieldName : null, itemType, keyFieldName != null
                        && !"".equals(keyFieldName) ? keyFieldName : null);
            } else {
                if (itemFieldName != null && !"".equals(itemFieldName)) {
                    implicitCollectionMapper.add(field.getDeclaringClass(), fieldName, itemFieldName, itemType);
                } else {
                    implicitCollectionMapper.add(field.getDeclaringClass(), fieldName, itemType);
                }
            }
        }
    }

    private void processOmitFieldAnnotation(final Field field) {
        final XStreamOmitField omitFieldAnnotation = field.getAnnotation(XStreamOmitField.class);
        if (omitFieldAnnotation != null) {
            if (elementIgnoringMapper == null) {
                throw new InitializationException("No " + ElementIgnoringMapper.class.getName() + " available");
            }
            elementIgnoringMapper.omitField(field.getDeclaringClass(), field.getName());
        }
    }

    private void processLocalConverterAnnotation(final Field field) {
        final XStreamConverter annotation = field.getAnnotation(XStreamConverter.class);
        if (annotation != null) {
            final Converter converter = cacheConverter(annotation, field.getType());
            if (converter != null) {
                if (localConversionMapper == null) {
                    throw new InitializationException("No " + LocalConversionMapper.class.getName() + " available");
                }
                localConversionMapper.registerLocalConverter(field.getDeclaringClass(), field.getName(), converter);
            }
        }
    }

    private Converter cacheConverter(final XStreamConverter annotation, final Class<?> targetType) {
        Converter result = null;
        final Object[] args;
        final List<Object> parameter = new ArrayList<>();
        if (targetType != null && annotation.useImplicitType()) {
            parameter.add(targetType);
        }
        final List<Object> arrays = new ArrayList<>();
        arrays.add(annotation.booleans());
        arrays.add(annotation.bytes());
        arrays.add(annotation.chars());
        arrays.add(annotation.doubles());
        arrays.add(annotation.floats());
        arrays.add(annotation.ints());
        arrays.add(annotation.longs());
        arrays.add(annotation.shorts());
        arrays.add(annotation.strings());
        arrays.add(annotation.types());
        for (final Object array : arrays) {
            if (array != null) {
                final int length = Array.getLength(array);
                for (int i = 0; i < length; i++) {
                    parameter.add(Array.get(array, i));
                }
            }
        }
        for (final Class<?> type : annotation.nulls()) {
            final TypedNull<?> nullType = new TypedNull<>(type);
            parameter.add(nullType);
        }
        final Class<? extends ConverterMatcher> converterType = annotation.value();
        Map<List<Object>, Converter> converterMapping = converterCache.get(converterType);
        if (converterMapping != null) {
            result = converterMapping.get(parameter);
        }
        if (result == null) {
            final int size = parameter.size();
            if (size > 0) {
                args = new Object[arguments.length + size];
                System.arraycopy(arguments, 0, args, size, arguments.length);
                System.arraycopy(parameter.toArray(new Object[size]), 0, args, 0, size);
            } else {
                args = arguments;
            }

            final Converter converter;
            try {
                if (SingleValueConverter.class.isAssignableFrom(converterType)
                    && !Converter.class.isAssignableFrom(converterType)) {
                    final SingleValueConverter svc = (SingleValueConverter)DependencyInjectionFactory.newInstance(
                        converterType, args);
                    converter = new SingleValueConverterWrapper(svc);
                } else {
                    converter = (Converter)DependencyInjectionFactory.newInstance(converterType, args);
                }
            } catch (final Exception e) {
                throw new InitializationException("Cannot instantiate converter "
                    + converterType.getName()
                    + (targetType != null ? " for type " + targetType.getName() : ""), e);
            }
            if (converterMapping == null) {
                converterMapping = new HashMap<>();
                converterCache.put(converterType, converterMapping);
            }
            converterMapping.put(parameter, converter);
            result = converter;
        }
        return result;
    }

    private Class<?> getClass(final Type typeArgument) {
        Class<?> type = null;
        if (typeArgument instanceof ParameterizedType) {
            type = (Class<?>)((ParameterizedType)typeArgument).getRawType();
        } else if (typeArgument instanceof Class) {
            type = (Class<?>)typeArgument;
        }
        return type;
    }

    private void setupMappers() {
        classAliasingMapper = lookupMapperOfType(ClassAliasingMapper.class);
        defaultImplementationsMapper = lookupMapperOfType(DefaultImplementationsMapper.class);
        implicitCollectionMapper = lookupMapperOfType(ImplicitCollectionMapper.class);
        fieldAliasingMapper = lookupMapperOfType(FieldAliasingMapper.class);
        elementIgnoringMapper = lookupMapperOfType(ElementIgnoringMapper.class);
        attributeMapper = lookupMapperOfType(AttributeMapper.class);
        localConversionMapper = lookupMapperOfType(LocalConversionMapper.class);
    }

    private void writeObject(final ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        final int max = arguments.length - 2;
        out.writeInt(max);
        for (int i = 0; i < max; i++) {
            out.writeObject(arguments[i]);
        }
    }

    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        setupMappers();
        final int max = in.readInt();
        arguments = new Object[max + 2];
        for (int i = 0; i < max; i++) {
            arguments[i] = in.readObject();
            if (arguments[i] instanceof ClassLoaderReference) {
                arguments[max + 1] = ((ClassLoaderReference)arguments[i]).getReference();
            }
        }
        arguments[max] = new JVM();
    }

    private final class UnprocessedTypesSet extends LinkedHashSet<Class<?>> {
        private static final long serialVersionUID = 20151010L;

        @Override
        public boolean add(Class<?> type) {
            if (type == null) {
                return false;
            }
            while (type.isArray()) {
                type = type.getComponentType();
            }
            final String name = type.getName();
            if (name.startsWith("java.") || name.startsWith("javax.")) {
                return false;
            }
            final boolean ret = annotatedTypes.contains(type) ? false : super.add(type);
            if (ret) {
                final XStreamInclude inc = type.getAnnotation(XStreamInclude.class);
                if (inc != null) {
                    final Class<?>[] incTypes = inc.value();
                    if (incTypes != null) {
                        for (final Class<?> incType : incTypes) {
                            add(incType);
                        }
                    }
                }
            }
            return ret;
        }
    }
}
