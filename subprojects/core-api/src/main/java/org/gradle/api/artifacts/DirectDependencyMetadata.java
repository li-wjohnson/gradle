/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.artifacts;

import org.gradle.api.Incubating;

/**
 * Describes a dependency declared in a resolved component's metadata, which typically originates from
 * a component descriptor (Gradle metadata file, Ivy file, Maven POM). This interface can be used to adjust
 * a dependency's properties via metadata rules (see {@link org.gradle.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.5
 */
public interface DirectDependencyMetadata extends DependencyMetadata<DirectDependencyMetadata> {

    /**
     * Inherit version constraints with {@link VersionConstraint#isForSubgraph()} from the target module.
     * For this, the version constraint of this dependency needs to strictly point at one version.
     *
     * @since 5.7
     */
    @Incubating
    void inheritConstraints();

    /**
     * Resets the {@link #isInheriting()} state of this dependency.
     *
     * @since 5.7
     */
    @Incubating
    void notInheritConstraints();

    /**
     * Are the {@link VersionConstraint#isForSubgraph()} dependency constraints of the target module inherited?
     *
     * @since 5.7
     */
    @Incubating
    boolean isInheriting();

}
