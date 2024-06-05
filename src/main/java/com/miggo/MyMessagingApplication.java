package com.miggo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;

@ApplicationScoped
public class MyMessagingApplication {

    @Inject
    @Channel("words-out")
    @OnOverflow(OnOverflow.Strategy.UNBOUNDED_BUFFER)
    Emitter<Integer> emitter;

    public void send(Message<Integer> word) {
        emitter.send(word);
    }

    public boolean hasRequests(){
        return this.emitter.hasRequests();
    }
}
