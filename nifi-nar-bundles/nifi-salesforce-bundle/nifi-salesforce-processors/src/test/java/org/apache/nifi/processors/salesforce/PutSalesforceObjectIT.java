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
package org.apache.nifi.processors.salesforce;

import org.apache.nifi.oauth2.StandardOauth2AccessTokenProvider;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processors.salesforce.util.CommonSalesforceProperties;
import org.apache.nifi.processors.salesforce.util.SalesforceConfigAware;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PutSalesforceObjectIT implements SalesforceConfigAware {

    private TestRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        Processor putSalesforceObject = new PutSalesforceObject() {
            @Override
            int getMaxRecordCount() {
                return 2;
            }
        };

        runner = TestRunners.newTestRunner(putSalesforceObject);

        StandardOauth2AccessTokenProvider oauth2AccessTokenProvider = initOAuth2AccessTokenProvider(runner);
        runner.setProperty(CommonSalesforceProperties.TOKEN_PROVIDER, oauth2AccessTokenProvider.getIdentifier());
    }

    @Test
    void testPutSalesforceObject() throws Exception {

        MockRecordParser reader = new MockRecordParser();
        reader.addSchemaField("name", RecordFieldType.STRING);
        reader.addSchemaField("phone", RecordFieldType.STRING);
        reader.addSchemaField("website", RecordFieldType.STRING);
        reader.addSchemaField("numberOfEmployees", RecordFieldType.STRING);
        reader.addSchemaField("industry", RecordFieldType.STRING);

        reader.addRecord("SampleAccount1", "111111", "www.salesforce1.com", "100", "Banking");
        reader.addRecord("SampleAccount2", "222222", "www.salesforce2.com", "200", "Banking");
        reader.addRecord("SampleAccount3", "333333", "www.salesforce3.com", "300", "Banking");
        reader.addRecord("SampleAccount4", "444444", "www.salesforce4.com", "400", "Banking");
        reader.addRecord("SampleAccount5", "555555", "www.salesforce5.com", "500", "Banking");

        runner.enqueue("", Collections.singletonMap("objectType", "Account"));

        runner.addControllerService("reader", reader);
        runner.enableControllerService(reader);

        runner.setProperty(CommonSalesforceProperties.API_VERSION, VERSION);
        runner.setProperty(CommonSalesforceProperties.API_URL, BASE_URL);
        runner.setProperty(PutSalesforceObject.RECORD_READER_FACTORY, reader.getIdentifier());

        runner.run();

        List<MockFlowFile> results = runner.getFlowFilesForRelationship(PutSalesforceObject.REL_SUCCESS);

        assertEquals(1, results.size());
    }
}
