/*
 * Copyright (c) 2023, 2025 Oracle and/or its affiliates.
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
package io.helidon.microprofile.telemetry;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.enterprise.inject.spi.CDI;

@SuppressWarnings("NullableProblems")
public class InMemorySpanExporterProvider implements ConfigurableSpanExporterProvider {
    @Override
    public SpanExporter createExporter(final ConfigProperties config) {
        return CDI.current().select(InMemorySpanExporter.class).get();
    }

    @Override
    public String getName() {
        return "in-memory";
    }
}
