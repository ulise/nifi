/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.elasticsearch.api;

import org.apache.nifi.components.DescribedValue;

import java.util.Arrays;

public enum PaginationType implements DescribedValue {
    SCROLL("pagination-scroll", "Use Elasticsearch \"scroll\" to page results."),
    SEARCH_AFTER("pagination-search_after", "Use Elasticsearch \"search_after\" to page sorted results."),
    POINT_IN_TIME("pagination-pit", "Use Elasticsearch (7.10+ with XPack) \"point in time\" to page sorted results.");

    private final String value;
    private final String description;

    PaginationType(final String value, final String description) {
        this.value = value;
        this.description = description;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public String getDisplayName() {
        return name();
    }

    @Override
    public String getDescription() {
        return description;
    }

    public static PaginationType fromValue(final String value) {
        return Arrays.stream(PaginationType.values()).filter(v -> v.getValue().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Unknown value %s", value)));
    }
}
