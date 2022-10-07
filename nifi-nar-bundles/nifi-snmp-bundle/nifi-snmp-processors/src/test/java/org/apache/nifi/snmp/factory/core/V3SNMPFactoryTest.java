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
package org.apache.nifi.snmp.factory.core;

import org.apache.nifi.remote.io.socket.NetworkUtils;
import org.apache.nifi.snmp.configuration.SNMPConfiguration;
import org.junit.jupiter.api.Test;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.USM;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;

import java.util.regex.Pattern;

import static org.apache.nifi.snmp.helper.configurations.SNMPConfigurationFactory.LOCALHOST;
import static org.apache.nifi.snmp.helper.configurations.SNMPV3ConfigurationFactory.AUTH_PASSPHRASE;
import static org.apache.nifi.snmp.helper.configurations.SNMPV3ConfigurationFactory.AUTH_PROTOCOL;
import static org.apache.nifi.snmp.helper.configurations.SNMPV3ConfigurationFactory.PRIV_PASSPHRASE;
import static org.apache.nifi.snmp.helper.configurations.SNMPV3ConfigurationFactory.PRIV_PROTOCOL;
import static org.apache.nifi.snmp.helper.configurations.SNMPV3ConfigurationFactory.SECURITY_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class V3SNMPFactoryTest {

    private static final int RETRIES = 3;
    private static final int EXPECTED_SECURITY_LEVEL = 3;

    @Test
    void testFactoryCreatesTarget() {
        final V3SNMPFactory snmpFactory = new V3SNMPFactory();
        final int managerPort = NetworkUtils.getAvailableUdpPort();
        final String targetPort = String.valueOf(NetworkUtils.getAvailableUdpPort());
        final SNMPConfiguration snmpConfiguration = getSnmpConfiguration(managerPort, targetPort);

        final Target target = snmpFactory.createTargetInstance(snmpConfiguration);

        assertThat(target, instanceOf(UserTarget.class));
        assertEquals(LOCALHOST + "/" + targetPort, target.getAddress().toString());
        assertEquals(RETRIES, target.getRetries());
        assertEquals(EXPECTED_SECURITY_LEVEL, target.getSecurityLevel());
        assertEquals(SECURITY_NAME, target.getSecurityName().toString());
    }

    @Test
    void testFactoryCreatesSnmpManager() {
        final V3SNMPFactory snmpFactory = new V3SNMPFactory();
        final int managerPort = NetworkUtils.getAvailableUdpPort();
        final String targetPort = String.valueOf(NetworkUtils.getAvailableUdpPort());
        final SNMPConfiguration snmpConfiguration = getSnmpConfiguration(managerPort, targetPort);

        final Snmp snmpManager = snmpFactory.createSnmpManagerInstance(snmpConfiguration);

        final String address = snmpManager.getMessageDispatcher().getTransportMappings().iterator().next().getListenAddress().toString();
        USM usm = (USM) SecurityModels.getInstance().getSecurityModel(new Integer32(3));
        assertTrue(Pattern.compile("0.+?0/" + managerPort).matcher(address).matches());
        assertTrue(usm.hasUser(null, new OctetString("SHAAES128")));
    }

    @Test
    void testFactoryCreatesResourceHandler() {
        final V3SNMPFactory snmpFactory = spy(V3SNMPFactory.class);
        final int managerPort = NetworkUtils.getAvailableUdpPort();
        final String targetPort = String.valueOf(NetworkUtils.getAvailableUdpPort());
        final SNMPConfiguration snmpConfiguration = getSnmpConfiguration(managerPort, targetPort);
        snmpFactory.createSNMPResourceHandler(snmpConfiguration);

        verify(snmpFactory).createTargetInstance(snmpConfiguration);
        verify(snmpFactory).createSnmpManagerInstance(snmpConfiguration);
    }

    private SNMPConfiguration getSnmpConfiguration(int managerPort, String targetPort) {
        return new SNMPConfiguration.Builder()
                .setRetries(RETRIES)
                .setManagerPort(managerPort)
                .setTargetHost(LOCALHOST)
                .setTargetPort(targetPort)
                .setSecurityLevel(SecurityLevel.authPriv.name())
                .setSecurityName(SECURITY_NAME)
                .setAuthProtocol(AUTH_PROTOCOL)
                .setAuthPassphrase(AUTH_PASSPHRASE)
                .setPrivacyProtocol(PRIV_PROTOCOL)
                .setPrivacyPassphrase(PRIV_PASSPHRASE)
                .build();
    }

}
