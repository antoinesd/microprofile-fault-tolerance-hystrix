/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.swarm.microprofile.faulttolerance;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Priority;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Unmanaged;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.jboss.logging.Logger;
import org.wildfly.swarm.microprofile.faulttolerance.config.BulkheadConfig;
import org.wildfly.swarm.microprofile.faulttolerance.config.CircuitBreakerConfig;
import org.wildfly.swarm.microprofile.faulttolerance.config.FallbackConfig;
import org.wildfly.swarm.microprofile.faulttolerance.config.RetryConfig;
import org.wildfly.swarm.microprofile.faulttolerance.config.TimeoutConfig;

import com.netflix.hystrix.HystrixCircuitBreaker;
import com.netflix.hystrix.HystrixCommand.Setter;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.exception.HystrixRuntimeException;

/**
 * @author Antoine Sabot-Durand
 */
@Interceptor
@HystrixCommandBinding
@Priority(Interceptor.Priority.LIBRARY_AFTER + 1)
public class HystrixCommandInterceptor {

    /**
     * This config property key can be used to disable synchronous circuit breaker functionality. If disabled, {@link CircuitBreaker#successThreshold()} of
     * value greater than 1 is not supported.
     * <p>
     * Moreover, circuit breaker does not necessarily transition from CLOSED to OPEN immediately when a fault tolerance operation completes. See also
     * <a href="https://github.com/Netflix/Hystrix/wiki/Configuration#metrics.healthSnapshot.intervalInMilliseconds">Hystrix configuration</a>
     * </p>
     * <p>
     * In general, application developers are encouraged to disable this feature on high-volume circuits and in production environments.
     * </p>
     */
    public static final String SYNC_CIRCUIT_BREAKER_KEY = "org_wildfly_swarm_microprofile_faulttolerance_syncCircuitBreaker";

    private static final Logger LOGGER = Logger.getLogger(HystrixCommandInterceptor.class);

    @SuppressWarnings("unchecked")
    public HystrixCommandInterceptor() {
        this.commandMetadataMap = new ConcurrentHashMap<>();
        // WORKAROUND: Hystrix does not allow to use custom HystrixCircuitBreaker impl
        // See also https://github.com/Netflix/Hystrix/issues/9
        try {
            Field field = SecurityActions.getDeclaredField(com.netflix.hystrix.HystrixCircuitBreaker.Factory.class, "circuitBreakersByCommand");
            SecurityActions.setAccessible(field);
            this.circuitBreakers = (ConcurrentHashMap<String, HystrixCircuitBreaker>) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain reference to com.netflix.hystrix.HystrixCircuitBreaker.Factory.circuitBreakersByCommand");
        }
    }

    @AroundInvoke
    public Object interceptCommand(InvocationContext ic) throws Exception {

        Method method = ic.getMethod();
        ExecutionContextWithInvocationContext ctx = new ExecutionContextWithInvocationContext(ic);

        boolean shouldRunCommand = true;
        Object res = null;

        CommandMetadata metadata = commandMetadataMap.computeIfAbsent(method, CommandMetadata::new);
        RetryContext retryContext = metadata.retryConfig != null ? new RetryContext(metadata.retryConfig) : null;

        Supplier<Object> fallback = null;
        if (metadata.hasFallback()) {
            fallback = metadata.unmanaged != null ? () -> {
                Unmanaged.UnmanagedInstance<FallbackHandler<?>> unmanagedInstance = metadata.unmanaged.newInstance();
                FallbackHandler<?> handler = unmanagedInstance.produce().inject().postConstruct().get();
                try {
                    return handler.handle(ctx);
                } finally {
                    // The instance exists to service a single invocation only
                    unmanagedInstance.preDestroy().dispose();
                }
            } : () -> {
                try {
                    return metadata.fallbackMethod.invoke(ctx.getTarget(), ctx.getParameters());
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new FaultToleranceException("Error during fallback method invocation", e);
                }
            };
        }

        Asynchronous async = getAnnotation(method, Asynchronous.class);
        SynchronousCircuitBreaker syncCircuitBreaker = null;
        while (shouldRunCommand) {
            shouldRunCommand = false;

            if (metadata.hasCircuitBreaker() && syncCircuitBreakerEnabled) {
                syncCircuitBreaker = getSynchronousCircuitBreaker(metadata.commandKey, metadata.circuitBreakerConfig);
            }
            DefaultCommand command = new DefaultCommand(metadata.setter, ctx, fallback, retryContext, async != null, metadata.hasCircuitBreaker());

            if (syncCircuitBreaker != null && syncCircuitBreaker.allowRequest() == false) {
                throw new CircuitBreakerOpenException(method.getName());
            }
            try {
                if (async != null) {
                    res = command.queue();
                } else {
                    res = command.execute();
                }
                if (syncCircuitBreaker != null) {
                    syncCircuitBreaker.incSuccessCount();
                }
            } catch (HystrixRuntimeException e) {
                if (syncCircuitBreaker != null) {
                    syncCircuitBreaker.incFailureCount();
                }
                HystrixRuntimeException.FailureType failureType = e.getFailureType();
                switch (failureType) {
                    case TIMEOUT: {
                        if (retryContext != null && retryContext.shouldRetry()) {
                            shouldRunCommand = shouldRetry(retryContext, new TimeoutException(e));
                            if (shouldRunCommand) {
                                continue;
                            }
                        }
                        throw new TimeoutException(e);
                    }
                    case SHORTCIRCUIT:
                        throw new CircuitBreakerOpenException(method.getName());
                    case REJECTED_THREAD_EXECUTION:
                    case REJECTED_SEMAPHORE_EXECUTION:
                    case REJECTED_SEMAPHORE_FALLBACK:
                    case COMMAND_EXCEPTION:
                        if (retryContext != null && retryContext.shouldRetry()) {
                            shouldRunCommand = shouldRetry(retryContext, e);
                            continue;
                        }
                    default:
                        throw (e.getCause() instanceof Exception) ? (Exception) e.getCause() : e;
                }
            }
        }
        return res;
    }

    private SynchronousCircuitBreaker getSynchronousCircuitBreaker(HystrixCommandKey commandKey, CircuitBreakerConfig config) {
        HystrixCircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(commandKey.name(), (key) -> new SynchronousCircuitBreaker(config));
        if (circuitBreaker instanceof SynchronousCircuitBreaker) {
            return (SynchronousCircuitBreaker) circuitBreaker;
        }
        throw new IllegalStateException("Cached circuit breaker does not extend SynchronousCircuitBreaker");
    }

    private <T extends Annotation> T getAnnotation(Method method, Class<T> annotation) {
        if (method.isAnnotationPresent(annotation)) {
            return method.getAnnotation(annotation);
        } else if (method.getDeclaringClass().isAnnotationPresent(annotation)) {
            return method.getDeclaringClass().getAnnotation(annotation);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Unmanaged<FallbackHandler<?>> initUnmanaged(Method method) {
        Fallback fallback = getAnnotation(method, Fallback.class);
        if (fallback != null) {
            return (Unmanaged<FallbackHandler<?>>) new Unmanaged<>(beanManager, fallback.value());
        }
        return null;
    }

    private Setter initSetter(HystrixCommandKey commandKey, Method method, TimeoutConfig timeoutConfig, CircuitBreakerConfig circuitBreakerConfig,
            BulkheadConfig bulkheadConfig) {
        HystrixCommandProperties.Setter propertiesSetter = HystrixCommandProperties.Setter();
        HystrixThreadPoolProperties.Setter threadPoolSetter = HystrixThreadPoolProperties.Setter();

        if (getAnnotation(method, Asynchronous.class) == null) {
            propertiesSetter.withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE);
        } else {
            propertiesSetter.withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.THREAD);
        }

        if (nonFallBackEnable && timeoutConfig != null) {
            Long value = Duration.of(timeoutConfig.get(TimeoutConfig.VALUE), timeoutConfig.get(TimeoutConfig.UNIT)).toMillis();
            if (value > Integer.MAX_VALUE) {
                LOGGER.warnf("Max supported value for @Timeout.value() is %s", Integer.MAX_VALUE);
                value = Long.valueOf(Integer.MAX_VALUE);
            }
            propertiesSetter.withExecutionTimeoutInMilliseconds(value.intValue());
        } else {
            propertiesSetter.withExecutionTimeoutEnabled(false);
        }

        if (nonFallBackEnable && circuitBreakerConfig != null) {
            propertiesSetter.withCircuitBreakerEnabled(true)
                    .withCircuitBreakerRequestVolumeThreshold(circuitBreakerConfig.get(CircuitBreakerConfig.REQUEST_VOLUME_THRESHOLD))
                    .withCircuitBreakerErrorThresholdPercentage(
                            new Double((Double) circuitBreakerConfig.get(CircuitBreakerConfig.FAILURE_RATIO) * 100).intValue())
                    .withCircuitBreakerSleepWindowInMilliseconds((int) Duration
                            .of(circuitBreakerConfig.get(CircuitBreakerConfig.DELAY), circuitBreakerConfig.get(CircuitBreakerConfig.DELAY_UNIT)).toMillis());
        } else {
            propertiesSetter.withCircuitBreakerEnabled(false);
        }

        if (nonFallBackEnable && bulkheadConfig != null) {
            propertiesSetter.withExecutionIsolationSemaphoreMaxConcurrentRequests(bulkheadConfig.get(BulkheadConfig.VALUE))
                    .withExecutionIsolationThreadInterruptOnFutureCancel(true);
            // TODO: review the following comments
            // threadPoolSetter.withCoreSize(conf.get(BulkheadConfig.VALUE));
            // threadPoolSetter.withMaximumSize(conf.get(BulkheadConfig.VALUE));
        }

        return Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("DefaultCommandGroup"))
                // Each method must have a unique command key
                .andCommandKey(commandKey).andCommandPropertiesDefaults(propertiesSetter).andThreadPoolPropertiesDefaults(threadPoolSetter);
    }

    private boolean shouldRetry(RetryContext retryContext, Exception e) throws Exception {
        boolean shouldRetry = false;
        // Decrement the retry count for this attempt
        retryContext.doRetry();
        // Check the exception type
        if (Arrays.stream(retryContext.getAbortOn()).noneMatch(ex -> ex.isAssignableFrom(e.getClass()))
                && (retryContext.getRetryOn().length == 0 || Arrays.stream(retryContext.getRetryOn()).anyMatch(ex -> ex.isAssignableFrom(e.getClass())))
                && retryContext.shouldRetry() && System.nanoTime() - retryContext.getStart() <= retryContext.getMaxDuration()) {
            Long jitterBase = retryContext.getJitter();
            if (retryContext.getDelay() > 0) {
                long jitter = (long) (Math.random() * ((jitterBase * 2) + 1)) - jitterBase; // random number between -jitter and +jitter
                Thread.sleep(retryContext.getDelay() + Duration.of(jitter, retryContext.getJitterDelayUnit()).toMillis());
            }
            shouldRetry = true;
        } else {
            throw e;
        }
        return shouldRetry;
    }

    private final ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakers;

    private final Map<Method, CommandMetadata> commandMetadataMap;

    @Inject
    @ConfigProperty(name = "MP_Fault_Tolerance_NonFallback_Enabled", defaultValue = "true")
    private Boolean nonFallBackEnable;

    @Inject
    @ConfigProperty(name = SYNC_CIRCUIT_BREAKER_KEY, defaultValue = "true")
    private Boolean syncCircuitBreakerEnabled;

    @Inject
    private BeanManager beanManager;

    private class CommandMetadata {

        public CommandMetadata(Method method) {

            Timeout timeout = getAnnotation(method, Timeout.class);
            TimeoutConfig timeoutConfig = timeout != null ? new TimeoutConfig(timeout, method) : null;
            Bulkhead bulkhead = getAnnotation(method, Bulkhead.class);
            BulkheadConfig bulkheadConfig = bulkhead != null ? new BulkheadConfig(bulkhead, method) : null;

            CircuitBreaker circuitBreaker = getAnnotation(method, CircuitBreaker.class);
            if (nonFallBackEnable && circuitBreaker != null) {
                circuitBreakerConfig = new CircuitBreakerConfig(circuitBreaker, method);
            } else {
                circuitBreakerConfig = null;
            }

            // Initialize Hystrix command setter
            commandKey = HystrixCommandKey.Factory.asKey(method.toGenericString());
            setter = initSetter(commandKey, method, timeoutConfig, circuitBreakerConfig, bulkheadConfig);

            Fallback fallback = getAnnotation(method, Fallback.class);
            if (fallback != null) {
                FallbackConfig fc = new FallbackConfig(fallback, method);
                if (!fc.get(FallbackConfig.VALUE).equals(Fallback.DEFAULT.class)) {
                    unmanaged = initUnmanaged(method);
                    fallbackMethod = null;
                } else {
                    unmanaged = null;
                    if (!"".equals(fc.get(FallbackConfig.FALLBACK_METHOD))) {
                        try {
                            fallbackMethod = method.getDeclaringClass().getMethod(fc.get(FallbackConfig.FALLBACK_METHOD), method.getParameterTypes());
                        } catch (NoSuchMethodException e) {
                            throw new FaultToleranceException("Fallback method not found", e);
                        }
                    } else {
                        fallbackMethod = null;
                    }
                }
            } else {
                unmanaged = null;
                fallbackMethod = null;
            }

            Retry retry = getAnnotation(method, Retry.class);
            if (nonFallBackEnable && retry != null) {
                retryConfig = new RetryConfig(retry, method);
            } else {
                retryConfig = null;
            }
        }

        boolean hasFallback() {
            return unmanaged != null || fallbackMethod != null;
        }

        boolean hasCircuitBreaker() {
            return circuitBreakerConfig != null;
        }

        private final Setter setter;

        private final HystrixCommandKey commandKey;

        private final Unmanaged<FallbackHandler<?>> unmanaged;

        private final RetryConfig retryConfig;

        private final Method fallbackMethod;

        private final CircuitBreakerConfig circuitBreakerConfig;

    }

}
