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

package org.apache.nifi.processors.standard;

import org.apache.nifi.event.transport.EventServer;
import org.apache.nifi.event.transport.configuration.ShutdownQuietPeriod;
import org.apache.nifi.event.transport.configuration.ShutdownTimeout;
import org.apache.nifi.event.transport.configuration.TransportProtocol;
import org.apache.nifi.event.transport.message.ByteArrayMessage;
import org.apache.nifi.event.transport.netty.ByteArrayMessageNettyEventServerFactory;
import org.apache.nifi.event.transport.netty.NettyEventServerFactory;
import org.apache.nifi.remote.io.socket.NetworkUtils;
import org.apache.nifi.security.util.TemporaryKeyStoreBuilder;
import org.apache.nifi.security.util.TlsConfiguration;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.web.util.ssl.SslContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class TestPutTCP {
    private final static String TCP_SERVER_ADDRESS = "127.0.0.1";
    private final static String SERVER_VARIABLE = "server.address";
    private final static String TCP_SERVER_ADDRESS_EL = "${" + SERVER_VARIABLE + "}";
    private final static int MIN_INVALID_PORT = 0;
    private final static int MIN_VALID_PORT = 1;
    private final static int MAX_VALID_PORT = 65535;
    private final static int MAX_INVALID_PORT = 65536;
    private final static int VALID_LARGE_FILE_SIZE = 32768;
    private final static int VALID_SMALL_FILE_SIZE = 64;
    private final static int LOAD_TEST_ITERATIONS = 500;
    private final static int LOAD_TEST_THREAD_COUNT = 1;
    private final static int DEFAULT_ITERATIONS = 1;
    private final static int DEFAULT_THREAD_COUNT = 1;
    private final static char CONTENT_CHAR = 'x';
    private final static String OUTGOING_MESSAGE_DELIMITER = "\n";
    private final static String OUTGOING_MESSAGE_DELIMITER_MULTI_CHAR = "{delimiter}\r\n";
    private final static String[] EMPTY_FILE = { "" };
    private final static String[] VALID_FILES = { "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba", "12345678", "343424222", "!@£$%^&*()_+:|{}[];\\" };

    private EventServer eventServer;
    private int port;
    private TestRunner runner;
    private BlockingQueue<ByteArrayMessage> messages;

    @BeforeEach
    public void setup() throws Exception {
        runner = TestRunners.newTestRunner(PutTCP.class);
        runner.setVariable(SERVER_VARIABLE, TCP_SERVER_ADDRESS);
        port = NetworkUtils.getAvailableTcpPort();
    }

    @AfterEach
    public void cleanup() {
        runner.shutdown();
        shutdownServer();
    }

    @Test
    public void testPortProperty() {
        runner.setProperty(PutTCP.PORT, Integer.toString(MIN_INVALID_PORT));
        runner.assertNotValid();

        runner.setProperty(PutTCP.PORT, Integer.toString(MIN_VALID_PORT));
        runner.assertValid();

        runner.setProperty(PutTCP.PORT, Integer.toString(MAX_VALID_PORT));
        runner.assertValid();

        runner.setProperty(PutTCP.PORT, Integer.toString(MAX_INVALID_PORT));
        runner.assertNotValid();
    }

    @Test
    public void testRunSuccess() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessSslContextService() throws Exception {
        final TlsConfiguration tlsConfiguration = new TemporaryKeyStoreBuilder().build();

        final SSLContext sslContext = SslContextUtils.createSslContext(tlsConfiguration);
        assertNotNull(sslContext, "SSLContext not found");
        final String identifier = SSLContextService.class.getName();
        final SSLContextService sslContextService = Mockito.mock(SSLContextService.class);
        Mockito.when(sslContextService.getIdentifier()).thenReturn(identifier);
        Mockito.when(sslContextService.createContext()).thenReturn(sslContext);
        runner.addControllerService(identifier, sslContextService);
        runner.enableControllerService(sslContextService);
        runner.setProperty(PutTCP.SSL_CONTEXT_SERVICE, identifier);
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port, sslContext);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessServerVariableExpression() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS_EL, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessPruneSenders() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertTransfers(VALID_FILES.length);
        assertMessagesReceived(VALID_FILES);

        runner.setProperty(PutTCP.IDLE_EXPIRATION, "500 ms");
        runner.run(1, false, false);
        runner.clearTransferState();
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessMultiCharDelimiter() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER_MULTI_CHAR, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessConnectionPerFlowFile() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, true);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessConnectionFailure() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);

        shutdownServer();
        sendTestData(VALID_FILES);
        runner.assertQueueEmpty();

        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(VALID_FILES);
        assertMessagesReceived(VALID_FILES);
    }

    @Test
    public void testRunSuccessEmptyFile() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        sendTestData(EMPTY_FILE);
        assertTransfers(1);
        runner.assertQueueEmpty();
    }

    @Test
    public void testRunSuccessLargeValidFile() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, true);
        createTestServer(port);
        final String[] testData = createContent(VALID_LARGE_FILE_SIZE);
        sendTestData(testData);
        assertMessagesReceived(testData);
    }

    @Test
    public void testRunSuccessFiveHundredMessages() throws Exception {
        configureProperties(TCP_SERVER_ADDRESS, OUTGOING_MESSAGE_DELIMITER, false);
        createTestServer(port);
        final String[] testData = createContent(VALID_SMALL_FILE_SIZE);
        sendTestData(testData, LOAD_TEST_ITERATIONS, LOAD_TEST_THREAD_COUNT);
        assertMessagesReceived(testData, LOAD_TEST_ITERATIONS);
    }

    private void createTestServer(final int port) throws Exception {
        createTestServer(port, null);
    }

    private void createTestServer(final int port, final SSLContext sslContext) throws Exception {
        messages = new LinkedBlockingQueue<>();
        final byte[] delimiter = getDelimiter();
        final InetAddress listenAddress = InetAddress.getByName(TCP_SERVER_ADDRESS);
        NettyEventServerFactory serverFactory = new ByteArrayMessageNettyEventServerFactory(runner.getLogger(),
                listenAddress, port, TransportProtocol.TCP, delimiter, VALID_LARGE_FILE_SIZE, messages);
        if (sslContext != null) {
            serverFactory.setSslContext(sslContext);
        }
        serverFactory.setShutdownQuietPeriod(ShutdownQuietPeriod.QUICK.getDuration());
        serverFactory.setShutdownTimeout(ShutdownTimeout.QUICK.getDuration());
        eventServer = serverFactory.getEventServer();
    }

    private void shutdownServer() {
        if (eventServer != null) {
            eventServer.shutdown();
        }
    }

    private void configureProperties(String host, String outgoingMessageDelimiter, boolean connectionPerFlowFile) {
        runner.setProperty(PutTCP.HOSTNAME, host);
        runner.setProperty(PutTCP.PORT, Integer.toString(port));

        if (outgoingMessageDelimiter != null) {
            runner.setProperty(PutTCP.OUTGOING_MESSAGE_DELIMITER, outgoingMessageDelimiter);
        }

        runner.setProperty(PutTCP.CONNECTION_PER_FLOWFILE, String.valueOf(connectionPerFlowFile));
        runner.assertValid();
    }

    private void sendTestData(final String[] testData) {
        sendTestData(testData, DEFAULT_ITERATIONS, DEFAULT_THREAD_COUNT);
    }

    private void sendTestData(final String[] testData, final int iterations, final int threadCount) {
        runner.setThreadCount(threadCount);
        for (int i = 0; i < iterations; i++) {
            for (String item : testData) {
                runner.enqueue(item.getBytes());
            }
            runner.run(testData.length, false, i == 0);
        }
    }

    private void assertTransfers(final int successCount) {
        runner.assertTransferCount(PutTCP.REL_SUCCESS, successCount);
        runner.assertTransferCount(PutTCP.REL_FAILURE, 0);
    }

    private void assertMessagesReceived(final String[] sentData) throws Exception {
        assertMessagesReceived(sentData, DEFAULT_ITERATIONS);
        runner.assertQueueEmpty();
    }

    private void assertMessagesReceived(final String[] sentData, final int iterations) throws Exception {
        for (int i = 0; i < iterations; i++) {
            for (String item : sentData) {
                final ByteArrayMessage message = messages.take();
                assertNotNull(message, String.format("Message [%d] not found", i));
                assertTrue(Arrays.asList(sentData).contains(new String(message.getMessage())));
            }
        }

        runner.assertTransferCount(PutTCP.REL_SUCCESS, sentData.length * iterations);
        runner.clearTransferState();

        assertNull(messages.poll(), "Unexpected extra messages found");
    }

    private String[] createContent(final int size) {
        final char[] content = new char[size];

        for (int i = 0; i < size; i++) {
            content[i] = CONTENT_CHAR;
        }

        return new String[] { new String(content) };
    }

    private byte[] getDelimiter() {
        String delimiter = runner.getProcessContext().getProperty(PutTCP.OUTGOING_MESSAGE_DELIMITER).getValue();
        if (delimiter != null) {
            return delimiter.getBytes();
        } else {
            return null;
        }
    }
}
