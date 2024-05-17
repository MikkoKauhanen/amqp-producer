package com.miggo;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.MutinyEmitter;
import org.eclipse.microprofile.reactive.messaging.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@ApplicationScoped
public class MyMessagingApplication {

    @Inject
    @Channel("words-out")
    @OnOverflow(OnOverflow.Strategy.UNBOUNDED_BUFFER)
    Emitter<String> emitter;

    public void send(String word) {
        emitter.send(addAmqCallbacks(Message.of(word)));
    }

    public boolean hasRequests(){
        return this.emitter.hasRequests();
    }

    private <T> Message<T> addAmqCallbacks(final Message<T> message) {
        return message.withAck(() -> CompletableFuture.runAsync(() -> {}).thenRun(() -> System.out.println(("ACK from AMQ send message: " + message.getPayload()))))
                      .withNack(throwable -> CompletableFuture.runAsync(() -> {}).thenRun(() -> System.err.println("NACK from AMQ send message: " + message.getPayload())));
    }
}
