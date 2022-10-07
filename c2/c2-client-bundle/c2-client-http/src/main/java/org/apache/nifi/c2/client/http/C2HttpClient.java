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

package org.apache.nifi.c2.client.http;

import static okhttp3.MultipartBody.FORM;
import static okhttp3.RequestBody.create;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.c2.client.C2ClientConfig;
import org.apache.nifi.c2.client.api.C2Client;
import org.apache.nifi.c2.protocol.api.C2Heartbeat;
import org.apache.nifi.c2.protocol.api.C2HeartbeatResponse;
import org.apache.nifi.c2.protocol.api.C2OperationAck;
import org.apache.nifi.c2.serializer.C2Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class C2HttpClient implements C2Client {

    static final MediaType MEDIA_TYPE_APPLICATION_JSON = MediaType.parse("application/json");
    private static final Logger logger = LoggerFactory.getLogger(C2HttpClient.class);
    private static final String MULTIPART_FORM_FILE_FIELD_NAME = "file";
    private static final String BUNDLE_FILE_NAME = "debug.tar.gz";
    private static final MediaType BUNDLE_MIME_TYPE = MediaType.parse("application/gzip");

    private final AtomicReference<OkHttpClient> httpClientReference = new AtomicReference<>();
    private final C2ClientConfig clientConfig;
    private final C2Serializer serializer;

    public C2HttpClient(C2ClientConfig clientConfig, C2Serializer serializer) {
        super();
        this.clientConfig = clientConfig;
        this.serializer = serializer;
        final OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder();

        // Configure request and response logging
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(logger::debug);
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        okHttpClientBuilder.addInterceptor(logging);

        // Set whether to follow redirects
        okHttpClientBuilder.followRedirects(true);

        // Timeouts
        okHttpClientBuilder.connectTimeout(clientConfig.getConnectTimeout(), TimeUnit.MILLISECONDS);
        okHttpClientBuilder.readTimeout(clientConfig.getReadTimeout(), TimeUnit.MILLISECONDS);
        okHttpClientBuilder.callTimeout(clientConfig.getCallTimeout(), TimeUnit.MILLISECONDS);

        // check if the ssl path is set and add the factory if so
        if (StringUtils.isNotBlank(clientConfig.getKeystoreFilename())) {
            try {
                setSslSocketFactory(okHttpClientBuilder);
            } catch (Exception e) {
                throw new IllegalStateException("OkHttp TLS configuration failed", e);
            }
        }

        httpClientReference.set(okHttpClientBuilder.build());
    }

    @Override
    public Optional<C2HeartbeatResponse> publishHeartbeat(C2Heartbeat heartbeat) {
        return serializer.serialize(heartbeat).flatMap(this::sendHeartbeat);
    }

    @Override
    public Optional<byte[]> retrieveUpdateContent(String flowUpdateUrl) {
        Optional<byte[]> updateContent = Optional.empty();
        final Request.Builder requestBuilder = new Request.Builder()
            .get()
            .url(flowUpdateUrl);
        final Request request = requestBuilder.build();

        try (Response response = httpClientReference.get().newCall(request).execute()) {
            Optional<ResponseBody> body = Optional.ofNullable(response.body());

            if (!response.isSuccessful()) {
                StringBuilder messageBuilder = new StringBuilder(String.format("Configuration retrieval failed: HTTP %d", response.code()));
                body.map(Object::toString).ifPresent(messageBuilder::append);
                throw new C2ServerException(messageBuilder.toString());
            }

            if (body.isPresent()) {
                updateContent = Optional.of(body.get().bytes());
            } else {
                logger.warn("No body returned when pulling a new configuration");
            }
        } catch (Exception e) {
            logger.warn("Configuration retrieval failed", e);
        }

        return updateContent;
    }

    @Override
    public void acknowledgeOperation(C2OperationAck operationAck) {
        logger.info("Acknowledging Operation {} to C2 server {}", operationAck.getOperationId(), clientConfig.getC2AckUrl());
        serializer.serialize(operationAck)
            .map(operationAckBody -> create(operationAckBody, MEDIA_TYPE_APPLICATION_JSON))
            .map(requestBody -> new Request.Builder().post(requestBody).url(clientConfig.getC2AckUrl()).build())
            .map(C2RequestCompression.forType(clientConfig.getC2RequestCompression())::compress)
            .ifPresent(this::sendAck);
    }

    @Override
    public Optional<String> uploadBundle(String callbackUrl, byte[] bundle) {
        Request request = new Request.Builder()
            .url(callbackUrl)
            .post(new MultipartBody.Builder()
                .setType(FORM)
                .addFormDataPart(MULTIPART_FORM_FILE_FIELD_NAME, BUNDLE_FILE_NAME, create(bundle, BUNDLE_MIME_TYPE))
                .build())
            .build();

        logger.info("Uploading bundle to C2 server {} with size {}", callbackUrl, bundle.length);
        try (Response response = httpClientReference.get().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Upload bundle failed to C2 server {} with status code {}", callbackUrl, response.code());
                return Optional.of("Upload bundle failed to C2 server");
            }
        } catch (IOException e) {
            logger.error("Could not upload bundle to C2 server {}", callbackUrl, e);
            return Optional.of("Could not upload bundle to C2 server");
        }
        return Optional.empty();
    }

    private Optional<C2HeartbeatResponse> sendHeartbeat(String heartbeat) {
        Optional<C2HeartbeatResponse> c2HeartbeatResponse = Optional.empty();
        Request request = new Request.Builder()
            .post(create(heartbeat, MEDIA_TYPE_APPLICATION_JSON))
            .url(clientConfig.getC2Url())
            .build();

        Request decoratedRequest = C2RequestCompression.forType(clientConfig.getC2RequestCompression()).compress(request);

        try (Response heartbeatResponse = httpClientReference.get().newCall(decoratedRequest).execute()) {
            c2HeartbeatResponse = getResponseBody(heartbeatResponse).flatMap(response -> serializer.deserialize(response, C2HeartbeatResponse.class));
        } catch (IOException ce) {
            logger.error("Send Heartbeat failed to C2 server {}", clientConfig.getC2Url(), ce);
        }

        return c2HeartbeatResponse;
    }

    private Optional<String> getResponseBody(Response response) {
        String responseBody = null;

        try {
            responseBody = response.body().string();
            logger.debug("Received response body {}", responseBody);
        } catch (IOException e) {
            logger.error("HTTP Request failed", e);
        }

        return Optional.ofNullable(responseBody);
    }

    private void setSslSocketFactory(OkHttpClient.Builder okHttpClientBuilder) throws Exception {
        final String keystoreLocation = clientConfig.getKeystoreFilename();
        final String keystoreType = clientConfig.getKeystoreType();
        final String keystorePass = clientConfig.getKeystorePass();

        assertKeystorePropertiesSet(keystoreLocation, keystorePass, keystoreType);

        // prepare the keystore
        final KeyStore keyStore = KeyStore.getInstance(keystoreType);

        try (FileInputStream keyStoreStream = new FileInputStream(keystoreLocation)) {
            keyStore.load(keyStoreStream, keystorePass.toCharArray());
        }

        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keystorePass.toCharArray());

        // load truststore
        final String truststoreLocation = clientConfig.getTruststoreFilename();
        final String truststorePass = clientConfig.getTruststorePass();
        final String truststoreType = clientConfig.getTruststoreType();
        assertTruststorePropertiesSet(truststoreLocation, truststorePass, truststoreType);

        KeyStore truststore = KeyStore.getInstance(truststoreType);
        final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
        truststore.load(new FileInputStream(truststoreLocation), truststorePass.toCharArray());
        trustManagerFactory.init(truststore);

        final X509TrustManager x509TrustManager;
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (trustManagers[0] != null) {
            x509TrustManager = (X509TrustManager) trustManagers[0];
        } else {
            throw new IllegalStateException("List of trust managers is null");
        }

        SSLContext tempSslContext;
        try {
            tempSslContext = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SSLContext creation failed", e);
        }

        final SSLContext sslContext = tempSslContext;
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
        okHttpClientBuilder.sslSocketFactory(sslSocketFactory, x509TrustManager);
    }

    private void assertKeystorePropertiesSet(String location, String password, String type) {
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException(clientConfig.getKeystoreFilename() + " is null or is empty");
        }

        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("The client's keystore filename is set but its password is not (or is empty). If the location is set, the password must also be.");
        }

        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("The client's keystore filename is set but its type is not (or is empty). If the location is set, the type must also be.");
        }
    }

    private void assertTruststorePropertiesSet(String location, String password, String type) {
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException("The client's truststore filename is not set or is empty");
        }

        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("The client's truststore filename is set but its password is not (or is empty). If the location is set, the password must also be.");
        }

        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("The client's truststore filename is set but its type is not (or is empty). If the location is set, the type must also be.");
        }
    }

    private void sendAck(Request request) {
        try (Response heartbeatResponse = httpClientReference.get().newCall(request).execute()) {
            if (!heartbeatResponse.isSuccessful()) {
                logger.warn("Acknowledgement was not successful with C2 server {} with status code {}", clientConfig.getC2AckUrl(), heartbeatResponse.code());
            }
        } catch (IOException e) {
            logger.error("Could not transmit ack to C2 server {}", clientConfig.getC2AckUrl(), e);
        }
    }
}
