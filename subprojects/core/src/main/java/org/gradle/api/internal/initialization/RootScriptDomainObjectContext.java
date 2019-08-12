/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.initialization;

import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.util.Path;

import javax.annotation.Nullable;

public class RootScriptDomainObjectContext implements DomainObjectContext {

    public static final DomainObjectContext INSTANCE = new RootScriptDomainObjectContext();

    private RootScriptDomainObjectContext() {
    }

    @Override
    public Path identityPath(String name) {
        return Path.path(name);
    }

    @Override
    public Path projectPath(String name) {
        return Path.path(name);
    }

    @Override
    public Path getProjectPath() {
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal getProject() {
        return null;
    }

    @Override
    public Path getBuildPath() {
        return Path.ROOT;
    }

    @Override
    public boolean isScript() {
        return true;
    }
}
