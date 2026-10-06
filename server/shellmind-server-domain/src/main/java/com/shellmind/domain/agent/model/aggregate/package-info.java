/**
 * Aggregate objects.
 * 1. Aggregate entities and value objects
 * 2. An aggregate is the clustered object and provides basic handling methods. Do not pull repositories and ports into the aggregate for heavy logic; those complex operations belong in a service
 * 3. Naming convention: XxxAggregate
 */
package com.shellmind.domain.agent.model.aggregate;
