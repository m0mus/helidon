/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.dbclient.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.helidon.common.reactive.GetSubscriber;
import io.helidon.dbclient.DbRow;
import io.helidon.dbclient.DbRows;
import io.helidon.dbclient.DbStatementGet;

/**
 * A JDBC get implementation.
 * Delegates to {@link io.helidon.dbclient.jdbc.JdbcStatementQuery} and processes the result using a subscriber
 * to read the first value.
 */
class JdbcStatementGet implements DbStatementGet {
    private final JdbcStatementQuery query;

    JdbcStatementGet(JdbcExecuteContext executeContext,
                     JdbcStatementContext statementContext) {

        this.query = new JdbcStatementQuery(executeContext,
                                            statementContext);
    }

    @Override
    public JdbcStatementGet params(List<?> parameters) {
        query.params(parameters);
        return this;
    }

    @Override
    public JdbcStatementGet params(Map<String, ?> parameters) {
        query.params(parameters);
        return this;
    }

    @Override
    public JdbcStatementGet namedParam(Object parameters) {
        query.namedParam(parameters);
        return this;
    }

    @Override
    public JdbcStatementGet indexedParam(Object parameters) {
        query.indexedParam(parameters);
        return this;
    }

    @Override
    public JdbcStatementGet addParam(Object parameter) {
        query.addParam(parameter);
        return this;
    }

    @Override
    public JdbcStatementGet addParam(String name, Object parameter) {
        query.addParam(name, parameter);
        return this;
    }

    @Override
    public CompletionStage<Optional<DbRow>> execute() {
        CompletableFuture<Optional<DbRow>> result = new CompletableFuture<>();

        query.execute()
                .thenApply(DbRows::publisher)
                .thenAccept(publisher -> {
                    publisher.subscribe(GetSubscriber.create(result, "Received more than one results for query "
                            + query.name()));
                })
                .exceptionally(throwable -> {
                    result.completeExceptionally(throwable);
                    return null;
                });

        return result;
    }
}