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

package org.apache.nifi.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.type.ArrayDataType;
import org.apache.nifi.serialization.record.type.ChoiceDataType;
import org.apache.nifi.serialization.record.type.MapDataType;
import org.apache.nifi.serialization.record.type.RecordDataType;
import org.apache.nifi.serialization.record.util.DataTypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

public abstract class AbstractJsonRowRecordReader implements RecordReader {

    private final ComponentLog logger;
    private final Supplier<DateFormat> LAZY_DATE_FORMAT;
    private final Supplier<DateFormat> LAZY_TIME_FORMAT;
    private final Supplier<DateFormat> LAZY_TIMESTAMP_FORMAT;

    private boolean firstObjectConsumed = false;

    private static final JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper codec = new ObjectMapper();
    private JsonParser jsonParser;
    private JsonNode firstJsonNode;
    private StartingFieldStrategy strategy;

    private Map<String, String> capturedFields;
    private BiPredicate<String, String> captureFieldPredicate;

    private AbstractJsonRowRecordReader(final ComponentLog logger, final String dateFormat, final String timeFormat, final String timestampFormat) {
        this.logger = logger;

        final DateFormat df = dateFormat == null ? null : DataTypeUtils.getDateFormat(dateFormat);
        final DateFormat tf = timeFormat == null ? null : DataTypeUtils.getDateFormat(timeFormat);
        final DateFormat tsf = timestampFormat == null ? null : DataTypeUtils.getDateFormat(timestampFormat);

        LAZY_DATE_FORMAT = () -> df;
        LAZY_TIME_FORMAT = () -> tf;
        LAZY_TIMESTAMP_FORMAT = () -> tsf;
    }

    protected AbstractJsonRowRecordReader(final InputStream in,
                                          final ComponentLog logger,
                                          final String dateFormat,
                                          final String timeFormat,
                                          final String timestampFormat)
            throws IOException, MalformedRecordException {

        this(in, logger, dateFormat, timeFormat, timestampFormat, null, null, null);
    }

    /**
     * Constructor with initial logic for JSON to NiFi record parsing.
     *
     * @param in                     the input stream to parse
     * @param logger                 ComponentLog
     * @param dateFormat             format for parsing date fields
     * @param timeFormat             format for parsing time fields
     * @param timestampFormat        format for parsing timestamp fields
     * @param strategy               whether to start processing from a specific field
     * @param nestedFieldName        the name of the field to start the processing from
     * @param captureFieldPredicate predicate that takes a JSON fieldName and fieldValue to capture top-level non-processed fields which can
     *                               be accessed by calling {@link #getCapturedFields()}
     * @throws IOException              in case of JSON stream processing failure
     * @throws MalformedRecordException in case of malformed JSON input
     */
    protected AbstractJsonRowRecordReader(final InputStream in,
                                          final ComponentLog logger,
                                          final String dateFormat,
                                          final String timeFormat,
                                          final String timestampFormat,
                                          final StartingFieldStrategy strategy,
                                          final String nestedFieldName,
                                          final BiPredicate<String, String> captureFieldPredicate)
            throws IOException, MalformedRecordException {

        this(logger, dateFormat, timeFormat, timestampFormat);

        this.strategy = strategy;
        this.captureFieldPredicate = captureFieldPredicate;
        capturedFields = new LinkedHashMap<>();

        try {
            jsonParser = jsonFactory.createParser(in);
            jsonParser.setCodec(codec);

            if (strategy == StartingFieldStrategy.NESTED_FIELD) {
                while (jsonParser.nextToken() != null) {
                    if (nestedFieldName.equals(jsonParser.getCurrentName())) {
                        logger.debug("Parsing starting at nested field [{}]", nestedFieldName);
                        break;
                    }
                    if (captureFieldPredicate != null) {
                        captureCurrentField(captureFieldPredicate);
                    }
                }
            }

            JsonToken token = jsonParser.nextToken();
            if (token == JsonToken.START_ARRAY) {
                token = jsonParser.nextToken(); // advance to START_OBJECT token
            }
            if (token == JsonToken.START_OBJECT) { // could be END_ARRAY also
                firstJsonNode = jsonParser.readValueAsTree();
            } else {
                firstJsonNode = null;
            }
        } catch (final JsonParseException e) {
            throw new MalformedRecordException("Could not parse data as JSON", e);
        }
    }

    protected Supplier<DateFormat> getLazyDateFormat() {
        return LAZY_DATE_FORMAT;
    }

    protected Supplier<DateFormat> getLazyTimeFormat() {
        return LAZY_TIME_FORMAT;
    }

    protected Supplier<DateFormat> getLazyTimestampFormat() {
        return LAZY_TIMESTAMP_FORMAT;
    }


    @Override
    public Record nextRecord(final boolean coerceTypes, final boolean dropUnknownFields) throws IOException, MalformedRecordException {
        final JsonNode nextNode = getNextJsonNode();
        if (nextNode == null) {
            if (captureFieldPredicate != null) {
                while (jsonParser.nextToken() != null) {
                    captureCurrentField(captureFieldPredicate);
                }
            }
            return null;
        }

        final RecordSchema schema = getSchema();
        try {
            return convertJsonNodeToRecord(nextNode, schema, coerceTypes, dropUnknownFields);
        } catch (final MalformedRecordException mre) {
            throw mre;
        } catch (final Exception e) {
            logger.debug("Failed to convert JSON Element {} into a Record object using schema {} due to {}", nextNode, schema, e.toString(), e);
            throw new MalformedRecordException("Successfully parsed a JSON object from input but failed to convert into a Record object with the given schema", e);
        }
    }

    protected Object getRawNodeValue(final JsonNode fieldNode, final String fieldName) throws IOException {
        return getRawNodeValue(fieldNode, null, fieldName);
    }

    protected Object getRawNodeValue(final JsonNode fieldNode, final DataType dataType, final String fieldName) throws IOException {
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }

        if (fieldNode.isNumber()) {
            return fieldNode.numberValue();
        }

        if (fieldNode.isBinary()) {
            return fieldNode.binaryValue();
        }

        if (fieldNode.isBoolean()) {
            return fieldNode.booleanValue();
        }

        if (fieldNode.isTextual()) {
            final String textValue = fieldNode.textValue();
            if (dataType == null) {
                return textValue;
            }

            switch (dataType.getFieldType()) {
                case DATE:
                case TIME:
                case TIMESTAMP:
                    try {
                        return DataTypeUtils.convertType(textValue, dataType, LAZY_DATE_FORMAT, LAZY_TIME_FORMAT, LAZY_TIMESTAMP_FORMAT, fieldName);
                    } catch (final Exception e) {
                        return textValue;
                    }
                default:
                    return textValue;
            }
        }

        if (fieldNode.isArray()) {
            final ArrayNode arrayNode = (ArrayNode) fieldNode;
            final int numElements = arrayNode.size();
            final Object[] arrayElements = new Object[numElements];
            int count = 0;

            final DataType elementDataType;
            if (dataType != null && dataType.getFieldType() == RecordFieldType.ARRAY) {
                final ArrayDataType arrayDataType = (ArrayDataType) dataType;
                elementDataType = arrayDataType.getElementType();
            } else if (dataType != null && dataType.getFieldType() == RecordFieldType.CHOICE) {
                List<DataType> possibleSubTypes = ((ChoiceDataType) dataType).getPossibleSubTypes();

                for (DataType possibleSubType : possibleSubTypes) {
                    if (possibleSubType.getFieldType() == RecordFieldType.ARRAY) {
                        ArrayDataType possibleArrayDataType = (ArrayDataType) possibleSubType;
                        DataType possibleElementType = possibleArrayDataType.getElementType();

                        final Object[] possibleArrayElements = new Object[numElements];
                        int elementCounter = 0;
                        for (final JsonNode node : arrayNode) {
                            final Object value = getRawNodeValue(node, possibleElementType, fieldName);
                            possibleArrayElements[elementCounter++] = value;
                        }

                        if (DataTypeUtils.isArrayTypeCompatible(possibleArrayElements, possibleElementType, true)) {
                            return possibleArrayElements;
                        }
                    }
                }

                logger.debug("Couldn't find proper schema for '{}'. This could lead to some fields filtered out.", fieldName);

                elementDataType = dataType;
            } else {
                elementDataType = dataType;
            }

            for (final JsonNode node : arrayNode) {
                final Object value = getRawNodeValue(node, elementDataType, fieldName);
                arrayElements[count++] = value;
            }

            return arrayElements;
        }

        if (fieldNode.isObject()) {
            if (dataType != null && RecordFieldType.MAP == dataType.getFieldType()) {
                return getMapFromRawValue(fieldNode, dataType, fieldName);
            }

            return getRecordFromRawValue(fieldNode, dataType);
        }

        return null;
    }

    private void captureCurrentField(BiPredicate<String, String> captureFieldPredicate) throws IOException {
        if (jsonParser.getCurrentToken() == JsonToken.FIELD_NAME) {
            jsonParser.nextToken();

            final String fieldName = jsonParser.getCurrentName();
            final String fieldValue = jsonParser.getValueAsString();

            if (captureFieldPredicate.test(fieldName, fieldValue)) {
                capturedFields.put(fieldName, fieldValue);
            }
        }
    }

    private Map<String, Object> getMapFromRawValue(final JsonNode fieldNode, final DataType dataType, final String fieldName) throws IOException {
        if (dataType == null || dataType.getFieldType() != RecordFieldType.MAP) {
            return null;
        }

        final MapDataType mapDataType = (MapDataType) dataType;
        final DataType valueType = mapDataType.getValueType();

        final Map<String, Object> mapValue = new LinkedHashMap<>();

        final Iterator<Map.Entry<String, JsonNode>> fieldItr = fieldNode.fields();
        while (fieldItr.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fieldItr.next();
            final String elementName = entry.getKey();
            final JsonNode elementNode = entry.getValue();

            final Object nodeValue = getRawNodeValue(elementNode, valueType, fieldName + "['" + elementName + "']");
            mapValue.put(elementName, nodeValue);
        }

        return mapValue;
    }

    private Record getRecordFromRawValue(final JsonNode fieldNode, final DataType dataType) throws IOException {
        RecordSchema childSchema = null;
        if (dataType != null && RecordFieldType.RECORD == dataType.getFieldType()) {
            final RecordDataType recordDataType = (RecordDataType) dataType;
            childSchema = recordDataType.getChildSchema();
        } else if (dataType != null && RecordFieldType.CHOICE == dataType.getFieldType()) {
            final ChoiceDataType choiceDataType = (ChoiceDataType) dataType;

            List<DataType> possibleSubTypes = choiceDataType.getPossibleSubTypes();

            for (final DataType possibleDataType : possibleSubTypes) {
                final Record record = createOptionalRecord(fieldNode, possibleDataType, true);
                if (record != null) {
                    return record;
                }
            }

            for (final DataType possibleDataType : possibleSubTypes) {
                final Record record = createOptionalRecord(fieldNode, possibleDataType, false);
                if (record != null) {
                    return record;
                }
            }
        }

        if (childSchema == null) {
            childSchema = new SimpleRecordSchema(Collections.emptyList());
        }

        return createRecordFromRawValue(fieldNode, childSchema);
    }

    private Record createOptionalRecord(final JsonNode fieldNode, final DataType dataType, final boolean strict) throws IOException {
        if (dataType.getFieldType() == RecordFieldType.RECORD) {
            final RecordSchema possibleSchema = ((RecordDataType) dataType).getChildSchema();
            final Record possibleRecord = createRecordFromRawValue(fieldNode, possibleSchema);

            if (DataTypeUtils.isCompatibleDataType(possibleRecord, dataType, strict)) {
                return possibleRecord;
            }
        } else if (dataType.getFieldType() == RecordFieldType.ARRAY) {
            final ArrayDataType arrayDataType = (ArrayDataType) dataType;
            final DataType elementType = arrayDataType.getElementType();
            return createOptionalRecord(fieldNode, elementType, strict);
        }

        return null;
    }

    private Record createRecordFromRawValue(final JsonNode fieldNode, final RecordSchema childSchema) throws IOException {
        final Iterator<String> fieldNames = fieldNode.fieldNames();
        final Map<String, Object> childValues = new LinkedHashMap<>();
        while (fieldNames.hasNext()) {
            final String childFieldName = fieldNames.next();

            final DataType childDataType = childSchema.getDataType(childFieldName).orElse(null);
            final Object childValue = getRawNodeValue(fieldNode.get(childFieldName), childDataType, childFieldName);
            childValues.put(childFieldName, childValue);
        }

        return new MapRecord(childSchema, childValues);
    }

    protected JsonNode getNextJsonNode() throws IOException, MalformedRecordException {
        if (!firstObjectConsumed) {
            firstObjectConsumed = true;
            return firstJsonNode;
        }
        if (strategy == StartingFieldStrategy.NESTED_FIELD) {
            return getJsonNodeWithNestedNodeStrategy();
        } else {
            return getJsonNode();
        }

    }

    private JsonNode getJsonNodeWithNestedNodeStrategy() throws IOException, MalformedRecordException {
        while (true) {
            final JsonToken token = jsonParser.nextToken();
            if (token == null) {
                return null;
            }

            switch (token) {
                case START_ARRAY:
                    break;
                case END_ARRAY:
                case END_OBJECT:
                case FIELD_NAME:
                    return null;
                case START_OBJECT:
                    return jsonParser.readValueAsTree();
                default:
                    throw new MalformedRecordException("Expected to get a JSON Object but got a token of type " + token.name());
            }
        }
    }

    private JsonNode getJsonNode() throws IOException, MalformedRecordException {
        while (true) {
            final JsonToken token = jsonParser.nextToken();
            if (token == null) {
                return null;
            }

            switch (token) {
                case START_ARRAY:
                case END_ARRAY:
                case END_OBJECT:
                    break;
                case START_OBJECT:
                    return jsonParser.readValueAsTree();
                default:
                    throw new MalformedRecordException("Expected to get a JSON Object but got a token of type " + token.name());
            }
        }
    }

    @Override
    public void close() throws IOException {
        jsonParser.close();
    }

    protected abstract Record convertJsonNodeToRecord(JsonNode nextNode, RecordSchema schema, boolean coerceTypes, boolean dropUnknownFields) throws IOException, MalformedRecordException;


    public Map<String, String> getCapturedFields() {
        return capturedFields;
    }
}
