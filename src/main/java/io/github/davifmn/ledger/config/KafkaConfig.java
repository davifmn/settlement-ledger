package io.github.davifmn.ledger.config;

import io.github.davifmn.ledger.settlement.InvalidTradeException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    NewTopic tradesTopic(LedgerProperties props) {
        return TopicBuilder.name(props.topics().trades())
                .partitions(props.topics().partitions())
                .replicas(props.topics().replicas())
                .build();
    }

    @Bean
    NewTopic tradesDeadLetterTopic(LedgerProperties props) {
        return TopicBuilder.name(props.topics().tradesDeadLetter())
                .partitions(1)
                .replicas(props.topics().replicas())
                .build();
    }

    @Bean
    NewTopic ledgerEventsTopic(LedgerProperties props) {
        return TopicBuilder.name(props.topics().events())
                .partitions(props.topics().partitions())
                .replicas(props.topics().replicas())
                .build();
    }

    /**
     * A message that can never be settled (unreadable or invalid) goes straight to the
     * dead-letter topic. Anything else (database down, deadlock, timeout) is retried with
     * backoff for as long as it takes: skipping a valid trade would leave the books wrong,
     * so the partition waits and consumer lag raises the alarm instead (docs/runbook.md).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template, LedgerProperties props) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(props.topics().tradesDeadLetter(), -1));
        var backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(30_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidTradeException.class);
        return handler;
    }
}
