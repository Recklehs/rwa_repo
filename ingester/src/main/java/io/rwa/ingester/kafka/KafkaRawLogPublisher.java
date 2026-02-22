package io.rwa.ingester.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.ingester.config.IngesterConfig;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaRawLogPublisher implements AutoCloseable {

    private final String defaultTopic;
    private final ObjectMapper objectMapper;
    private final KafkaProducer<String, String> producer;

    public KafkaRawLogPublisher(IngesterConfig config, ObjectMapper objectMapper) {
        this.defaultTopic = config.kafkaTopic();
        this.objectMapper = objectMapper;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE));
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(config.kafkaRequestTimeoutMs()));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Integer.toString(config.kafkaDeliveryTimeoutMs()));
        props.put(ProducerConfig.LINGER_MS_CONFIG, Integer.toString(config.kafkaLingerMs()));

        putIfNotBlank(props, ProducerConfig.COMPRESSION_TYPE_CONFIG, config.kafkaCompressionType());
        putIfNotBlank(props, CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, config.kafkaSecurityProtocol());
        putIfNotBlank(props, SaslConfigs.SASL_MECHANISM, config.kafkaSaslMechanism());
        putIfNotBlank(props, SaslConfigs.SASL_JAAS_CONFIG, config.kafkaSaslJaasConfig());
        putIfNotBlank(props, CommonClientConfigs.CLIENT_DNS_LOOKUP_CONFIG, config.kafkaClientDnsLookup());

        this.producer = new KafkaProducer<>(props);
    }

    private void putIfNotBlank(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.put(key, value);
        }
    }

    public void publish(String key, ObjectNode payload) throws Exception {
        publish(defaultTopic, key, payload);
    }

    public void publish(String topic, String key, ObjectNode payload) throws Exception {
        String value = objectMapper.writeValueAsString(payload);
        producer.send(new ProducerRecord<>(topic, key, value)).get();
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }
}
