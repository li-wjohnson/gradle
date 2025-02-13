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

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.GeneratedSubclasses
import org.gradle.api.internal.TaskInputsInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.project.ProjectState
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.api.internal.tasks.properties.InputFilePropertyType
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType
import org.gradle.api.internal.tasks.properties.PropertyValue
import org.gradle.api.internal.tasks.properties.PropertyVisitor
import org.gradle.api.tasks.FileNormalizer
import org.gradle.execution.plan.LocalTaskNode
import org.gradle.execution.plan.TaskNodeFactory
import org.gradle.instantexecution.extensions.uncheckedCast
import org.gradle.instantexecution.runToCompletion
import org.gradle.instantexecution.serialization.Codec
import org.gradle.instantexecution.serialization.IsolateContext
import org.gradle.instantexecution.serialization.IsolateOwner
import org.gradle.instantexecution.serialization.MutableIsolateContext
import org.gradle.instantexecution.serialization.PropertyKind
import org.gradle.instantexecution.serialization.PropertyTrace
import org.gradle.instantexecution.serialization.ReadContext
import org.gradle.instantexecution.serialization.WriteContext
import org.gradle.instantexecution.serialization.beans.BeanPropertyWriter
import org.gradle.instantexecution.serialization.beans.readEachProperty
import org.gradle.instantexecution.serialization.beans.writeNextProperty
import org.gradle.instantexecution.serialization.beans.writingProperties
import org.gradle.instantexecution.serialization.readEnum
import org.gradle.instantexecution.serialization.withIsolate
import org.gradle.instantexecution.serialization.withPropertyTrace
import org.gradle.instantexecution.serialization.writeEnum


class TaskNodeCodec(
    private val projectStateRegistry: ProjectStateRegistry,
    private val userTypesCodec: Codec<Any?>,
    private val taskNodeFactory: TaskNodeFactory
) : Codec<LocalTaskNode> {

    override suspend fun WriteContext.encode(value: LocalTaskNode) {
        val task = value.task
        try {
            runToCompletionWithMutableStateOf(task.project) {
                writeTask(task)
            }
        } catch (e: Exception) {
            throw GradleException("Could not save state of $task.", e)
        }
    }

    override suspend fun ReadContext.decode(): LocalTaskNode? {
        val task = readTask()
        return taskNodeFactory.getOrCreateNode(task) as LocalTaskNode
    }

    private
    suspend fun WriteContext.writeTask(task: Task) {
        val taskType = GeneratedSubclasses.unpack(task.javaClass)
        writeClass(taskType)
        writeString(task.project.path)
        writeString(task.name)

        withTaskOf(taskType, task, userTypesCodec) {
            beanStateWriterFor(task.javaClass).run {
                writeStateOf(task)
                writeRegisteredPropertiesOf(task, this as BeanPropertyWriter)
            }
        }
    }

    private
    suspend fun ReadContext.readTask(): Task {
        val taskType = readClass().asSubclass(Task::class.java)
        val projectPath = readString()
        val taskName = readString()

        val task = createTask(projectPath, taskName, taskType)

        withTaskOf(taskType, task, userTypesCodec) {
            beanStateReaderFor(task.javaClass).run {
                readStateOf(task)
                readRegisteredPropertiesOf(task)
            }
        }

        return task
    }

    /**
     * Runs the suspending [block] to completion against the [public mutable state][ProjectState.withMutableState] of [project].
     */
    private
    fun runToCompletionWithMutableStateOf(project: Project, block: suspend () -> Unit) {
        projectStateRegistry.stateFor(project).withMutableState {
            runToCompletion(block)
        }
    }
}


private
inline fun <T> T.withTaskOf(
    taskType: Class<*>,
    task: Task,
    codec: Codec<Any?>,
    action: () -> Unit
) where T : IsolateContext, T : MutableIsolateContext {
    withIsolate(IsolateOwner.OwnerTask(task), codec) {
        withPropertyTrace(PropertyTrace.Task(taskType, task.path)) {
            action()
        }
    }
}


private
sealed class RegisteredProperty {

    data class Input(
        val propertyName: String,
        val propertyValue: PropertyValue,
        val optional: Boolean
    ) : RegisteredProperty()

    data class InputFile(
        val propertyName: String,
        val propertyValue: PropertyValue,
        val optional: Boolean,
        val filePropertyType: InputFilePropertyType,
        val skipWhenEmpty: Boolean,
        val incremental: Boolean,
        val fileNormalizer: Class<out FileNormalizer>?
    ) : RegisteredProperty()

    data class OutputFile(
        val propertyName: String,
        val propertyValue: PropertyValue,
        val optional: Boolean,
        val filePropertyType: OutputFilePropertyType
    ) : RegisteredProperty()
}


private
suspend fun WriteContext.writeRegisteredPropertiesOf(
    task: Task,
    propertyWriter: BeanPropertyWriter
) = propertyWriter.run {

    suspend fun writeProperty(propertyName: String, propertyValue: PropertyValue, kind: PropertyKind): Boolean {
        val value = unpack(propertyValue.call()) ?: return false
        return writeNextProperty(propertyName, value, kind)
    }

    suspend fun writeInputProperty(propertyName: String, propertyValue: PropertyValue): Boolean =
        writeProperty(propertyName, propertyValue, PropertyKind.InputProperty)

    suspend fun writeOutputProperty(propertyName: String, propertyValue: PropertyValue): Boolean =
        writeProperty(propertyName, propertyValue, PropertyKind.OutputProperty)

    writingProperties {
        val properties = collectRegisteredInputsOf(task)
        properties.forEach { property ->
            property.run {
                when (this) {
                    is RegisteredProperty.InputFile -> {
                        if (writeInputProperty(propertyName, propertyValue)) {
                            writeBoolean(optional)
                            writeBoolean(true)
                            writeEnum(filePropertyType)
                            writeBoolean(skipWhenEmpty)
                            writeClass(fileNormalizer!!)
                        }
                    }
                    is RegisteredProperty.Input -> {
                        if (writeInputProperty(propertyName, propertyValue)) {
                            writeBoolean(optional)
                            writeBoolean(false)
                        }
                    }
                }
            }
        }
    }

    writingProperties {
        val properties = collectRegisteredOutputsOf(task)
        properties.forEach {
            it.run {
                if (writeOutputProperty(propertyName, propertyValue)) {
                    writeBoolean(optional)
                    writeEnum(filePropertyType)
                }
            }
        }
    }
}


private
fun collectRegisteredOutputsOf(task: Task): List<RegisteredProperty.OutputFile> {

    val properties = mutableListOf<RegisteredProperty.OutputFile>()

    (task.outputs as TaskOutputsInternal).visitRegisteredProperties(object : PropertyVisitor.Adapter() {

        override fun visitOutputFileProperty(
            propertyName: String,
            optional: Boolean,
            value: PropertyValue,
            filePropertyType: OutputFilePropertyType
        ) {
            properties.add(
                RegisteredProperty.OutputFile(
                    propertyName,
                    value,
                    optional,
                    filePropertyType
                )
            )
        }
    })
    return properties
}


private
fun collectRegisteredInputsOf(task: Task): List<RegisteredProperty> {

    val properties = mutableListOf<RegisteredProperty>()

    (task.inputs as TaskInputsInternal).visitRegisteredProperties(object : PropertyVisitor.Adapter() {

        override fun visitInputFileProperty(
            propertyName: String,
            optional: Boolean,
            skipWhenEmpty: Boolean,
            incremental: Boolean,
            fileNormalizer: Class<out FileNormalizer>?,
            propertyValue: PropertyValue,
            filePropertyType: InputFilePropertyType
        ) {
            properties.add(
                RegisteredProperty.InputFile(
                    propertyName,
                    propertyValue,
                    optional,
                    filePropertyType,
                    skipWhenEmpty,
                    incremental,
                    fileNormalizer
                )
            )
        }

        override fun visitInputProperty(
            propertyName: String,
            propertyValue: PropertyValue,
            optional: Boolean
        ) {
            properties.add(
                RegisteredProperty.Input(
                    propertyName,
                    propertyValue,
                    optional
                )
            )
        }
    })
    return properties
}


private
suspend fun ReadContext.readRegisteredPropertiesOf(task: Task) {
    readInputPropertiesOf(task)
    readOutputPropertiesOf(task)
}


private
suspend fun ReadContext.readInputPropertiesOf(task: Task) =
    readEachProperty(PropertyKind.InputProperty) { propertyName, propertyValue ->
        val optional = readBoolean()
        val isFileInputProperty = readBoolean()
        require(propertyValue != null)
        when {
            isFileInputProperty -> {
                val filePropertyType = readEnum<InputFilePropertyType>()
                val skipWhenEmpty = readBoolean()
                val normalizer = readClass()
                task.inputs.run {
                    when (filePropertyType) {
                        InputFilePropertyType.FILE -> file(propertyValue)
                        InputFilePropertyType.DIRECTORY -> dir(propertyValue)
                        InputFilePropertyType.FILES -> files(propertyValue)
                    }
                }.run {
                    withPropertyName(propertyName)
                    optional(optional)
                    skipWhenEmpty(skipWhenEmpty)
                    withNormalizer(normalizer.uncheckedCast())
                }
            }
            else -> {
                task.inputs
                    .property(propertyName, propertyValue)
                    .optional(optional)
            }
        }
    }


private
suspend fun ReadContext.readOutputPropertiesOf(task: Task) =
    readEachProperty(PropertyKind.OutputProperty) { propertyName, propertyValue ->
        val optional = readBoolean()
        val filePropertyType = readEnum<OutputFilePropertyType>()
        require(propertyValue != null)
        task.outputs.run {
            when (filePropertyType) {
                OutputFilePropertyType.DIRECTORY -> dir(propertyValue)
                OutputFilePropertyType.DIRECTORIES -> dirs(propertyValue)
                OutputFilePropertyType.FILE -> file(propertyValue)
                OutputFilePropertyType.FILES -> files(propertyValue)
            }
        }.run {
            withPropertyName(propertyName)
            optional(optional)
        }
    }


private
fun ReadContext.createTask(projectPath: String, taskName: String, taskClass: Class<out Task>) =
    getProject(projectPath).tasks.createWithoutConstructor(taskName, taskClass)
