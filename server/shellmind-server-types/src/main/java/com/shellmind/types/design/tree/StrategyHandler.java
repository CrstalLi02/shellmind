package com.shellmind.types.design.tree;

/**
 * Strategy-tree node: processes a request and returns a result.
 *
 * @param <T> request parameter
 * @param <D> dynamic context shared across nodes
 * @param <R> return result
 */
@FunctionalInterface
public interface StrategyHandler<T, D, R> {

    /** Default handler: no-op, returns null (end of the chain). */
    @SuppressWarnings("rawtypes")
    StrategyHandler DEFAULT = (request, context) -> null;

    R apply(T request, D context) throws Exception;
}
