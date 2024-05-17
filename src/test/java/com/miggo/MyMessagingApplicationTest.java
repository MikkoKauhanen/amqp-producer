package com.miggo;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@QuarkusTest
@QuarkusTestResource(ActiveMQClassicResource.class)
@Slf4j
class MyMessagingApplicationTest {

    @Inject
    MyMessagingApplication application;

    @InjectAMQ
    GenericContainer amq;

    /**
     * This test produces message every 500ms and every 20 second restarts active mq to produce issue where the
     * messages are not anymore produced to broker.
     * For every message {@link Emitter#hasRequests()} is checked if returns true.
     */
    @Test
    void should_run_out_of_requests() throws InterruptedException {
        // wait until the amq connection has been made and credits has been retrieved for the emitter
        Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> application.hasRequests());

        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger amountOfRequests = new AtomicInteger(0);

        AtomicInteger amountOfNoRequests = new AtomicInteger(0);


        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        final Runnable amqRestarter = () -> {

            try {
                String containerId = amq.getContainerId();
                DockerClient client = DockerClientFactory.lazyClient();
                client.stopContainerCmd(containerId).exec();
                Thread.sleep(1000);
                client.startContainerCmd(containerId).exec();
            } catch (Exception e) {
                log.error("EXCEPTION WHILE RESTART OF CONTAINER: {}", e.getMessage());
            }

        };

        final Runnable messageProducer = () -> {

            if (!application.hasRequests()) {
                log.error("DETECTED EMITTER WITHOUT REQUESTS");
                if (amountOfNoRequests.get() > 10) {
                    latch.countDown();
                    amountOfNoRequests.set(0);
                } else {
                    amountOfNoRequests.incrementAndGet();
                }

            }

            int amount = amountOfRequests.incrementAndGet();
            this.application.send(String.valueOf(amount));

        };

        final ScheduledFuture<?> messageCreator = scheduler.scheduleAtFixedRate(messageProducer, 1000, 500, TimeUnit.MILLISECONDS);
        final ScheduledFuture<?> restarter = scheduler.scheduleAtFixedRate(amqRestarter, 2, 20, TimeUnit.SECONDS);

        boolean wasDecrementedToZero = latch.await(30, TimeUnit.MINUTES);
        messageCreator.cancel(true);
        restarter.cancel(true);
        scheduler.shutdown();

        if (wasDecrementedToZero) {
            Assertions.fail("NO MORE REQUESTS FOR THE QUEUE");
        }

    }
}
