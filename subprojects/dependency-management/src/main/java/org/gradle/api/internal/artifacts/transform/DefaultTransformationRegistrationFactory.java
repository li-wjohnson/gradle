/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.InputArtifactDependencies;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.internal.artifacts.ArtifactTransformRegistration;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.tasks.properties.DefaultParameterValidationContext;
import org.gradle.api.internal.tasks.properties.FileParameterUtils;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.internal.tasks.properties.TypeMetadata;
import org.gradle.api.internal.tasks.properties.TypeMetadataStore;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.fingerprint.FileCollectionFingerprinterRegistry;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.instantiation.InstantiationScheme;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.internal.snapshot.ValueSnapshotter;
import org.gradle.model.internal.type.ModelType;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultTransformationRegistrationFactory implements TransformationRegistrationFactory {

    private final BuildOperationExecutor buildOperationExecutor;
    private final IsolatableFactory isolatableFactory;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final TransformerInvocationFactory transformerInvocationFactory;
    private final ValueSnapshotter valueSnapshotter;
    private final PropertyWalker parametersPropertyWalker;
    private final DomainObjectProjectStateHandler domainObjectProjectStateHandler;
    private final TypeMetadataStore actionMetadataStore;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry;
    private final InstantiationScheme actionInstantiationScheme;
    private final InstantiationScheme legacyActionInstantiationScheme;

    public DefaultTransformationRegistrationFactory(
        BuildOperationExecutor buildOperationExecutor,
        IsolatableFactory isolatableFactory,
        ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
        TransformerInvocationFactory transformerInvocationFactory,
        ValueSnapshotter valueSnapshotter,
        FileCollectionFactory fileCollectionFactory,
        FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry,
        DomainObjectProjectStateHandler domainObjectProjectStateHandler,
        ArtifactTransformParameterScheme parameterScheme,
        ArtifactTransformActionScheme actionScheme
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.isolatableFactory = isolatableFactory;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.transformerInvocationFactory = transformerInvocationFactory;
        this.valueSnapshotter = valueSnapshotter;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileCollectionFingerprinterRegistry = fileCollectionFingerprinterRegistry;
        this.actionInstantiationScheme = actionScheme.getInstantiationScheme();
        this.actionMetadataStore = actionScheme.getInspectionScheme().getMetadataStore();
        this.legacyActionInstantiationScheme = actionScheme.getLegacyInstantiationScheme();
        this.parametersPropertyWalker = parameterScheme.getInspectionScheme().getPropertyWalker();
        this.domainObjectProjectStateHandler = domainObjectProjectStateHandler;
    }

    @Override
    public ArtifactTransformRegistration create(ImmutableAttributes from, ImmutableAttributes to, Class<? extends TransformAction> implementation, @Nullable TransformParameters parameterObject) {
        List<String> validationMessages = new ArrayList<>();
        TypeMetadata actionMetadata = actionMetadataStore.getTypeMetadata(implementation);
        DefaultParameterValidationContext parameterValidationContext = new DefaultParameterValidationContext(validationMessages);
        actionMetadata.collectValidationFailures(null, parameterValidationContext);
        boolean cacheable = implementation.isAnnotationPresent(CacheableTransform.class);

        // Should retain this on the metadata rather than calculate on each invocation
        Class<? extends FileNormalizer> inputArtifactNormalizer = null;
        Class<? extends FileNormalizer> dependenciesNormalizer = null;
        for (PropertyMetadata propertyMetadata : actionMetadata.getPropertiesMetadata()) {
            Class<? extends Annotation> propertyType = propertyMetadata.getPropertyType();
            if (propertyType.equals(InputArtifact.class)) {
                // Should ask the annotation handler to figure this out instead
                NormalizerCollectingVisitor visitor = new NormalizerCollectingVisitor();
                actionMetadata.getAnnotationHandlerFor(propertyMetadata).visitPropertyValue(propertyMetadata.getPropertyName(), null, propertyMetadata, visitor, null);
                inputArtifactNormalizer = visitor.normalizer;
                DefaultTransformer.validateInputFileNormalizer(propertyMetadata.getPropertyName(), inputArtifactNormalizer, cacheable, parameterValidationContext);
            } else if (propertyType.equals(InputArtifactDependencies.class)) {
                NormalizerCollectingVisitor visitor = new NormalizerCollectingVisitor();
                actionMetadata.getAnnotationHandlerFor(propertyMetadata).visitPropertyValue(propertyMetadata.getPropertyName(), null, propertyMetadata, visitor, null);
                dependenciesNormalizer = visitor.normalizer;
                DefaultTransformer.validateInputFileNormalizer(propertyMetadata.getPropertyName(), dependenciesNormalizer, cacheable, parameterValidationContext);
            }
        }
        if (!validationMessages.isEmpty()) {
            throw new DefaultMultiCauseException(
                String.format(validationMessages.size() == 1 ? "A problem was found with the configuration of %s." : "Some problems were found with the configuration of %s.", ModelType.of(implementation).getDisplayName()),
                validationMessages.stream().sorted().map(InvalidUserDataException::new).collect(Collectors.toList()));
        }
        Transformer transformer = new DefaultTransformer(
            implementation,
            parameterObject,
            null,
            from,
            FileParameterUtils.normalizerOrDefault(inputArtifactNormalizer),
            FileParameterUtils.normalizerOrDefault(dependenciesNormalizer),
            cacheable,
            buildOperationExecutor,
            classLoaderHierarchyHasher,
            isolatableFactory,
            valueSnapshotter,
            fileCollectionFactory,
            parametersPropertyWalker,
            actionInstantiationScheme);

        return new DefaultArtifactTransformRegistration(from, to, new TransformationStep(transformer, transformerInvocationFactory, domainObjectProjectStateHandler, fileCollectionFingerprinterRegistry));
    }

    @Override
    @SuppressWarnings("deprecation")
    public ArtifactTransformRegistration create(ImmutableAttributes from, ImmutableAttributes to, Class<? extends ArtifactTransform> implementation, Object[] params) {
        Transformer transformer = new LegacyTransformer(implementation, params, legacyActionInstantiationScheme, from, classLoaderHierarchyHasher, isolatableFactory);
        return new DefaultArtifactTransformRegistration(from, to, new TransformationStep(transformer, transformerInvocationFactory, domainObjectProjectStateHandler, fileCollectionFingerprinterRegistry));
    }

    private static class DefaultArtifactTransformRegistration implements ArtifactTransformRegistration {
        private final ImmutableAttributes from;
        private final ImmutableAttributes to;
        private final TransformationStep transformationStep;

        public DefaultArtifactTransformRegistration(ImmutableAttributes from, ImmutableAttributes to, TransformationStep transformationStep) {
            this.from = from;
            this.to = to;
            this.transformationStep = transformationStep;
        }

        @Override
        public AttributeContainerInternal getFrom() {
            return from;
        }

        @Override
        public AttributeContainerInternal getTo() {
            return to;
        }

        @Override
        public TransformationStep getTransformationStep() {
            return transformationStep;
        }
    }

    private static class NormalizerCollectingVisitor extends PropertyVisitor.Adapter {
        private Class<? extends FileNormalizer> normalizer;

        @Override
        public void visitInputFileProperty(String propertyName, boolean optional, boolean skipWhenEmpty, boolean incremental, @Nullable Class<? extends FileNormalizer> fileNormalizer, PropertyValue value, InputFilePropertyType filePropertyType) {
            this.normalizer = fileNormalizer;
        }
    }
}
