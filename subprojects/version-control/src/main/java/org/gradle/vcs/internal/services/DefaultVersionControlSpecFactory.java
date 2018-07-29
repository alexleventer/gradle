/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.vcs.internal.services;

import org.gradle.StartParameter;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.model.ObjectFactory;
import org.gradle.vcs.VersionControlSpec;
import org.gradle.vcs.git.GitVersionControlSpec;
import org.gradle.vcs.git.internal.DefaultGitVersionControlSpec;
import org.gradle.vcs.internal.VersionControlSpecFactory;

public class DefaultVersionControlSpecFactory implements VersionControlSpecFactory {
    private final ObjectFactory objectFactory;
    private final StartParameter rootBuildStartParameter;

    public DefaultVersionControlSpecFactory(ObjectFactory objectFactory, StartParameter rootBuildStartParameter) {
        this.objectFactory = objectFactory;
        this.rootBuildStartParameter = rootBuildStartParameter;
    }

    @Override
    public <T extends VersionControlSpec> T create(Class<T> specType, ClassLoaderScope classLoaderScope) {
        if (specType.isAssignableFrom(GitVersionControlSpec.class)) {
            return specType.cast(objectFactory.newInstance(DefaultGitVersionControlSpec.class, rootBuildStartParameter, classLoaderScope));
        }
        throw new IllegalArgumentException(String.format("Do not know how to create an instance of %s.", specType.getName()));
    }
}