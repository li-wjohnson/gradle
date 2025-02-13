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

package org.gradle.instantexecution.serialization.codecs

import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.internal.artifacts.transform.ExecutionGraphDependenciesResolver
import org.gradle.api.internal.artifacts.transform.TransformationNode
import org.gradle.api.internal.artifacts.transform.TransformationStep
import org.gradle.instantexecution.serialization.Codec
import org.gradle.instantexecution.serialization.ReadContext
import org.gradle.instantexecution.serialization.WriteContext


internal
abstract class AbstractTransformationNodeCodec<T : TransformationNode> : Codec<T> {
    override suspend fun WriteContext.encode(value: T) {
        val id = sharedIdentities.getId(value)
        if (id != null) {
            writeSmallInt(id)
        } else {
            writeSmallInt(sharedIdentities.putInstance(value))
            doEncode(value)
        }
    }

    override suspend fun ReadContext.decode(): T {
        val id = readSmallInt()
        val instance = sharedIdentities.getInstance(id)
        if (instance != null) {
            return instance as T
        }
        val node = doDecode()
        sharedIdentities.putInstance(id, node)
        return node
    }

    protected
    abstract suspend fun WriteContext.doEncode(value: T)

    protected
    abstract suspend fun ReadContext.doDecode(): T
}


internal
object InitialTransformationNodeCodec : AbstractTransformationNodeCodec<TransformationNode.InitialTransformationNode>() {
    override suspend fun WriteContext.doEncode(value: TransformationNode.InitialTransformationNode) {
        write(value.transformationStep)
        write(value.dependenciesResolver)
        write(value.artifact)
    }

    override suspend fun ReadContext.doDecode(): TransformationNode.InitialTransformationNode {
        val transformationStep = read() as TransformationStep
        val resolver = read() as ExecutionGraphDependenciesResolver
        val artifact = read() as ResolvableArtifact
        return TransformationNode.initial(transformationStep, artifact, resolver)
    }
}


internal
object ChainedTransformationNodeCodec : AbstractTransformationNodeCodec<TransformationNode.ChainedTransformationNode>() {
    override suspend fun WriteContext.doEncode(value: TransformationNode.ChainedTransformationNode) {
        write(value.transformationStep)
        write(value.dependenciesResolver)
        write(value.previousTransformationNode)
    }

    override suspend fun ReadContext.doDecode(): TransformationNode.ChainedTransformationNode {
        val transformationStep = read() as TransformationStep
        val resolver = read() as ExecutionGraphDependenciesResolver
        val previousStep = read() as TransformationNode
        return TransformationNode.chained(transformationStep, previousStep, resolver)
    }
}
