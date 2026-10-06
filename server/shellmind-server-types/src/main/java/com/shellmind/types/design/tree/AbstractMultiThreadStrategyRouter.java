package com.shellmind.types.design.tree;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Strategy-tree node base: first runs {@link #multiThread} for async preload, then {@link #doApply}
 * for this node. Inside the node, {@link #router} forwards the request to the next node from {@link #get}.
 *
 * @param <T> request parameter
 * @param <D> dynamic context shared across nodes
 * @param <R> return result
 */
public abstract class AbstractMultiThreadStrategyRouter<T, D, R>
        implements StrategyMapper<T, D, R>, StrategyHandler<T, D, R> {

    @SuppressWarnings("unchecked")
    protected StrategyHandler<T, D, R> defaultStrategyHandler = StrategyHandler.DEFAULT;

    /**
     * Route to the next node; if there is none, use the default handler.
     */
    public R router(T request, D context) throws Exception {
        StrategyHandler<T, D, R> next = get(request, context);
        if (next != null) {
            return next.apply(request, context);
        }
        return defaultStrategyHandler.apply(request, context);
    }

    @Override
    public R apply(T request, D context) throws Exception {
        multiThread(request, context);
        return doApply(request, context);
    }

    /** Async preload (may be a no-op). */
    protected abstract void multiThread(T request, D context)
            throws ExecutionException, InterruptedException, TimeoutException;

    /** Processing logic for this node. */
    protected abstract R doApply(T request, D context) throws Exception;

    public StrategyHandler<T, D, R> getDefaultStrategyHandler() {
        return defaultStrategyHandler;
    }

    public void setDefaultStrategyHandler(StrategyHandler<T, D, R> defaultStrategyHandler) {
        this.defaultStrategyHandler = defaultStrategyHandler;
    }
}
