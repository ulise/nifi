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
package org.apache.nifi.c2.client.service;

import java.util.List;
import org.apache.nifi.c2.client.api.C2Client;
import org.apache.nifi.c2.client.service.model.RuntimeInfoWrapper;
import org.apache.nifi.c2.client.service.operation.C2OperationService;
import org.apache.nifi.c2.protocol.api.C2Heartbeat;
import org.apache.nifi.c2.protocol.api.C2HeartbeatResponse;
import org.apache.nifi.c2.protocol.api.C2Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class C2ClientService {

    private static final Logger logger = LoggerFactory.getLogger(C2ClientService.class);

    private final C2Client client;
    private final C2HeartbeatFactory c2HeartbeatFactory;
    private final C2OperationService operationService;

    public C2ClientService(C2Client client, C2HeartbeatFactory c2HeartbeatFactory, C2OperationService operationService) {
        this.client = client;
        this.c2HeartbeatFactory = c2HeartbeatFactory;
        this.operationService = operationService;
    }

    public void sendHeartbeat(RuntimeInfoWrapper runtimeInfoWrapper) {
        try {
            C2Heartbeat c2Heartbeat = c2HeartbeatFactory.create(runtimeInfoWrapper);
            client.publishHeartbeat(c2Heartbeat).ifPresent(this::processResponse);
        } catch (Exception e) {
            logger.error("Failed to send/process heartbeat:", e);
        }
    }

    private void processResponse(C2HeartbeatResponse response) {
        List<C2Operation> requestedOperations = response.getRequestedOperations();
        if (requestedOperations != null && !requestedOperations.isEmpty()) {
            logger.info("Received {} operations from the C2 server", requestedOperations.size());
            handleRequestedOperations(requestedOperations);
        } else {
            logger.trace("No operations received from the C2 server in the server. Nothing to do.");
        }
    }

    private void handleRequestedOperations(List<C2Operation> requestedOperations) {
        for (C2Operation requestedOperation : requestedOperations) {
            operationService.handleOperation(requestedOperation)
                .ifPresent(client::acknowledgeOperation);
        }
    }
}

