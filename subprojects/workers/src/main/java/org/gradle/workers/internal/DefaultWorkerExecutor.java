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

package org.gradle.workers.internal;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import org.gradle.api.Action;
import org.gradle.api.specs.Spec;
import org.gradle.internal.Actions;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.classloader.ClassLoaderUtils;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationRef;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.work.AbstractConditionalExecution;
import org.gradle.internal.work.AsyncWorkCompletion;
import org.gradle.internal.work.AsyncWorkTracker;
import org.gradle.internal.work.ConditionalExecutionQueue;
import org.gradle.internal.work.DefaultConditionalExecutionQueue;
import org.gradle.internal.work.NoAvailableWorkerLeaseException;
import org.gradle.internal.work.WorkerLeaseRegistry;
import org.gradle.internal.work.WorkerLeaseRegistry.WorkerLease;
import org.gradle.model.internal.type.ModelType;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.worker.child.WorkerDirectoryProvider;
import org.gradle.util.CollectionUtils;
import org.gradle.workers.ClassLoaderWorkerSpec;
import org.gradle.workers.IsolationMode;
import org.gradle.workers.ProcessWorkerSpec;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerConfiguration;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkerExecutionException;
import org.gradle.workers.WorkerExecutor;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkerSpec;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.File;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.Callable;

import static org.gradle.internal.classloader.ClassLoaderUtils.classFromContextLoader;
import static org.gradle.internal.work.AsyncWorkTracker.ProjectLockRetention.RETAIN_PROJECT_LOCKS;

public class DefaultWorkerExecutor implements WorkerExecutor {
    private final ConditionalExecutionQueue<DefaultWorkResult> executionQueue;
    private final WorkerFactory daemonWorkerFactory;
    private final WorkerFactory isolatedClassloaderWorkerFactory;
    private final WorkerFactory noIsolationWorkerFactory;
    private final JavaForkOptionsFactory forkOptionsFactory;
    private final WorkerLeaseRegistry workerLeaseRegistry;
    private final BuildOperationExecutor buildOperationExecutor;
    private final AsyncWorkTracker asyncWorkTracker;
    private final WorkerDirectoryProvider workerDirectoryProvider;
    private final ClassLoaderStructureProvider classLoaderStructureProvider;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;
    private final Instantiator instantiator;

    public DefaultWorkerExecutor(WorkerFactory daemonWorkerFactory, WorkerFactory isolatedClassloaderWorkerFactory, WorkerFactory noIsolationWorkerFactory, JavaForkOptionsFactory forkOptionsFactory, WorkerLeaseRegistry workerLeaseRegistry, BuildOperationExecutor buildOperationExecutor, AsyncWorkTracker asyncWorkTracker, WorkerDirectoryProvider workerDirectoryProvider, WorkerExecutionQueueFactory workerExecutionQueueFactory, ClassLoaderStructureProvider classLoaderStructureProvider, ActionExecutionSpecFactory actionExecutionSpecFactory, Instantiator instantiator) {
        this.daemonWorkerFactory = daemonWorkerFactory;
        this.isolatedClassloaderWorkerFactory = isolatedClassloaderWorkerFactory;
        this.noIsolationWorkerFactory = noIsolationWorkerFactory;
        this.forkOptionsFactory = forkOptionsFactory;
        this.executionQueue = workerExecutionQueueFactory.create();
        this.workerLeaseRegistry = workerLeaseRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
        this.asyncWorkTracker = asyncWorkTracker;
        this.workerDirectoryProvider = workerDirectoryProvider;
        this.classLoaderStructureProvider = classLoaderStructureProvider;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
        this.instantiator = instantiator;
    }

    @Override
    public WorkQueue noIsolation() {
        return noIsolation(Actions.doNothing());
    }

    @Override
    public WorkQueue classLoaderIsolation() {
        return classLoaderIsolation(Actions.doNothing());
    }

    @Override
    public WorkQueue processIsolation() {
        return processIsolation(Actions.doNothing());
    }

    @Override
    public WorkQueue noIsolation(Action<WorkerSpec> action) {
        DefaultWorkerSpec spec = instantiator.newInstance(DefaultWorkerSpec.class);
        action.execute(spec);
        return instantiator.newInstance(DefaultWorkQueue.class, this, spec);
    }

    @Override
    public WorkQueue classLoaderIsolation(Action<ClassLoaderWorkerSpec> action) {
        DefaultClassLoaderWorkerSpec spec = instantiator.newInstance(DefaultClassLoaderWorkerSpec.class);
        action.execute(spec);
        return instantiator.newInstance(DefaultWorkQueue.class, this, spec);
    }

    @Override
    public WorkQueue processIsolation(Action<ProcessWorkerSpec> action) {
        DefaultProcessWorkerSpec spec = instantiator.newInstance(DefaultProcessWorkerSpec.class);
        File defaultWorkingDir = spec.getForkOptions().getWorkingDir();
        File workingDirectory = workerDirectoryProvider.getWorkingDirectory();
        action.execute(spec);

        if (!defaultWorkingDir.equals(spec.getForkOptions().getWorkingDir())) {
            throw new IllegalArgumentException("Setting the working directory of a worker is not supported.");
        } else {
            spec.getForkOptions().setWorkingDir(workingDirectory);
        }

        return instantiator.newInstance(DefaultWorkQueue.class, this, spec);
    }

    @Override
    public void submit(Class<? extends Runnable> actionClass, Action<? super WorkerConfiguration> configAction) {
        DefaultWorkerConfiguration configuration = new DefaultWorkerConfiguration(forkOptionsFactory);
        configAction.execute(configuration);

        Action<AdapterWorkParameters> parametersAction = new Action<AdapterWorkParameters>() {
            @Override
            public void execute(AdapterWorkParameters parameters) {
                parameters.setImplementationClassName(actionClass.getName());
                parameters.setParams(configuration.getParams());
                parameters.setDisplayName(configuration.getDisplayName());
            }
        };

        WorkQueue workQueue;
        switch(configuration.getIsolationMode()) {
            case NONE:
            case AUTO:
                workQueue = noIsolation(getWorkerSpecAdapterAction(configuration));
                break;
            case CLASSLOADER:
                workQueue = classLoaderIsolation(getWorkerSpecAdapterAction(configuration));
                break;
            case PROCESS:
                workQueue = processIsolation(getWorkerSpecAdapterAction(configuration));
                break;
            default:
                throw new IllegalArgumentException("Unknown isolation mode: " + configuration.getIsolationMode());
        }
        workQueue.submit(AdapterWorkAction.class, parametersAction);
    }

    <T extends WorkerSpec> Action<T> getWorkerSpecAdapterAction(DefaultWorkerConfiguration configuration) {
        return new Action<T>() {
            @Override
            public void execute(T spec) {
                configuration.adaptTo(spec);
            }
        };
    }

    private <T extends WorkParameters> AsyncWorkCompletion submitWork(Class<? extends WorkAction<T>> workActionClass, WorkerSpecInternal workerSpec, Action<T> parameterAction) {
        ParameterizedType superType = (ParameterizedType) TypeToken.of(workActionClass).getSupertype(WorkAction.class).getType();
        Class<T> parameterType = Cast.uncheckedNonnullCast(TypeToken.of(superType.getActualTypeArguments()[0]).getRawType());
        if (parameterType == WorkParameters.class) {
            throw new IllegalArgumentException(String.format("Could not create worker parameters: must use a sub-type of %s as parameter type. Use %s for executions without parameters.", ModelType.of(WorkParameters.class).getDisplayName(), ModelType.of(WorkParameters.None.class).getDisplayName()));
        }
        T parameters = (parameterType == WorkParameters.None.class) ? null : instantiator.newInstance(parameterType);
        if (parameters != null) {
            parameterAction.execute(parameters);
        }

        ActionExecutionSpec spec;
        String description = getWorkerDisplayName(workActionClass, parameters);
        DaemonForkOptions forkOptions = getDaemonForkOptions(workActionClass, workerSpec, parameters);
        try {
            // Isolate parameters in this thread prior to starting work in a separate thread
            spec = actionExecutionSpecFactory.newIsolatedSpec(description, workActionClass, parameters, forkOptions.getClassLoaderStructure());
        } catch (Throwable t) {
            throw new WorkExecutionException(description, t);
        }

        return submitWork(spec, workerSpec.getIsolationMode(), forkOptions);
    }

    private AsyncWorkCompletion submitWork(final ActionExecutionSpec spec, final IsolationMode isolationMode, final DaemonForkOptions daemonForkOptions) {
        final WorkerLease currentWorkerWorkerLease = getCurrentWorkerLease();
        final BuildOperationRef currentBuildOperation = buildOperationExecutor.getCurrentOperation();
        WorkerFactory workerFactory = getWorkerFactory(isolationMode);
        WorkItemExecution execution = new WorkItemExecution(spec.getDisplayName(), currentWorkerWorkerLease, new Callable<DefaultWorkResult>() {
            @Override
            public DefaultWorkResult call() throws Exception {
                try {
                    BuildOperationAwareWorker worker = workerFactory.getWorker(daemonForkOptions);
                    return worker.execute(spec, currentBuildOperation);
                } catch (Throwable t) {
                    throw new WorkExecutionException(spec.getDisplayName(), t);
                }
            }
        });
        executionQueue.submit(execution);
        asyncWorkTracker.registerWork(currentBuildOperation, execution);
        return execution;
    }

    private static String getWorkerDisplayName(Class<?> workActionClass, WorkParameters parameters) {
        if (workActionClass == AdapterWorkAction.class) {
            AdapterWorkParameters adapterWorkParameters = (AdapterWorkParameters) parameters;
            if (adapterWorkParameters.getDisplayName() != null) {
                return adapterWorkParameters.getDisplayName();
            } else {
                return adapterWorkParameters.getImplementationClassName();
            }
        } else {
            return workActionClass.getName();
        }
    }

    private WorkerLease getCurrentWorkerLease() {
        try {
            return workerLeaseRegistry.getCurrentWorkerLease();
        } catch (NoAvailableWorkerLeaseException e) {
            throw new IllegalStateException("An attempt was made to submit work from a thread not managed by Gradle.  Work may only be submitted from a Gradle-managed thread.", e);
        }
    }

    private WorkerFactory getWorkerFactory(IsolationMode isolationMode) {
        switch (isolationMode) {
            case AUTO:
            case CLASSLOADER:
                return isolatedClassloaderWorkerFactory;
            case NONE:
                return new ContextClassLoaderWorkerFactory(noIsolationWorkerFactory);
            case PROCESS:
                return daemonWorkerFactory;
            default:
                throw new IllegalArgumentException("Unknown isolation mode: " + isolationMode);
        }
    }

    /**
     * Wait for any outstanding work to complete.  Note that if there is uncompleted work associated
     * with the current build operation, we'll also temporarily expand the thread pool of the execution queue.
     * This is to avoid a thread starvation scenario (see {@link DefaultConditionalExecutionQueue#expand(boolean)}
     * for further details).
     */
    @Override
    public void await() throws WorkerExecutionException {
        BuildOperationRef currentOperation = buildOperationExecutor.getCurrentOperation();
        try {
            if (asyncWorkTracker.hasUncompletedWork(currentOperation)) {
                executionQueue.expand();
            }
            asyncWorkTracker.waitForCompletion(currentOperation, RETAIN_PROJECT_LOCKS);
        } catch (DefaultMultiCauseException e) {
            throw workerExecutionException(e.getCauses());
        }
    }

    private void await(List<AsyncWorkCompletion> workItems) throws WorkExecutionException {
        BuildOperationRef currentOperation = buildOperationExecutor.getCurrentOperation();
        try {
            if (CollectionUtils.any(workItems, new Spec<AsyncWorkCompletion>() {
                @Override
                public boolean isSatisfiedBy(AsyncWorkCompletion workItem) {
                    return !workItem.isComplete();
                }
            })) {
                executionQueue.expand();
            }
            asyncWorkTracker.waitForCompletion(currentOperation, workItems, RETAIN_PROJECT_LOCKS);
        } catch (DefaultMultiCauseException e) {
            throw workerExecutionException(e.getCauses());
        }
    }

    private WorkerExecutionException workerExecutionException(List<? extends Throwable> failures) {
        if (failures.size() == 1) {
            throw new WorkerExecutionException("There was a failure while executing work items", failures);
        } else {
            throw new WorkerExecutionException("There were multiple failures while executing work items", failures);
        }
    }

    DaemonForkOptions getDaemonForkOptions(Class<?> executionClass, WorkerSpec configuration, WorkParameters parameters) {
        DaemonForkOptionsBuilder builder = new DaemonForkOptionsBuilder(forkOptionsFactory)
                .keepAliveMode(KeepAliveMode.DAEMON);

        if (configuration instanceof ProcessWorkerSpec) {
            ProcessWorkerSpec processConfiguration = (ProcessWorkerSpec) configuration;
            JavaForkOptions forkOptions = forkOptionsFactory.newJavaForkOptions();
            processConfiguration.getForkOptions().copyTo(forkOptions);
            forkOptions.setWorkingDir(workerDirectoryProvider.getWorkingDirectory());

            builder.javaForkOptions(forkOptions)
                    .withClassLoaderStructure(classLoaderStructureProvider.getWorkerProcessClassLoaderStructure(processConfiguration.getClasspath(), getParamClasses(executionClass, parameters)));

        } else if (configuration instanceof ClassLoaderWorkerSpec) {
            ClassLoaderWorkerSpec classLoaderConfiguration = (ClassLoaderWorkerSpec) configuration;
            builder.withClassLoaderStructure(classLoaderStructureProvider.getInProcessClassLoaderStructure(classLoaderConfiguration.getClasspath(), getParamClasses(executionClass, parameters)));
        }

        return builder.build();
    }

    private Class<?>[] getParamClasses(Class<?> actionClass, WorkParameters parameters) {
        Class<?> implementationClass;
        Object[] params;
        if (parameters instanceof AdapterWorkParameters) {
            AdapterWorkParameters adapterWorkParameters = (AdapterWorkParameters) parameters;
            implementationClass = classFromContextLoader(adapterWorkParameters.getImplementationClassName());
            params = adapterWorkParameters.getParams();
        } else {
            implementationClass = actionClass;
            params = new Object[] {parameters};
        }

        List<Class<?>> classes = Lists.newArrayList();
        classes.add(implementationClass);
        for (Object param : params) {
            if (param != null) {
                classes.add(param.getClass());
            }
        }
        return classes.toArray(new Class[0]);
    }

    @Contextual
    private static class WorkExecutionException extends RuntimeException {
        WorkExecutionException(String description) {
            super(toMessage(description));
        }

        WorkExecutionException(String description, Throwable cause) {
            super(toMessage(description), cause);
        }

        private static String toMessage(String description) {
            return "A failure occurred while executing " + description;
        }
    }

    private static class WorkItemExecution extends AbstractConditionalExecution<DefaultWorkResult> implements AsyncWorkCompletion {
        private final String description;

        public WorkItemExecution(String description, WorkerLease parentWorkerLease, Callable<DefaultWorkResult> callable) {
            super(callable, new LazyChildWorkerLeaseLock(parentWorkerLease));
            this.description = description;
        }

        @Override
        public void waitForCompletion() {
            DefaultWorkResult result = await();
            if (!result.isSuccess()) {
                throw new WorkExecutionException(description, result.getException());
            }
        }
    }

    private static class LazyChildWorkerLeaseLock implements ResourceLock {
        private final WorkerLease parentWorkerLease;
        private WorkerLease child;

        public LazyChildWorkerLeaseLock(WorkerLease parentWorkerLease) {
            this.parentWorkerLease = parentWorkerLease;
        }

        @Override
        public boolean isLocked() {
            return getChild().isLocked();
        }

        @Override
        public boolean isLockedByCurrentThread() {
            return getChild().isLockedByCurrentThread();
        }

        @Override
        public boolean tryLock() {
            child = parentWorkerLease.createChild();
            if (child.tryLock()) {
                return true;
            } else {
                child = null;
                return false;
            }
        }

        @Override
        public void unlock() {
            getChild().unlock();
        }

        @Override
        public String getDisplayName() {
            return getChild().getDisplayName();
        }

        private WorkerLease getChild() {
            if (child == null) {
                throw new IllegalStateException("Detected attempt to access LazyChildWorkerLeaseLock before tryLock() has succeeded.  tryLock must be succeed before other methods are called.");
            }
            return child;
        }
    }

    @NotThreadSafe
    static class DefaultWorkQueue implements WorkQueue {
        private final DefaultWorkerExecutor workerExecutor;
        private final WorkerSpecInternal spec;
        private final List<AsyncWorkCompletion> workItems = Lists.newArrayList();

        public DefaultWorkQueue(DefaultWorkerExecutor workerExecutor, WorkerSpecInternal spec) {
            this.workerExecutor = workerExecutor;
            this.spec = spec;
        }

        @Override
        public <T extends WorkParameters> void submit(Class<? extends WorkAction<T>> workActionClass, Action<T> parameterAction) {
            workItems.add(workerExecutor.submitWork(workActionClass, spec, parameterAction));
        }

        @Override
        public void await() throws WorkerExecutionException {
            workerExecutor.await(workItems);
         }
    }

    /**
     * This is a delegating WorkerFactory that captures the context classloader at the moment it is created
     * and then ensures that the worker is created with the same context classloader when
     * getWorker() is called later.
     */
    private static class ContextClassLoaderWorkerFactory implements WorkerFactory {
        private final ClassLoader contextClassLoader;
        private final WorkerFactory delegate;

        public ContextClassLoaderWorkerFactory(WorkerFactory delegate) {
            this.delegate = delegate;
            this.contextClassLoader = Thread.currentThread().getContextClassLoader();
        }

        @Override
        public BuildOperationAwareWorker getWorker(DaemonForkOptions forkOptions) {
            return ClassLoaderUtils.executeInClassloader(contextClassLoader, new Factory<BuildOperationAwareWorker>() {
                @Nullable
                @Override
                public BuildOperationAwareWorker create() {
                    return delegate.getWorker(forkOptions);
                }
            });
        }

        @Override
        public IsolationMode getIsolationMode() {
            return delegate.getIsolationMode();
        }
    }
}
