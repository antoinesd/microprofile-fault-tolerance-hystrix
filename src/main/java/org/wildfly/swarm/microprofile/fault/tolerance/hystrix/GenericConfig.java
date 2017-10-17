package org.wildfly.swarm.microprofile.fault.tolerance.hystrix;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;

/**
 * @author Antoine Sabot-Durand
 */

public abstract class GenericConfig<X extends Annotation> {

    public GenericConfig(X annotation, Method method) {
        this.method = method;
        this.annotation = annotation;
    }

    public <U> U get(String key, Class<U> expectedType) {

        /*
           Global config has the highest priority
         */
        Optional<U> opt = getConfig().getOptionalValue(getConfigType() + "/" + key, expectedType);
        if (opt.isPresent()) {
            return opt.get();
        }

        /*
            Config on field or on field annotation is priority 2
         */
        if (method.isAnnotationPresent(annotation.annotationType())) {
            opt = getConfig().getOptionalValue(getConfigKeyForMethod() + key, expectedType);
            if (opt.isPresent()) {
                return opt.get();
            } else {
                return getConfigFromAnnotaion(key);
            }
        }

        /*
            lowest priority for config on class
         */
        opt = getConfig().getOptionalValue(getConfigKeyForClass() + key, expectedType);
        if (opt.isPresent()) {
            return opt.get();
        } else {
            return getConfigFromAnnotaion(key);
        }

    }

    public <U> U get(String key) {
        Class<U> expectedType = (Class<U>) getKeysToType().get(key);
        return get(key, expectedType);
    }

    private <U> U getConfigFromAnnotaion(String key) {
        try {
            return (U) annotation.getClass().getMethod(key).invoke(annotation);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new FaultToleranceDefinitionException("Member " + key + " on annotation " + annotation.getClass().toString() + " doesn't exist or is not accessible");
        }
    }

    protected String getConfigKeyForMethod() {
        return method.getDeclaringClass().getName() + "/" + method.getName() + "/" + getConfigType() + "/";
    }

    protected abstract String getConfigType();

    protected String getConfigKeyForClass() {
        return method.getDeclaringClass().getName() + "/" + getConfigType() + "/";
    }

    protected Config getConfig() {
        return ConfigProvider.getConfig();
    }


    protected abstract Map<String, Class<?>> getKeysToType();

    private final Method method;


    private final X annotation;

}
