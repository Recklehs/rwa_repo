package io.rwa.ingester.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.ingester.config.IngesterConfig;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaRawLogPublisher implements AutoCloseable {

    private final String topic;
    private final ObjectMapper objectMapper;
    private final KafkaProducer<String, String> producer;

    public KafkaRawLogPublisher(IngesterConfig config, ObjectMapper objectMapper) {
        this.topic = config.kafkaTopic();
        this.objectMapper = objectMapper;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE));
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");

        this.producer = new KafkaProducer<>(props);
    }

    public void publish(String key, ObjectNode payload) throws Exception {
        String value = objectMapper.writeValueAsString(payload);
        producer.send(new ProducerRecord<>(topic, key, value)).get();
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }
}
