package com.miggo;


import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;

import java.util.HashMap;
import java.util.Map;

public class ActiveMQClassicResource implements QuarkusTestResourceLifecycleManager {

    GenericContainer amq = new FixedHostPortGenericContainer("apache/activemq-classic:5.18.3")
            .withFixedExposedPort(5672,5672)
            .withFixedExposedPort(8161,8161)
            .withFileSystemBind("src/test/resources/activemq.xml", "/opt/apache-activemq/conf/activemq.xml", BindMode.READ_ONLY);

    @Override
    public Map<String, String> start() {
        amq.withStartupAttempts(5).start();
        final String host = "localhost";
        final String port = "5672";
        final String adminPort = "8161";

        final Map<String, String> config = Map.of(
                "mp.messaging.outgoing.words-out.host", host,
                "mp.messaging.outgoing.words-out.port", port,
                "mp.messaging.outgoing.words-out.username", "admin",
                "mp.messaging.outgoing.words-out.password", "admin",
                "amqp-port", port,
                "activemq.admin.host", host,
                "activemq.admin.port", adminPort);


        return config;
    }

    @Override
    public void stop() {
        amq.stop();
    }

    @Override
    public void inject(final TestInjector testInjector) {
        testInjector.injectIntoFields(
                this.amq, new TestInjector.AnnotatedAndMatchesType(InjectAMQ.class, GenericContainer.class));
    }


}