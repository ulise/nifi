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

package org.apache.nifi.web.dao.impl;

import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.registry.flow.FlowRegistryBucket;
import org.apache.nifi.registry.flow.FlowRegistryClientUserContext;
import org.apache.nifi.registry.flow.FlowRegistryClientNode;
import org.apache.nifi.registry.flow.FlowRegistryException;
import org.apache.nifi.registry.flow.RegisteredFlow;
import org.apache.nifi.registry.flow.RegisteredFlowSnapshotMetadata;
import org.apache.nifi.util.BundleUtils;
import org.apache.nifi.web.NiFiCoreException;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.web.api.dto.FlowRegistryClientDTO;
import org.apache.nifi.web.dao.FlowRegistryDAO;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public class StandardFlowRegistryDAO extends ComponentDAO implements FlowRegistryDAO {
    private FlowController flowController;

    @Override
    public FlowRegistryClientNode createFlowRegistryClient(final FlowRegistryClientDTO flowRegistryClientDto) {
        // ensure the type is specified
        if (flowRegistryClientDto.getType() == null) {
            throw new IllegalArgumentException("The flow registry client type must be specified.");
        }

        verifyCreate(flowController.getExtensionManager(),  flowRegistryClientDto.getType(), flowRegistryClientDto.getBundle());

        final BundleCoordinate bundleCoordinate = BundleUtils.getBundle(flowController.getExtensionManager(), flowRegistryClientDto.getType(), flowRegistryClientDto.getBundle());
        final FlowRegistryClientNode flowRegistryClient = flowController.getFlowManager().createFlowRegistryClient(
                flowRegistryClientDto.getType(), flowRegistryClientDto.getId(), bundleCoordinate, Collections.emptySet(), true, true, null);

        configureFlowRegistry(flowRegistryClient, flowRegistryClientDto);

        return flowRegistryClient;
    }

    @Override
    public FlowRegistryClientNode updateFlowRegistryClient(final FlowRegistryClientDTO flowRegistryClientDto) {
        final FlowRegistryClientNode client = getFlowRegistryClient(flowRegistryClientDto.getId());

        // ensure we can perform the update
        verifyUpdate(client, flowRegistryClientDto);

        // perform the update
        configureFlowRegistry(client, flowRegistryClientDto);

        return client;
    }

    private void verifyUpdate(final FlowRegistryClientNode client, final FlowRegistryClientDTO flowRegistryClientDto) {
        final boolean duplicateName = getFlowRegistryClients().stream()
                .anyMatch(reg -> reg.getName().equals(flowRegistryClientDto.getName()) && !reg.getIdentifier().equals(flowRegistryClientDto.getId()));

        if (duplicateName) {
            throw new IllegalStateException("Cannot update Flow Registry because a Flow Registry already exists with the name " + flowRegistryClientDto.getName());
        }
    }


    @Override
    public FlowRegistryClientNode getFlowRegistryClient(final String registryId) {
        final FlowRegistryClientNode registry = flowController.getFlowManager().getFlowRegistryClient(registryId);

        if (registry == null) {
            throw new ResourceNotFoundException("Unable to find Flow Registry with id '" + registryId + "'");
        }

        return registry;
    }

    @Override
    public Set<FlowRegistryClientNode> getFlowRegistryClients() {
        return flowController.getFlowManager().getAllFlowRegistryClients();
    }

    @Override
    public Set<FlowRegistryClientNode> getFlowRegistryClientsForUser(final FlowRegistryClientUserContext context) {
        return getFlowRegistryClients();
    }

    @Override
    public Set<FlowRegistryBucket> getBucketsForUser(final FlowRegistryClientUserContext context, final String registryId) {
        try {
            final FlowRegistryClientNode flowRegistry = flowController.getFlowManager().getFlowRegistryClient(registryId);

            if (flowRegistry == null) {
                throw new IllegalArgumentException("The specified registry id is unknown to this NiFi.");
            }

            final Set<FlowRegistryBucket> buckets = flowRegistry.getBuckets(context);
            final Set<FlowRegistryBucket> sortedBuckets = new TreeSet<>((b1, b2) -> b1.getName().compareTo(b2.getName()));
            sortedBuckets.addAll(buckets);
            return sortedBuckets;
        } catch (final FlowRegistryException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (final IOException ioe) {
            throw new NiFiCoreException("Unable to obtain listing of buckets: " + ioe, ioe);
        }
    }

    @Override
    public Set<RegisteredFlow> getFlowsForUser(final FlowRegistryClientUserContext context, final String registryId, final String bucketId) {
        try {
            final FlowRegistryClientNode flowRegistry = flowController.getFlowManager().getFlowRegistryClient(registryId);
            if (flowRegistry == null) {
                throw new IllegalArgumentException("The specified registry id is unknown to this NiFi.");
            }

            final Set<RegisteredFlow> flows = flowRegistry.getFlows(context, bucketId);
            final Set<RegisteredFlow> sortedFlows = new TreeSet<>((f1, f2) -> f1.getName().compareTo(f2.getName()));
            sortedFlows.addAll(flows);
            return sortedFlows;
        } catch (final IOException | FlowRegistryException ioe) {
            throw new NiFiCoreException("Unable to obtain listing of flows for bucket with ID " + bucketId + ": " + ioe, ioe);
        }
    }

    @Override
    public Set<RegisteredFlowSnapshotMetadata> getFlowVersionsForUser(final FlowRegistryClientUserContext context, final String registryId, final String bucketId, final String flowId) {
        try {
            final FlowRegistryClientNode flowRegistry = flowController.getFlowManager().getFlowRegistryClient(registryId);
            if (flowRegistry == null) {
                throw new IllegalArgumentException("The specified registry id is unknown to this NiFi.");
            }

            final Set<RegisteredFlowSnapshotMetadata> flowVersions = flowRegistry.getFlowVersions(context, bucketId, flowId);
            final Set<RegisteredFlowSnapshotMetadata> sortedFlowVersions = new TreeSet<>((f1, f2) -> Integer.compare(f1.getVersion(), f2.getVersion()));
            sortedFlowVersions.addAll(flowVersions);
            return sortedFlowVersions;
        } catch (final IOException | FlowRegistryException ioe) {
            throw new NiFiCoreException("Unable to obtain listing of versions for bucket with ID " + bucketId + " and flow with ID " + flowId + ": " + ioe, ioe);
        }
    }

    @Override
    public FlowRegistryClientNode removeFlowRegistry(final String registryId) {
        final FlowRegistryClientNode flowRegistry = flowController.getFlowManager().getFlowRegistryClient(registryId);
        if (flowRegistry == null) {
            throw new IllegalArgumentException("The specified registry id is unknown to this NiFi.");
        }

        flowController.getFlowManager().removeFlowRegistryClientNode(flowRegistry);

        return flowRegistry;
    }

    public void setFlowController(final FlowController flowController) {
        this.flowController = flowController;
    }

    private void configureFlowRegistry(final FlowRegistryClientNode node, final FlowRegistryClientDTO dto) {
        final String name = dto.getName();
        final String description = dto.getDescription();
        final Map<String, String> properties = dto.getProperties();

        node.pauseValidationTrigger();

        try {
            if (isNotNull(name)) {
                node.setName(name);
            }

            if (isNotNull(description)) {
                node.setDescription(description);
            }

            if (isNotNull(properties)) {
                final Set<String> sensitiveDynamicPropertyNames = Optional.ofNullable(dto.getSensitiveDynamicPropertyNames()).orElse(Collections.emptySet());
                node.setProperties(properties, false, sensitiveDynamicPropertyNames);
            }
        } finally {
            node.resumeValidationTrigger();
        }
    }
}
