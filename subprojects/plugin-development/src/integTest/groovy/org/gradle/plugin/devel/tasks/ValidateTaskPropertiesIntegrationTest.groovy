/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.plugin.devel.tasks

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.model.ReplacedBy
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.Destroys
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.options.OptionValues
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Unroll

import javax.inject.Inject

class ValidateTaskPropertiesIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        buildFile << """
            apply plugin: "java-gradle-plugin"

            dependencies {
                implementation gradleApi()
            }

            validateTaskProperties {
                failOnWarning = true
            }
        """
    }

    def "detects missing annotations on Java properties"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                // Should be ignored because it's not a getter
                public void getVoid() {
                }

                // Should be ignored because it's not a getter
                public int getWithParameter(int count) {
                    return count;
                }

                public long getter() {
                    return 0L;
                }

                // Ignored because static
                public static int getStatic() {
                    return 0;
                }

                // Ignored because injected
                @javax.inject.Inject
                public org.gradle.api.internal.file.FileResolver getInjected() {
                    throw new UnsupportedOperationException();
                }

                // Valid because it is annotated
                @Input
                public long getGoodTime() {
                    return 0;
                }

                // Valid because it is annotated
                @Nested
                public Options getOptions() {
                    return null;
                }

                // Valid because it is annotated
                @CompileClasspath
                public java.util.List<java.io.File> getClasspath() {
                    return null;
                }

                // Invalid because it has no annotation
                public long getBadTime() {
                    return System.currentTimeMillis();
                }

                // Invalid because it has some other annotation
                @Deprecated
                public String getOldThing() {
                    return null;
                }

                public static class Options {
                    // Valid because it is annotated
                    @Input
                    public int getGoodNested() {
                        return 1;
                    }

                    // Invalid because there is no annotation
                    public int getBadNested() {
                        return -1;
                    }
                }
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Warning: Type 'MyTask': property 'badTime' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Type 'MyTask': property 'oldThing' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Type 'MyTask': property 'ter' is not annotated with an input or output annotation."

        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': property 'oldThing' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': property 'ter' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    @Unroll
    def "task can have property with annotation @#annotation.simpleName"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.model.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                @${application}
                ${type.name} getThing() {
                    return null;
                }
            }
        """

        expect:
        succeeds("validateTaskProperties")

        where:
        annotation        | application                   | type
        Inject            | Inject.name                   | ObjectFactory
        OptionValues      | "${OptionValues.name}(\"a\")" | List
        Internal          | 'Internal'                    | String
        ReplacedBy        | 'ReplacedBy("")'              | String
        Console           | 'Console'                     | Boolean
        Destroys          | 'Destroys'                    | FileCollection
        LocalState        | 'LocalState'                  | FileCollection
        InputFile         | 'InputFile'                   | File
        InputFiles        | 'InputFiles'                  | Set
        InputDirectory    | 'InputDirectory'              | File
        Input             | 'Input'                       | String
        OutputFile        | 'OutputFile'                  | File
        OutputFiles       | 'OutputFiles'                 | Map
        OutputDirectory   | 'OutputDirectory'             | File
        OutputDirectories | 'OutputDirectories'           | Map
        Nested            | 'Nested'                      | List
    }

    @Unroll
    def "task cannot have property with annotation @#annotation.simpleName"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;

            public class MyTask extends DefaultTask {
                @${annotation.simpleName}
                String getThing() {
                    return null;
                }

                @Nested
                Options getOptions() { 
                    return null;
                }

                public static class Options {
                    @${annotation.simpleName}
                    String getNestedThing() {
                        return null;
                    }
                }
            }
        """

        expect:
        fails("validateTaskProperties")
        failure.assertHasDescription("Execution failed for task ':validateTaskProperties'.")
        failure.assertHasCause("Task property validation failed. See")
        failure.assertHasCause("Warning: Type 'MyTask': property 'thing' is annotated with invalid property type @${annotation.simpleName}.")
        failure.assertHasCause("Warning: Type 'MyTask': property 'options.nestedThing' is annotated with invalid property type @${annotation.simpleName}.")

        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'options.nestedThing' is annotated with invalid property type @${annotation.simpleName}.
            Warning: Type 'MyTask': property 'thing' is annotated with invalid property type @${annotation.simpleName}.
        """.stripIndent().trim()

        where:
        annotation                | _
        InputArtifact             | _
        InputArtifactDependencies | _
    }

    def "validates task caching annotations"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;

            @CacheableTransform 
            public class MyTask extends DefaultTask {
                @Nested
                Options getOptions() { 
                    return null;
                }

                @CacheableTask @CacheableTransform 
                public static class Options {
                    @Input
                    String getNestedThing() {
                        return null;
                    }
                }
            }
        """

        expect:
        fails("validateTaskProperties")
        failure.assertHasDescription("Execution failed for task ':validateTaskProperties'.")
        failure.assertHasCause("Task property validation failed. See")
        failure.assertHasCause("Error: Cannot use @CacheableTask with type MyTask.Options. This annotation can only be used with Task types.")
        failure.assertHasCause("Error: Cannot use @CacheableTransform with type MyTask. This annotation can only be used with TransformAction types.")
        failure.assertHasCause("Error: Cannot use @CacheableTransform with type MyTask.Options. This annotation can only be used with TransformAction types.")

        file("build/reports/task-properties/report.txt").text == """
            Error: Cannot use @CacheableTask with type MyTask.Options. This annotation can only be used with Task types.
            Error: Cannot use @CacheableTransform with type MyTask. This annotation can only be used with TransformAction types.
            Error: Cannot use @CacheableTransform with type MyTask.Options. This annotation can only be used with TransformAction types.
        """.stripIndent().trim()
    }

    def "detects missing annotation on Groovy properties"() {
        buildFile << """
            apply plugin: "groovy"

            dependencies {
                implementation localGroovy()
            }
        """
        file("src/main/groovy/MyTask.groovy") << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @Input
                long goodTime

                @Nested Options options

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver

                long badTime

                static class Options {
                    @Input String goodNested
                    String badNested
                }
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Warning: Type 'MyTask': property 'badTime' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation."

        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    def "no problems with Copy task"() {
        file("src/main/java/MyTask.java") << """
            public class MyTask extends org.gradle.api.tasks.Copy {}
        """

        expect:
        succeeds "validateTaskProperties"
    }

    def "does not report missing properties for Provider types"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.provider.Provider;
            import org.gradle.api.provider.Property;
            
            import java.io.File;
            import java.util.concurrent.Callable;

            public class MyTask extends DefaultTask {
                private final Provider<String> text;
                private final Property<File> file;
                private final Property<Pojo> pojo;

                public MyTask() {
                    text = getProject().provider(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            return "Hello World!";
                        }
                    });
                    file = getProject().getObjects().property(File.class);
                    file.set(new File("some/dir"));
                    pojo = getProject().getObjects().property(Pojo.class);
                }

                @Input
                public String getText() {
                    return text.get();
                }

                @OutputFile
                public File getFile() {
                    return file.get();
                }

                @Nested
                public Pojo getPojo() {
                    return pojo.get();
                }
            }
        """

        file("src/main/java/Pojo.java") << """
            import org.gradle.api.tasks.Input;

            public class Pojo {
                private final Boolean enabled;
                
                public Pojo(Boolean enabled) {
                    this.enabled = enabled;
                }

                @Input
                public Boolean isEnabled() {
                    return enabled;
                }
            }
        """

        expect:
        succeeds "validateTaskProperties"

        file("build/reports/task-properties/report.txt").text == ""
    }

    @Unroll
    def "report setters for property of mutable type #type"() {
        def myTask = """
            import org.gradle.api.DefaultTask;
            import org.gradle.api.tasks.InputFiles;

            public class MyTask extends DefaultTask {
                // getter and setter
                @InputFiles public ${type} getMutablePropertyWithSetter() { return null; } 
                public void setMutablePropertyWithSetter(${type} value) {} 

                // just getter
                @InputFiles public ${type} getMutablePropertyWithoutSetter() { return null; } 

                // just setter
                // TODO implement warning for this case: https://github.com/gradle/gradle/issues/9341
                public void setMutablePropertyWithoutGetter() {}                
            }
        """
        file("src/main/java/MyTask.java") << myTask

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'mutablePropertyWithSetter' of mutable type '${type.replaceAll("<.+>", "")}' is writable. Properties of this type should be read-only and mutated via the value itself
        """.stripIndent().trim()

        where:
        type << [ConfigurableFileCollection.name, "${Property.name}<String>", RegularFileProperty.name]
    }

    def "detects problems with file inputs"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.util.Set;
            import java.util.Collections;
            import java.io.File;

            @CacheableTask
            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver;

                public long getBadTime() {
                    return 0;
                }

                public static class Options {
                    @Input
                    public String getGoodNested() {
                        return "good";
                    }
                    public String getBadNested(){
                        return "bad";
                    }
                }
                
                @OutputDirectory
                public File getOutputDir() {
                    return new File("outputDir");
                }
                
                @InputDirectory
                @Optional
                public File getInputDirectory() {
                    return new File("inputDir");
                }

                @InputFile
                public File getInputFile() {
                    return new File("inputFile");
                }

                @InputFiles
                public Set<File> getInputFiles() {
                    return Collections.emptySet();
                }
                
                @Input
                public File getFile() {
                    return new File("some-file");
                }
                
                @TaskAction
                public void doStuff() { }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': property 'file' has @Input annotation used on property of type java.io.File.
            Warning: Type 'MyTask': property 'inputDirectory' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Type 'MyTask': property 'inputFile' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Type 'MyTask': property 'inputFiles' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    def "detects annotations on private getter methods"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;

            public class MyTask extends DefaultTask {
                @Input
                private long getBadTime() {
                    return 0;
                }

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                public static class Options {
                    @Input
                    private String getBadNested() {
                        return "good";
                    }
                }
                
                @OutputDirectory
                private File getOutputDir() {
                    return new File("outputDir");
                }
                
                @TaskAction
                public void doStuff() { }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'badTime' is private and annotated with @Input.
            Warning: Type 'MyTask': property 'options.badNested' is private and annotated with @Input.
            Warning: Type 'MyTask': property 'outputDir' is private and annotated with @OutputDirectory.
        """.stripIndent().trim()
    }

    def "detects annotations on non-property methods"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;

            public class MyTask extends DefaultTask {
                @Input
                public String notAGetter() {
                    return "not-a-getter";
                }

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                public static class Options {
                    @Input
                    public String notANestedGetter() {
                        return "not-a-nested-getter";
                    }
                }

                @TaskAction
                public void doStuff() { }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask\$Options': non-property method 'notANestedGetter()' should not be annotated with: @Input
            Warning: Type 'MyTask': non-property method 'notAGetter()' should not be annotated with: @Input
        """.stripIndent().trim()
    }

    def "detects annotations on setter methods"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;

            public class MyTask extends DefaultTask {
                @Input
                public void setWriteOnly(String value) {
                }

                public String getReadWrite() {
                    return "read-write property";
                }

                @Input
                public void setReadWrite(String value) {
                }

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                public static class Options {
                    @Input
                    public void setWriteOnly(String value) {
                    }

                    @Input
                    public String getReadWrite() {
                        return "read-write property";
                    }

                    @Input
                    public void setReadWrite(String value) {
                    }
                }

                @TaskAction
                public void doStuff() { }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask\$Options': setter method 'setReadWrite()' should not be annotated with: @Input
            Warning: Type 'MyTask\$Options': setter method 'setWriteOnly()' should not be annotated with: @Input
            Warning: Type 'MyTask': property 'readWrite' is not annotated with an input or output annotation.
            Warning: Type 'MyTask': setter method 'setReadWrite()' should not be annotated with: @Input
            Warning: Type 'MyTask': setter method 'setWriteOnly()' should not be annotated with: @Input
        """.stripIndent().trim()
    }

    @Requires(TestPrecondition.JDK8_OR_LATER)
    def "can validate task classes using external types"() {
        buildFile << """
            ${jcenterRepository()}

            dependencies {
                implementation 'com.typesafe:config:1.3.2'
            }
        """

        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;
            import com.typesafe.config.Config;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }
                
                @Input
                public Config getConfig() { return null; } 
            }
        """

        expect:
        succeeds "validateTaskProperties"
    }

    @Requires(TestPrecondition.JDK8_OR_LATER)
    def "can validate task classes using types from other projects"() {
        settingsFile << """include 'lib'"""
        buildFile << """  
            allprojects {
                ${jcenterRepository()}
            }

            project(':lib') {
                apply plugin: 'java'

                dependencies {
                    implementation 'com.typesafe:config:1.3.2'
                }
            }          

            dependencies {
                implementation project(':lib')
            }
        """
        file("lib/src/main/java/MyUtil.java") << """
            import com.typesafe.config.Config;

            public class MyUtil {
                public Config getConfig() {
                    return null;
                }
            }
        """

        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }
                
                @Input
                public MyUtil getUtil() { return null; } 
            }
        """

        expect:
        succeeds "validateTaskProperties"
    }

    def "can enable stricter validation"() {
        buildFile << """
            apply plugin: "groovy"

            dependencies {
                implementation localGroovy()
            }
            
            validateTaskProperties.enableStricterValidation = project.hasProperty('strict')
        """
        file("src/main/groovy/MyTask.groovy") << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @InputFile
                File fileProp
                
                @InputFiles
                Set<File> filesProp

                @InputDirectory
                File dirProp

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver
            }
        """

        expect:
        succeeds("validateTaskProperties")

        when:
        fails "validateTaskProperties", "-Pstrict"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'dirProp' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Type 'MyTask': property 'fileProp' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Type 'MyTask': property 'filesProp' is missing a normalization annotation, defaulting to PathSensitivity.ABSOLUTE.
        """.stripIndent().trim()
    }

    def "can validate properties of an artifact transform action"() {
        file("src/main/java/MyTransformAction.java") << """
            import org.gradle.api.*;
            import org.gradle.api.provider.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import java.io.*;

            public abstract class MyTransformAction implements TransformAction {
                // Should be ignored because it's not a getter
                public void getVoid() {
                }

                // Should be ignored because it's not a getter
                public int getWithParameter(int count) {
                    return count;
                }

                // Ignored because static
                public static int getStatic() {
                    return 0;
                }

                // Ignored because injected
                @javax.inject.Inject
                public abstract org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputArtifact
                public abstract Provider<FileSystemLocation> getGoodInput();

                // Invalid because it has no annotation
                public long getBadTime() {
                    return System.currentTimeMillis();
                }

                // Invalid because it has some other annotation
                @Deprecated
                public String getOldThing() {
                    return null;
                }
                
                // Unsupported annotation
                @InputFile
                public abstract File getInputFile();
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Error: Type 'MyTransformAction': property 'badTime' is not annotated with an input annotation."
        failure.assertHasCause "Error: Type 'MyTransformAction': property 'inputFile' is annotated with invalid property type @InputFile."
        failure.assertHasCause "Error: Type 'MyTransformAction': property 'oldThing' is not annotated with an input annotation."

        file("build/reports/task-properties/report.txt").text == """
            Error: Type 'MyTransformAction': property 'badTime' is not annotated with an input annotation.
            Error: Type 'MyTransformAction': property 'inputFile' is annotated with invalid property type @InputFile.
            Error: Type 'MyTransformAction': property 'oldThing' is not annotated with an input annotation.
        """.stripIndent().trim()
    }

    def "can validate properties of an artifact transform parameters object"() {
        file("src/main/java/MyTransformParameters.java") << """
            import org.gradle.api.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import org.gradle.work.*;
            import java.io.*;

            public interface MyTransformParameters extends TransformParameters {
                // Should be ignored because it's not a getter
                void getVoid();

                // Should be ignored because it's not a getter
                int getWithParameter(int count);

                // Ignored because injected
                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputFile
                File getGoodFileInput();

                // Valid
                @Incremental
                @InputFiles
                FileCollection getGoodIncrementalInput();

                // Valid
                @Input
                String getGoodInput();
                void setGoodInput(String value);

                // Invalid because only file inputs can be incremental
                @Incremental
                @Input
                String getIncrementalNonFileInput();
                void setIncrementalNonFileInput(String value);

                // Invalid because it has no annotation
                long getBadTime();

                // Invalid because it has some other annotation
                @Deprecated
                String getOldThing();
                
                // Unsupported annotation
                @InputArtifact
                File getInputFile();
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Error: Type 'MyTransformParameters': property 'badTime' is not annotated with an input annotation."
        failure.assertHasCause "Error: Type 'MyTransformParameters': property 'incrementalNonFileInput' is annotated with @Incremental that is not allowed for @Input properties."
        failure.assertHasCause "Error: Type 'MyTransformParameters': property 'inputFile' is annotated with invalid property type @InputArtifact."
        failure.assertHasCause "Error: Type 'MyTransformParameters': property 'oldThing' is not annotated with an input annotation."

        file("build/reports/task-properties/report.txt").text == """
            Error: Type 'MyTransformParameters': property 'badTime' is not annotated with an input annotation.
            Error: Type 'MyTransformParameters': property 'incrementalNonFileInput' is annotated with @Incremental that is not allowed for @Input properties.
            Error: Type 'MyTransformParameters': property 'inputFile' is annotated with invalid property type @InputArtifact.
            Error: Type 'MyTransformParameters': property 'oldThing' is not annotated with an input annotation.
        """.stripIndent().trim()
    }

    def "reports conflicting types when property is replaced but keeps old annotations"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.model.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.provider.*;
            
            public class MyTask extends DefaultTask {
                private final Property<String> newProperty = getProject().getObjects().property(String.class);
    
                @Input
                @ReplacedBy("newProperty")
                public String getOldProperty() {
                    return newProperty.get();
                }
    
                public void setOldProperty(String oldProperty) {
                    newProperty.set(oldProperty);
                }
    
                @Input
                public Property<String> getNewProperty() {
                    return newProperty;
                }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Type 'MyTask': property 'oldProperty' has conflicting type annotations declared: @Input, @ReplacedBy; assuming @Input.
        """.stripIndent().trim()
    }
}
