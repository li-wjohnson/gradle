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

package org.gradle.execution.plan

import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.Destroys
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.composite.internal.IncludedBuildTaskGraph
import org.gradle.internal.nativeintegration.filesystem.FileSystem
import org.gradle.internal.resources.DefaultResourceLockCoordinationService
import org.gradle.internal.resources.ResourceLock
import org.gradle.internal.resources.ResourceLockState
import org.gradle.internal.resources.SharedResourceLeaseRegistry
import org.gradle.internal.work.WorkerLeaseRegistry
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.test.fixtures.AbstractProjectBuilderSpec
import org.gradle.test.fixtures.file.TestFile
import org.gradle.testfixtures.internal.NativeServicesTestFixture
import org.gradle.util.Path
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Issue
import spock.lang.Unroll

import static org.gradle.util.TestUtil.createChildProject

class DefaultExecutionPlanParallelTest extends AbstractProjectBuilderSpec {

    FileSystem fs = NativeServicesTestFixture.instance.get(FileSystem)

    DefaultExecutionPlan executionPlan
    def lockSetup = new LockSetup()

    def setup() {
        def taskNodeFactory = new TaskNodeFactory(project.gradle, Stub(IncludedBuildTaskGraph))
        def dependencyResolver = new TaskDependencyResolver([new TaskNodeDependencyResolver(taskNodeFactory)])
        def coordinationService = new DefaultResourceLockCoordinationService()
        def sharedResourceLeaseRegistry = new SharedResourceLeaseRegistry(coordinationService)
        executionPlan = new DefaultExecutionPlan(lockSetup.workerLeaseService, project.gradle, taskNodeFactory, dependencyResolver, sharedResourceLeaseRegistry)
    }

    def "multiple tasks with async work from the same project can run in parallel"() {
        given:
        def foo = project.task("foo", type: Async)
        def bar = project.task("bar", type: Async)
        def baz = project.task("baz", type: Async)

        when:
        addToGraphAndPopulate(foo, bar, baz)

        def executedTasks = [selectNextTask(), selectNextTask(), selectNextTask()] as Set
        then:
        executedTasks == [bar, baz, foo] as Set
    }

    def "one non-async task per project is allowed"() {
        given:
        //2 projects, 2 non parallelizable tasks each
        def projectA = createChildProject(project, "a")
        def projectB = createChildProject(project, "b")

        def fooA = projectA.task("foo")
        def barA = projectA.task("bar")

        def fooB = projectB.task("foo")
        def barB = projectB.task("bar")

        when:
        addToGraphAndPopulate(fooA, barA, fooB, barB)
        def taskNode1 = selectNextTaskNode()
        def taskNode2 = selectNextTaskNode()

        then:
        lockSetup.lockedProjects.size() == 2
        taskNode1.task.project != taskNode2.task.project
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(taskNode1)
        executionPlan.finishedExecuting(taskNode2)
        def taskNode3 = selectNextTaskNode()
        def taskNode4 = selectNextTaskNode()

        then:
        lockSetup.lockedProjects.size() == 2
        taskNode3.task.project != taskNode4.task.project
    }

    def "a non-async task can start while an async task from the same project is waiting for work to complete"() {
        given:
        def bar = project.task("bar", type: Async)
        def foo = project.task("foo")

        when:
        addToGraphAndPopulate(foo, bar)
        def asyncTask = selectNextTask()
        then:
        asyncTask == bar

        when:
        def nonAsyncTask = selectNextTask()

        then:
        nonAsyncTask == foo
    }

    def "an async task does not start while a non-async task from the same project is running"() {
        given:
        def a = project.task("a")
        def b = project.task("b", type: Async)

        when:
        addToGraphAndPopulate(a, b)
        def nonAsyncTaskNode = selectNextTaskNode()
        then:
        nonAsyncTaskNode.task == a
        selectNextTask() == null
        lockSetup.lockedProjects.size() == 1

        when:
        executionPlan.finishedExecuting(nonAsyncTaskNode)
        def asyncTask = selectNextTask()
        then:
        asyncTask == b
        lockSetup.lockedProjects.empty
    }

    @Unroll
    def "two tasks with #relation relationship are not executed in parallel"() {
        given:
        Task a = project.task("a", type: Async)
        Task b = project.task("b", type: Async)."${relation}"(a)

        when:
        addToGraphAndPopulate(a, b)
        def firstTaskNode = selectNextTaskNode()
        then:
        firstTaskNode.task == a
        selectNextTask() == null
        lockSetup.lockedProjects.empty

        when:
        executionPlan.finishedExecuting(firstTaskNode)
        def secondTask = selectNextTask()
        then:
        secondTask == b

        where:
        relation << ["dependsOn", "mustRunAfter"]
    }

    def "two tasks with should run after ordering are executed in parallel" () {
        given:
        def a = project.task("a", type: Async)
        def b = project.task("b", type: Async)
        b.shouldRunAfter(a)

        when:
        addToGraphAndPopulate(a, b)

        def firstTask = selectNextTask()
        def secondTask = selectNextTask()
        then:
        firstTask == a
        secondTask == b
    }

    def "task is not available for execution until all of its dependencies that are executed in parallel complete"() {
        given:
        Task a = project.task("a", type: Async)
        Task b = project.task("b", type: Async)
        Task c = project.task("c", type: Async).dependsOn(a, b)

        when:
        addToGraphAndPopulate(a,b,c)

        def firstTaskNode = selectNextTaskNode()
        def secondTaskNode = selectNextTaskNode()
        then:
        [firstTaskNode, secondTaskNode]*.task as Set == [a, b] as Set
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(firstTaskNode)
        then:
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(secondTaskNode)
        then:
        selectNextTask() == c

    }

    def "two tasks that have the same file in outputs are not executed in parallel"() {
        def sharedFile = file("output")

        given:
        Task a = project.task("a", type: AsyncWithOutputFile) {
            outputFile = sharedFile
        }
        Task b = project.task("b", type: AsyncWithOutputFile) {
            delegate.outputFile = sharedFile
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "two tasks that have the same file as output and local state are not executed in parallel"() {
        def sharedFile = file("output")

        given:
        Task a = project.task("a", type: AsyncWithOutputFile) {
            outputFile = sharedFile
        }
        Task b = project.task("b", type: AsyncWithLocalState) {
            localStateFile = sharedFile
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that writes into a directory that is an output of a running task is not started"() {
        given:
        Task a = project.task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }
        Task b = project.task("b", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir").file("outputSubdir").file("output")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that writes into an ancestor directory of a file that is an output of a running task is not started"() {
        given:
        Task a = project.task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir").file("outputSubdir").file("output")
        }
        Task b = project.task("b", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    @Requires(TestPrecondition.SYMLINKS)
    def "a task that writes into a symlink that overlaps with output of currently running task is not started"() {
        given:
        def taskOutput = file("outputDir").createDir()
        def symlink = file("symlink")
        fs.createSymbolicLink(symlink, taskOutput)

        and:
        Task a = project.task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = taskOutput
        }
        Task b = project.task("b", type: AsyncWithOutputFile) {
            outputFile = symlink.file("fileUnderSymlink")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    private void tasksAreNotExecutedInParallel(Task first, Task second) {
        addToGraphAndPopulate(first, second)

        def firstTaskNode = selectNextTaskNode()

        assert selectNextTask() == null
        assert lockSetup.lockedProjects.empty

        executionPlan.finishedExecuting(firstTaskNode)
        def secondTask = selectNextTask()

        assert [firstTaskNode.task, secondTask] as Set == [first, second] as Set

    }

    @Requires(TestPrecondition.SYMLINKS)
    def "a task that writes into a symlink of a shared output dir of currently running task is not started"() {
        given:
        def taskOutput = file("outputDir").createDir()
        def symlink = file("symlink")
        fs.createSymbolicLink(symlink, taskOutput)

        // Deleting any file clears the internal canonicalisation cache.
        // This allows the created symlink to be actually resolved.
        // See java.io.UnixFileSystem#cache.
        file("tmp").createFile().delete()

        and:
        Task a = project.task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = taskOutput
        }
        Task b = project.task("b", type: AsyncWithOutputDirectory) {
            outputDirectory = symlink
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)

        cleanup:
        assert symlink.delete()
    }

    @Requires(TestPrecondition.SYMLINKS)
    def "a task that stores local state into a symlink of a shared output dir of currently running task is not started"() {
        given:
        def taskOutput = file("outputDir").createDir()
        def symlink = file("symlink")
        fs.createSymbolicLink(symlink, taskOutput)

        // Deleting any file clears the internal canonicalisation cache.
        // This allows the created symlink to be actually resolved.
        // See java.io.UnixFileSystem#cache.
        file("tmp").createFile().delete()

        and:
        Task a = project.task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = taskOutput
        }
        Task b = project.task("b", type: AsyncWithLocalState) {
            localStateFile = symlink
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)

        cleanup:
        assert symlink.delete()
    }

    def "tasks from two different projects that have the same file in outputs are not executed in parallel"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputFile) {
            outputFile = file("output")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithOutputFile) {
            outputFile = file("output")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task from different project that writes into a directory that is an output of currently running task is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithOutputFile) {
            outputFile = file("outputDir").file("outputSubdir").file("output")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that destroys a directory that is an output of a currently running task is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("outputDir")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that writes to a directory that is being destroyed by a currently running task is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithDestroysFile) {
            destroysFile = file("outputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that destroys an ancestor directory of an output of a currently running task is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir").file("outputSubdir").file("output")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("outputDir")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that writes to an ancestor of a directory that is being destroyed by a currently running task is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithDestroysFile) {
            destroysFile = file("outputDir").file("outputSubdir").file("output")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithOutputDirectory) {
            outputDirectory = file("outputDir")
        }

        expect:
        tasksAreNotExecutedInParallel(a, b)
    }

    def "a task that destroys an intermediate input is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"


        expect:
        destroyerRunsLast(a, c, b)
    }

    private void destroyerRunsLast(Task producer, Task consumer, Task destroyer) {
        addToGraphAndPopulate(producer, destroyer, consumer)

        def producerInfo = selectNextTaskNode()

        assert producerInfo.task == producer
        assert selectNextTask() == null

        executionPlan.finishedExecuting(producerInfo)
        def consumerInfo = selectNextTaskNode()

        assert consumerInfo.task == consumer
        assert selectNextTask() == null

        executionPlan.finishedExecuting(consumerInfo)
        def destroyerInfo = selectNextTaskNode()

        assert destroyerInfo.task == destroyer
    }

    def "a task that destroys an ancestor of an intermediate input is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir").file("inputSubdir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir").file("inputSubdir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"

        expect:
        destroyerRunsLast(a, c, b)
    }

    def "a task that destroys a descendant of an intermediate input is not started"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir").file("inputSubdir").file("foo")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"

        expect:
        destroyerRunsLast(a, c, b)
    }

    def "a task that destroys an intermediate input can be started if it's ordered first"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"

        expect:
        destroyerRunsFirst(a, c, b)
    }

    private void destroyerRunsFirst(Task producer, Task consumer, Task destroyer) {
        addToGraphAndPopulate(destroyer)
        addToGraphAndPopulate(producer, consumer)

        def destroyerInfo = selectNextTaskNode()

        assert destroyerInfo.task == destroyer
        assert selectNextTask() == null

        executionPlan.finishedExecuting(destroyerInfo)
        def producerInfo = selectNextTaskNode()

        assert producerInfo.task == producer
        assert selectNextTask() == null

        executionPlan.finishedExecuting(producerInfo)
        def consumerInfo = selectNextTaskNode()

        assert consumerInfo.task == consumer
    }

    def "a task that destroys an ancestor of an intermediate input can be started if it's ordered first"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir").file("inputSubdir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir").file("inputSubdir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"

        expect:
        destroyerRunsFirst(a, c, b)
    }

    def "a task that destroys a descendant of an intermediate input can be started if it's ordered first"() {
        given:
        Task a = createChildProject(project, "a").task("a", type: AsyncWithOutputDirectory) {
            outputDirectory = file("inputDir")
        }
        Task b = createChildProject(project, "b").task("b", type: AsyncWithDestroysFile) {
            destroysFile = file("inputDir").file("inputSubdir").file("foo")
        }
        Task c = createChildProject(project, "c").task("c", type: AsyncWithInputDirectory) {
            inputDirectory = file("inputDir")
            dependsOn a
        }

        file("inputDir").file("inputSubdir").file("foo").file("bar") << "bar"

        expect:
        destroyerRunsFirst(a, c, b)
    }

    def "finalizer runs after the last task to be finalized"() {
        def projectA = createChildProject(project, "a")
        given:
        Task finalizer = projectA.task("finalizer")
        Task a = projectA.task("a", type: Async)
        Task b = createChildProject(project, "b").task("b", type: Async)
        [a, b]*.finalizedBy(finalizer)

        when:
        addToGraphAndPopulate(a, b)
        def firstInfo = selectNextTaskNode()
        def secondInfo = selectNextTaskNode()

        then:
        firstInfo.task == a
        secondInfo.task == b
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(firstInfo)

        then:
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(secondInfo)
        def finalizerInfo = selectNextTaskNode()

        then:
        finalizerInfo.task == finalizer
    }

    @Issue("https://github.com/gradle/gradle/issues/8253")
    def "dependency of dependency of finalizer is scheduled when another task depends on the dependency"() {
        given:
        Task finalizer = project.task("finalizer", type: Async)
        Task finalized = project.task("finalized", type: Async)
        Task dependency = project.task("dependency", type: Async)
        Task otherTaskWithDependency = project.task("otherTaskWithDependency", type: Async)
        Task dependencyOfDependency = project.task("dependencyOfDependency", type: Async)
        finalized.finalizedBy(finalizer)
        finalizer.dependsOn(dependency)
        dependency.dependsOn(dependencyOfDependency)
        otherTaskWithDependency.dependsOn(dependency)

        when:
        executionPlan.addEntryTasks([finalized])
        executionPlan.addEntryTasks([otherTaskWithDependency])
        executionPlan.determineExecutionPlan()

        and:
        def finalizedNode = selectNextTaskNode()
        def dependencyOfDependencyNode = selectNextTaskNode()
        then:
        finalizedNode.task == finalized
        dependencyOfDependencyNode.task == dependencyOfDependency
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(dependencyOfDependencyNode)
        def dependencyNode = selectNextTaskNode()
        then:
        dependencyNode.task == dependency
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(dependencyNode)
        def otherTaskWithDependencyNode = selectNextTaskNode()
        then:
        otherTaskWithDependencyNode.task == otherTaskWithDependency
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(otherTaskWithDependencyNode)
        then:
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(finalizedNode)
        then:
        selectNextTask() == finalizer
        selectNextTask() == null
    }

    def "must run after is sometimes not respected for finalizers"() {
        Task dependency = project.task("dependency", type: Async)
        Task finalized = project.task("finalized", type: Async)
        Task finalizer = project.task("finalizer", type: Async)
        Task mustRunAfter = project.task("mustRunAfter", type: Async)

        finalized.dependsOn(dependency)
        finalized.finalizedBy(finalizer)
        mustRunAfter.mustRunAfter(finalizer)

        when:
        executionPlan.addEntryTasks([finalized])
        executionPlan.addEntryTasks([mustRunAfter])
        executionPlan.determineExecutionPlan()

        and:
        def dependencyNode = selectNextTaskNode()
        def mustRunAfterNode = selectNextTaskNode()
        then:
        selectNextTaskNode() == null
        dependencyNode.task == dependency
        mustRunAfterNode.task == mustRunAfter

        when:
        executionPlan.finishedExecuting(dependencyNode)

        def finalizedNode = selectNextTaskNode()
        then:
        selectNextTaskNode() == null
        finalizedNode.task == finalized

        when:
        executionPlan.finishedExecuting(finalizedNode)

        def finalizerNode = selectNextTaskNode()
        then:
        selectNextTaskNode() == null
        finalizerNode.task == finalizer
    }

    def "must run after is sometimes respected for finalizers"() {
        Task dependency = project.task("dependency", type: Async)
        Task finalized = project.task("finalized", type: Async)
        Task finalizer = project.task("finalizer", type: Async)
        Task mustRunAfter = project.task("mustRunAfter", type: Async)

        finalized.dependsOn(dependency)
        finalized.finalizedBy(finalizer)
        mustRunAfter.mustRunAfter(finalizer)

        when:
        executionPlan.addEntryTasks([finalized])
        executionPlan.addEntryTasks([mustRunAfter])
        executionPlan.determineExecutionPlan()

        and:
        def dependencyNode = selectNextTaskNode()
        then:
        dependencyNode.task == dependency

        when:
        executionPlan.finishedExecuting(dependencyNode)

        def finalizedNode = selectNextTaskNode()
        then:
        finalizedNode.task == finalized

        when:
        executionPlan.finishedExecuting(finalizedNode)

        def finalizerNode = selectNextTaskNode()
        then:
        selectNextTaskNode() == null
        finalizerNode.task == finalizer

        when:
        executionPlan.finishedExecuting(finalizerNode)
        then:
        selectNextTask() == mustRunAfter
        selectNextTask() == null
    }

    def "handles an exception while walking the task graph when an enforced task is present"() {
        given:
        Task finalizer = project.task("finalizer", type: BrokenTask)
        Task finalized = project.task("finalized")
        finalized.finalizedBy finalizer

        when:
        addToGraphAndPopulate(finalized)
        def finalizedInfo = selectNextTaskNode()

        then:
        finalizedInfo.task == finalized
        selectNextTask() == null

        when:
        executionPlan.finishedExecuting(finalizedInfo)
        selectNextTask()

        then:
        Exception e = thrown()
        e.message.contains("Execution failed for task ':finalizer'")

        when:
        lockSetup.currentState.releaseLocks()
        executionPlan.abortAllAndFail(e)

        then:
        executionPlan.getNode(finalized).isSuccessful()
        executionPlan.getNode(finalizer).state == Node.ExecutionState.SKIPPED
    }

    private void addToGraphAndPopulate(Task... tasks) {
        executionPlan.addEntryTasks(Arrays.asList(tasks))
        executionPlan.determineExecutionPlan()
    }

    TestFile file(String path) {
        temporaryFolder.file(path)
    }

    static class Async extends DefaultTask {}

    static class AsyncWithOutputFile extends Async {
        @OutputFile
        File outputFile
    }

    static class AsyncWithOutputDirectory extends Async {
        @OutputDirectory
        File outputDirectory
    }

    static class AsyncWithDestroysFile extends Async {
        @Destroys
        File destroysFile
    }

    static class AsyncWithLocalState extends Async {
        @LocalState
        File localStateFile
    }

    static class AsyncWithInputFile extends Async {
        @InputFile
        File inputFile
    }

    static class AsyncWithInputDirectory extends Async {
        @InputDirectory
        File inputDirectory
    }

    static class BrokenTask extends DefaultTask {
        @OutputFiles
        FileCollection getOutputFiles() {
            throw new Exception("BOOM!")
        }
    }

    private TaskInternal selectNextTask() {
        selectNextTaskNode()?.task
    }

    private TaskNode selectNextTaskNode() {
        def nextTaskNode = executionPlan.selectNext(lockSetup.workerLease, lockSetup.createResourceLockState())
        if (nextTaskNode?.task instanceof Async) {
            def project = (ProjectInternal) nextTaskNode.task.project
            lockSetup.projectLocks.get(project.identityPath).unlock()
        }
        return nextTaskNode
    }

    class LockSetup {
        int availableWorkerLeases = 5
        Set<Path> lockedProjects = [] as Set
        Map<Path, ResourceLock> projectLocks = [:]
        ResourceLockState currentState

        ResourceLockState createResourceLockState() {
            currentState = new ResourceLockState() {
                private Set<ResourceLock> lockedResources = [] as Set

                @Override
                void registerLocked(ResourceLock resourceLock) {
                    lockedResources.add(resourceLock)
                }

                @Override
                void registerUnlocked(ResourceLock resourceLock) {
                }

                @Override
                void releaseLocks() {
                    lockedResources.each {
                        it.unlock()
                    }
                    lockedResources.clear()
                }
            }
            return currentState
        }
        WorkerLeaseService workerLeaseService = [
            getProjectLock: { Path gradlePath, Path projectPath ->
                if (!projectLocks.containsKey(projectPath)) {
                    projectLocks[projectPath] = new StubProjectLock(lockedProjects, projectPath)
                }
                return projectLocks[projectPath]
            }
        ] as WorkerLeaseService

        WorkerLeaseRegistry.WorkerLease getWorkerLease() {
            return new StubWorkerLease(this)
        }
    }

    class StubProjectLock implements ResourceLock {
        boolean locked = false
        private final String projectPath
        private final Set<Path> lockedProjects

        StubProjectLock(Set<Path> lockedProjects, Path projectPath) {
            this.lockedProjects = lockedProjects
            this.projectPath = projectPath
        }

        @Override
        boolean isLockedByCurrentThread() {
            return locked
        }

        @Override
        boolean tryLock() {
            if (!locked) {
                locked = true
                lockSetup.currentState?.registerLocked(this)
                lockedProjects.add(projectPath)
                return true
            }
            return false
        }

        @Override
        void unlock() {
            if (locked) {
                assert lockedProjects.contains(projectPath)
                lockedProjects.remove(projectPath)
                locked = false
            }
        }

        @Override
        String getDisplayName() { "Project lock for ${projectPath}" }
    }

    class StubWorkerLease implements WorkerLeaseRegistry.WorkerLease {
        boolean locked = false
        private final LockSetup lockSetup

        StubWorkerLease(LockSetup lockSetup) {
            this.lockSetup = lockSetup
        }

        @Override
        WorkerLeaseRegistry.WorkerLease createChild() { null }

        @Override
        WorkerLeaseRegistry.WorkerLeaseCompletion startChild() { null }

        @Override
        boolean isLockedByCurrentThread() {
            return locked
        }

        @Override
        boolean tryLock() {
            if (!locked && lockSetup.availableWorkerLeases > 0) {
                lockSetup.availableWorkerLeases--
                lockSetup.currentState?.registerLocked(this)
                locked = true
            }
            return locked
        }

        @Override
        void unlock() {
            if (locked) {
                locked = false
                lockSetup.availableWorkerLeases++
            }
        }

        @Override
        String getDisplayName() { return "Mock worker lease" }
    }
}
