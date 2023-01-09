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
package org.apache.nifi.processors.script;

import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.script.ScriptingComponentUtils;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.MockProcessContext;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestInvokeJython extends BaseScriptTest {

    /**
     * Copies all scripts to the target directory because when they are compiled they can leave unwanted .class files.
     *
     * @throws Exception Any error encountered while testing
     */
    @BeforeEach
    public void setup() throws Exception {
        super.setupInvokeScriptProcessor();
    }

    /**
     * Tests a script that has a Jython processor that is always invalid.
     *
     */
    @Test
    public void testAlwaysInvalid() {
        final TestRunner runner = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        runner.setValidateExpressionUsage(false);
        runner.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_invalid.py");

        final Collection<ValidationResult> results = ((MockProcessContext) runner.getProcessContext()).validate();
        assertEquals(1L, results.size());
        assertEquals("Never valid.", results.iterator().next().getExplanation());
    }

    /**
     * Tests a script that has a Jython processor that begins invalid then is fixed.
     *
     */
    @Test
    public void testInvalidThenFixed() {
        final TestRunner runner = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        runner.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_invalid.py");

        final Collection<ValidationResult> results = ((MockProcessContext) runner.getProcessContext()).validate();
        assertEquals(1L, results.size());
        assertEquals("Never valid.", results.iterator().next().getExplanation());

        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_update_attribute.py");
        runner.setProperty("for-attributes", "value-1");

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("for-attributes", "value-2");

        runner.assertValid();
        runner.enqueue(new byte[0], attributes);
        runner.run();
    }

    /**
     * Test a script that has a Jython processor that reads a value from a processor property and another from a flowfile attribute then stores both in the attributes of the flowfile being routed.
     * <p>
     * This may seem contrived but it verifies that the Jython processors properties are being considered and are able to be set and validated. It verifies the processor is able to access the property
     * values and flowfile attribute values during onTrigger. Lastly, it verifies the processor is able to route the flowfile to a relationship it specified.
     *
     */
    @Test
    public void testUpdateAttributeFromProcessorPropertyAndFlowFileAttribute() {
        final TestRunner runner = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        runner.setValidateExpressionUsage(false);
        runner.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_update_attribute.py");
        runner.setProperty("for-attributes", "value-1");

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("for-attributes", "value-2");

        runner.assertValid();
        runner.enqueue(new byte[0], attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred("success", 1);
        final List<MockFlowFile> result = runner.getFlowFilesForRelationship("success");

        // verify reading a property value
        result.get(0).assertAttributeEquals("from-property", "value-1");

        // verify reading an attribute value
        result.get(0).assertAttributeEquals("from-attribute", "value-2");
    }

    /**
     * Tests a script that has a Jython Processor that that reads the first line of text from the flowfiles content and stores the value in an attribute of the outgoing flowfile.
     *
     */
    @Test
    public void testReadFlowFileContentAndStoreInFlowFileAttribute() {
        final TestRunner runner = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        runner.setValidateExpressionUsage(false);
        runner.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_reader.py");
        // Use EL to populate MODULES property
        runner.setProperty(ScriptingComponentUtils.MODULES, "target/test/resources/${literal('JYTHON'):toLower()}");

        runner.assertValid();
        runner.enqueue("test content".getBytes(StandardCharsets.UTF_8));
        runner.run();

        runner.assertAllFlowFilesTransferred("success", 1);
        final List<MockFlowFile> result = runner.getFlowFilesForRelationship("success");
        result.get(0).assertAttributeEquals("from-content", "test content");
    }

    /**
     * Tests compression and decompression using two different InvokeScriptedProcessor processor instances. A string is compressed and decompressed and compared.
     *
     */
    @Test
    public void testCompressor() {
        final TestRunner one = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        one.setValidateExpressionUsage(false);
        one.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        one.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_compress.py");
        one.setProperty(ScriptingComponentUtils.MODULES, "target/test/resources/jython");
        one.setProperty("mode", "compress");

        one.assertValid();
        one.enqueue("test content".getBytes(StandardCharsets.UTF_8));
        one.run();

        one.assertAllFlowFilesTransferred("success", 1);
        final List<MockFlowFile> oneResult = one.getFlowFilesForRelationship("success");

        final TestRunner two = TestRunners.newTestRunner(new InvokeScriptedProcessor());
        two.setValidateExpressionUsage(false);
        two.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");

        two.setProperty(ScriptingComponentUtils.MODULES, "target/test/resources/jython");
        two.setProperty(ScriptingComponentUtils.SCRIPT_FILE, "target/test/resources/jython/test_compress.py");
        two.setProperty("mode", "decompress");

        two.assertValid();
        two.enqueue(oneResult.get(0));
        two.run();

        two.assertAllFlowFilesTransferred("success", 1);
        final List<MockFlowFile> twoResult = two.getFlowFilesForRelationship("success");
        assertEquals("test content", new String(twoResult.get(0).toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Tests a script file that creates and transfers a new flow file.
     *
     */
    @Test
    public void testInvalidConfiguration() {
        runner.setValidateExpressionUsage(false);
        runner.setProperty(scriptingComponent.getScriptingComponentHelper().SCRIPT_ENGINE, "python");
        runner.setProperty(ScriptingComponentUtils.SCRIPT_FILE, TEST_RESOURCE_LOCATION);
        runner.setProperty(ScriptingComponentUtils.SCRIPT_BODY, "body");

        runner.assertNotValid();
    }
}
