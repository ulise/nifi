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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.nifi.NullSuppression;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.json.OutputGrouping;
import org.apache.nifi.json.WriteJsonResult;
import org.apache.nifi.oauth2.OAuth2AccessTokenProvider;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processors.salesforce.util.RecordExtender;
import org.apache.nifi.processors.salesforce.util.SalesforceRestService;
import org.apache.nifi.schema.access.NopSchemaAccessWriter;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.nifi.processors.salesforce.util.CommonSalesforceProperties.API_URL;
import static org.apache.nifi.processors.salesforce.util.CommonSalesforceProperties.API_VERSION;
import static org.apache.nifi.processors.salesforce.util.CommonSalesforceProperties.READ_TIMEOUT;
import static org.apache.nifi.processors.salesforce.util.CommonSalesforceProperties.TOKEN_PROVIDER;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"salesforce", "sobject", "put"})
@CapabilityDescription("Creates new records for the specified Salesforce sObject. The type of the Salesforce object must be set in the input flowfile's"
        + " 'objectType' attribute. This processor cannot update existing records.")
@ReadsAttribute(attribute = "objectType", description = "The Salesforce object type to upload records to. E.g. Account, Contact, Campaign.")
public class PutSalesforceObject extends AbstractProcessor {

    private static final int MAX_RECORD_COUNT = 200;

    protected static final PropertyDescriptor RECORD_READER_FACTORY = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description(
                    "Specifies the Controller Service to use for parsing incoming data and determining the data's schema")
            .identifiesControllerService(RecordReaderFactory.class)
            .required(true)
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("For FlowFiles created as a result of a successful execution.")
            .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("For FlowFiles created as a result of an execution error.")
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            API_URL,
            API_VERSION,
            READ_TIMEOUT,
            TOKEN_PROVIDER,
            RECORD_READER_FACTORY
    ));

    private static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            REL_SUCCESS,
            REL_FAILURE
    )));

    private volatile SalesforceRestService salesforceRestService;
    private volatile int maxRecordCount;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        maxRecordCount = getMaxRecordCount();

        String salesforceVersion = context.getProperty(API_VERSION).getValue();
        String baseUrl = context.getProperty(API_URL).getValue();
        OAuth2AccessTokenProvider accessTokenProvider =
                context.getProperty(TOKEN_PROVIDER).asControllerService(OAuth2AccessTokenProvider.class);

        salesforceRestService = new SalesforceRestService(
                salesforceVersion,
                baseUrl,
                () -> accessTokenProvider.getAccessDetails().getAccessToken(),
                context.getProperty(READ_TIMEOUT).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS)
                        .intValue()
        );
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        String objectType = flowFile.getAttribute("objectType");
        if (objectType == null) {
            throw new ProcessException("Salesforce object type not found among the incoming flowfile attributes");
        }

        RecordReaderFactory readerFactory = context.getProperty(RECORD_READER_FACTORY).asControllerService(RecordReaderFactory.class);

        RecordExtender extender;

        try (InputStream in = session.read(flowFile);
             RecordReader reader = readerFactory.createRecordReader(flowFile, in, getLogger());
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             WriteJsonResult writer = getWriter(extender = new RecordExtender(reader.getSchema()), out)) {

            int count = 0;
            Record record;

            while ((record = reader.nextRecord()) != null) {
                count++;
                if (!writer.isActiveRecordSet()) {
                    writer.beginRecordSet();
                }

                MapRecord extendedRecord = extender.getExtendedRecord(objectType, count, record);
                writer.write(extendedRecord);

                if (count == maxRecordCount) {
                    count = 0;
                    processRecords(objectType, out, writer, extender);
                    out.reset();
                }
            }

            if (writer.isActiveRecordSet()) {
                processRecords(objectType, out, writer, extender);
            }
            session.transfer(flowFile, REL_SUCCESS);

        } catch (MalformedRecordException e) {
            getLogger().error("Couldn't read records from input", e);
            session.transfer(flowFile, REL_FAILURE);
        } catch (SchemaNotFoundException e) {
            getLogger().error("Couldn't create record writer", e);
            session.transfer(flowFile, REL_FAILURE);
        } catch (Exception e) {
            getLogger().error("Failed to put records to Salesforce.", e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }

    private void processRecords(String objectType, ByteArrayOutputStream out, WriteJsonResult writer, RecordExtender extender) throws IOException {
        writer.finishRecordSet();
        writer.flush();
        ObjectNode wrappedJson = extender.getWrappedRecordsJson(out);
        salesforceRestService.postRecord(objectType, wrappedJson.toPrettyString());
    }

    private WriteJsonResult getWriter(RecordExtender extender, ByteArrayOutputStream out) throws IOException {
        final RecordSchema extendedSchema = extender.getExtendedSchema();
        return new WriteJsonResult(getLogger(), extendedSchema, new NopSchemaAccessWriter(), out,
                true, NullSuppression.NEVER_SUPPRESS, OutputGrouping.OUTPUT_ARRAY, null, null, null);
    }

    int getMaxRecordCount() {
        return MAX_RECORD_COUNT;
    }
}
