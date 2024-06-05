package com.miggo;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@QuarkusTestResource(value = ActiveMQClassicResource.class, restrictToAnnotatedClass = true)
@Slf4j
class NoMoreRequestsTest {

    @Inject
    MyMessagingApplication application;

    @InjectAMQ
    GenericContainer amq;
    private final List<Integer> ackedMessages = new ArrayList<>();
    private final List<Integer> nackedMessages = new ArrayList<>();


    private MBeanServerConnection getConnection() throws IOException {
        String brokerURL = "service:jmx:rmi://" + amq.getHost() + ":1098/jndi/rmi://" + amq.getHost() + ":1099/jmxrmi";
        JMXServiceURL url = new JMXServiceURL(brokerURL);
        JMXConnector connector = JMXConnectorFactory.connect(url);
        return connector.getMBeanServerConnection();
    }

    /**
     * This test tries to reproduce the error where messages are not anymore produced
     */
    @Test
    void should_not_run_out_of_requests() throws InterruptedException {
        // wait until the amq connection has been made and credits has been retrieved for the emitter
        Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> application.hasRequests());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger amountOfRequests = new AtomicInteger(0);
        AtomicInteger amountOfNoRequests = new AtomicInteger(0);

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        final Runnable messageProducer = () -> {

            for (int i = 0; i < 99; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                int amount = amountOfRequests.incrementAndGet();
                this.application.send(addAmqCallbacks(Message.of(amount)));
            }

            if (!this.application.hasRequests()) {
                log.warn("DETECTED PRODUCER WITHOUT REQUESTS");
                if (amountOfNoRequests.incrementAndGet() > 10) {
                    latch.countDown();
                }
            } else {
                amountOfNoRequests.set(0);
            }
        };

        final Runnable amqRestarter = () -> {

            try {
                ScheduledFuture<?> producer = scheduler.schedule(messageProducer, 0, TimeUnit.SECONDS);
                Thread.sleep(100);
                stopAMQ();
                Thread.sleep(1000);
                startAMQ();
                Thread.sleep(1000);
                producer.cancel(true);
            } catch (Exception e) {
                log.error("EXCEPTION WHILE RESTART OF CONTAINER: {}", e.getMessage());
            }
        };

        final ScheduledFuture<?> restarter = scheduler.scheduleAtFixedRate(amqRestarter, 2, 10, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            try {

                restarter.cancel(false);

                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }, 90, TimeUnit.SECONDS);

        boolean res = latch.await(2, TimeUnit.MINUTES);

        if (!res) {
            scheduler.shutdown();
        }

        if (!amq.isRunning()) {
            fail("AMQ container is not running");
        }

        Awaitility.await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            try {
                Long count = this.getAmountOfMessagesInQueue();
                return count == ackedMessages.size();
            } catch (Exception ex) {
                return false;
            }
        });

        if (!application.hasRequests()) {
            Thread.sleep(5000);
        }

        assertTrue(this.application.hasRequests());
        assertTrue(nackedMessages.isEmpty());
        assertEquals(amountOfRequests.get(), ackedMessages.size());
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


    private Message<Integer> addAmqCallbacks(final Message<Integer> message) {
        return message.withAck(() -> CompletableFuture.runAsync(() -> {
                      }).thenRun(() -> {
                          log.info("ACK OF {}", message.getPayload());
                          ackedMessages.add(message.getPayload());
                      }))
                      .withNack(throwable -> CompletableFuture.runAsync(() -> {
                      }).thenRun(() -> {
                          log.error("NACK OF {}", message.getPayload());
                          nackedMessages.add(message.getPayload());
                      }));
    }
}
