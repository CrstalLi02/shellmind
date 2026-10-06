package com.shellmind.types.design.tree;

/**
 * Strategy mapper: selects the next node from the request and context.
 */
@FunctionalInterface
public interface StrategyMapper<T, D, R> {

    /**
     * @return the next handler; when null, the router uses the default handler
     */
    StrategyHandler<T, D, R> get(T request, D context) throws Exception;
}
