package jbro.cobblemon.bettermusic.integration.mbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

final class ReflectiveMbcContentLookup {
    private final Object clientApi;
    private final Method contentIdMethod;

    private ReflectiveMbcContentLookup(Object clientApi, Method contentIdMethod) {
        this.clientApi = clientApi;
        this.contentIdMethod = contentIdMethod;
    }

    static ReflectiveMbcContentLookup load(ClassLoader classLoader, String clientApiClassName)
        throws ReflectiveOperationException {
        Class<?> clientApiClass = Class.forName(clientApiClassName, true, classLoader);
        Object clientApi = clientApiClass.getField("INSTANCE").get(null);
        Method contentIdMethod = clientApiClass.getMethod("contentId", UUID.class);
        return new ReflectiveMbcContentLookup(clientApi, contentIdMethod);
    }

    Optional<String> contentId(UUID battleId) {
        try {
            Object result = contentIdMethod.invoke(clientApi, battleId);
            if (result instanceof String contentId && !contentId.isBlank()) {
                return Optional.of(contentId);
            }
            return Optional.empty();
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("MBC content ID API is no longer accessible", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MBC content ID lookup failed", cause);
        }
    }
}
