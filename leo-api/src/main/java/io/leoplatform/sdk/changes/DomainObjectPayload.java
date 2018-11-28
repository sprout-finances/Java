package io.leoplatform.sdk.changes;

import io.leoplatform.schema.ChangeEvent;
import io.leoplatform.schema.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

@Singleton
public class DomainObjectPayload implements ChangeReactor {
    private static final Logger log = LoggerFactory.getLogger(DomainObjectPayload.class);

    private final DomainResolver domainResolver;
    private final PayloadWriter payloadWriter;

    @Inject
    public DomainObjectPayload(DomainResolver domainResolver, PayloadWriter payloadWriter) {
        this.domainResolver = domainResolver;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public void loadChanges(BlockingQueue<ChangeEvent> changeEvent) {
        List<JsonObject> domainObjects = distinctChanges(changeEvent).entrySet().stream()
            .map(this::toJson)
            .collect(toList());
        try {
            payloadWriter.write(domainObjects);
        } catch (Exception e) {
            log.error("Error occurred while writing domain objects", e);
        }
    }

    @Override
    public void end() {
        payloadWriter.end();
    }

    private JsonObject toJson(Entry<String, BlockingQueue<Field>> changes) {
        JsonArray results = domainResolver.toResultJson(changes.getKey(), changes.getValue());
        log.info("Loaded {} domain objects from {}", results.size(), changes.getKey());
        return Json.createObjectBuilder()
            .add(changes.getKey(), results)
            .build();
    }

    private Map<String, BlockingQueue<Field>> distinctChanges(Queue<ChangeEvent> changeEvent) {

        return changeEvent.parallelStream()
            .filter(c -> Optional.of(c).map(ChangeEvent::getName).isPresent())
            .collect(toConcurrentMap(
                ChangeEvent::getName,
                c -> c.getFields().parallelStream()
                    .filter(f -> Optional.of(f).map(Field::getValue).isPresent())
                    .collect(toCollection(LinkedBlockingQueue::new)),
                (f1, f2) -> Stream.of(f1, f2)
                    .flatMap(Collection::stream)
                    .collect(toCollection(LinkedBlockingQueue::new)))
            );
    }
}
