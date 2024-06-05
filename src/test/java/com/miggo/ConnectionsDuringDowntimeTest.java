package com.miggo;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.time.Duration;

@QuarkusTest
@QuarkusTestResource(value = ActiveMQClassicResource.class, restrictToAnnotatedClass = true)
@Slf4j
class ConnectionsDuringDowntimeTest {

    @Inject
    MyMessagingApplication application;

    @InjectAMQ
    GenericContainer amq;

    private MBeanServerConnection getConnection() throws IOException {
        String brokerURL = "service:jmx:rmi://" + amq.getHost() + ":1098/jndi/rmi://" + amq.getHost() + ":1099/jmxrmi";
        JMXServiceURL url = new JMXServiceURL(brokerURL);
        JMXConnector connector = JMXConnectorFactory.connect(url);
        return connector.getMBeanServerConnection();
    }


    /**
     * This test tries to reproduce situation where the connection amount of grows due to messages emitted while broker is not available
     */
    @Test
    @Order(2)
    void should_not_cause_extra_of_AMQP_connections() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> application.hasRequests());
        Integer originalAmountOfConnections = getConnectionCount();
        final var amountOfMessages = 100;

        for (int i = 0; i < amountOfMessages; i++) {

            if (i == 10) {
                stopAMQ();
            }

            application.send(Message.of(i));
        }

        startAMQ();
        // just to stall the test until we have some of the msgs produced to broker
        Awaitility.await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            try {
                Long count = this.getAmountOfMessagesInQueue();
                return count >= 50;
            } catch (Exception ex) {
                return false;
            }
        });
        Integer connectionCount = getConnectionCount();
        Assertions.assertEquals(originalAmountOfConnections, connectionCount);
    }

    private Integer getConnectionCount() throws IOException, ReflectionException, AttributeNotFoundException, InstanceNotFoundException, MBeanException, MalformedObjectNameException {
        var brokerObjectName = new ObjectName("org.apache.activemq:type=Broker,brokerName=localhost");
        MBeanServerConnection connection = getConnection();
        return (Integer) connection.getAttribute(brokerObjectName, "CurrentConnectionsCount");
    }


    private Long getAmountOfMessagesInQueue() throws MalformedObjectNameException, IOException, ReflectionException, AttributeNotFoundException, InstanceNotFoundException, MBeanException {
        ObjectName queueObjectName = new ObjectName("org.apache.activemq:type=Broker,brokerName=localhost,destinationType=Queue,destinationName=words-out");
        MBeanServerConnection connection = getConnection();
        return (Long) connection.getAttribute(queueObjectName, "QueueSize");
    }

    private void stopAMQ() {
        String containerId = amq.getContainerId();
        DockerClient client = DockerClientFactory.lazyClient();
        client.stopContainerCmd(containerId).exec();
    }

    private void startAMQ() {
        if (!amq.isRunning()) {
            String containerId = amq.getContainerId();
            DockerClient client = DockerClientFactory.lazyClient();
            client.startContainerCmd(containerId).exec();
        }
    }

}
