package io.rwa.flink.process;

import io.rwa.flink.model.DecodedChainEvent;
import io.rwa.flink.model.EventType;
import java.math.BigInteger;
import java.time.Duration;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public class ListingFillHintProcessFunction extends KeyedProcessFunction<String, DecodedChainEvent, DecodedChainEvent> {

    private static final Duration STATE_TTL = Duration.ofDays(30);

    private transient ValueState<BigInteger> listedTotalState;
    private transient ValueState<BigInteger> purchasedState;

    @Override
    public void open(Configuration parameters) {
        StateTtlConfig ttlConfig = StateTtlConfig
            .newBuilder(Time.milliseconds(STATE_TTL.toMillis()))
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
            .build();

        ValueStateDescriptor<BigInteger> listedDescriptor = new ValueStateDescriptor<>("listed-total", BigInteger.class);
        listedDescriptor.enableTimeToLive(ttlConfig);
        listedTotalState = getRuntimeContext().getState(listedDescriptor);

        ValueStateDescriptor<BigInteger> purchasedDescriptor = new ValueStateDescriptor<>("purchased-total", BigInteger.class);
        purchasedDescriptor.enableTimeToLive(ttlConfig);
        purchasedState = getRuntimeContext().getState(purchasedDescriptor);
    }

    @Override
    public void processElement(
        DecodedChainEvent event,
        KeyedProcessFunction<String, DecodedChainEvent, DecodedChainEvent>.Context context,
        Collector<DecodedChainEvent> out
    ) throws Exception {
        if (event.eventType() == EventType.MARKET_LISTED) {
            if (event.amount() != null) {
                listedTotalState.update(event.amount());
            }
            BigInteger purchased = purchasedState.value();
            boolean filled = isFilled(listedTotalState.value(), purchased);
            out.collect(event.withMarkFilled(filled));
            if (filled) {
                clearListingState();
            }
            return;
        }

        if (event.eventType() == EventType.MARKET_BOUGHT) {
            BigInteger currentPurchased = purchasedState.value();
            if (currentPurchased == null) {
                currentPurchased = BigInteger.ZERO;
            }
            if (event.amount() != null) {
                currentPurchased = currentPurchased.add(event.amount());
                purchasedState.update(currentPurchased);
            }
            boolean filled = isFilled(listedTotalState.value(), currentPurchased);
            out.collect(event.withMarkFilled(filled));
            if (filled) {
                clearListingState();
            }
            return;
        }

        if (event.eventType() == EventType.MARKET_CANCELLED) {
            clearListingState();
            out.collect(event);
            return;
        }

        out.collect(event);
    }

    private boolean isFilled(BigInteger listedTotal, BigInteger purchasedTotal) {
        if (listedTotal == null || purchasedTotal == null) {
            return false;
        }
        return purchasedTotal.compareTo(listedTotal) >= 0;
    }

    private void clearListingState() throws Exception {
        listedTotalState.clear();
        purchasedState.clear();
    }
}
