package io.prometheus.metrics.core;

import io.prometheus.metrics.exemplars.CounterExemplarSampler;
import io.prometheus.metrics.exemplars.ExemplarConfig;
import io.prometheus.metrics.model.*;
import io.prometheus.metrics.observer.DiscreteEventObserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

public class Counter extends ObservingMetric<DiscreteEventObserver, Counter.CounterData> implements DiscreteEventObserver {
    /**
     * null means default from ExemplarConfig applies.
     */
    private final Boolean exemplarsEnabled;
    private final CounterExemplarSampler exemplarSampler;

    private Counter(Builder builder) {
        super(builder);
        this.exemplarsEnabled = builder.exemplarsEnabled;
        this.exemplarSampler = builder.exemplarSampler;
    }

    @Override
    public void inc(double amount) {
        getNoLabels().inc(amount);
    }

    @Override
    public void incWithExemplar(double amount, Labels labels) {
        getNoLabels().incWithExemplar(amount, labels);
    }


    @Override
    protected CounterData newMetricData() {
        return new CounterData();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected CounterSnapshot collect(List<Labels> labels, List<CounterData> metricData) {
        List<CounterSnapshot.CounterData> data = new ArrayList<>(labels.size());
        for (int i=0; i<labels.size(); i++) {
            data.add(metricData.get(i).snapshot(labels.get(i)));
        }
        return new CounterSnapshot(getMetadata(), data);
    }

    class CounterData implements DiscreteEventObserver, MetricData<DiscreteEventObserver> {

        private final DoubleAdder value = new DoubleAdder();
        private final long createdTimeMillis = System.currentTimeMillis();
        private final AtomicReference<Exemplar> exemplar = new AtomicReference<Exemplar>();

        @Override
        public void inc(double amount) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                Exemplar prev, next;
                do {
                    prev = exemplar.get();
                    next = sampleNextExemplar(amount, prev);
                } while (next != null && !exemplar.compareAndSet(prev, next));
            }
        }

        @Override
        public void incWithExemplar(double amount, Labels labels) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                exemplar.set(new Exemplar(amount, labels, System.currentTimeMillis()));
            }
        }

        private void validateAndAdd(double amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Amount " + amount + " is negative.");
            }
            value.add(amount);
        }

        private boolean isExemplarsEnabled() {
            if (exemplarsEnabled != null) {
                return exemplarsEnabled;
            } else {
                return ExemplarConfig.isExemplarsEnabled();
            }
        }

        private Exemplar sampleNextExemplar(double amt, Exemplar prev) {
            if (exemplarSampler != null) {
                return exemplarSampler.sample(amt, prev);
            } else {
                CounterExemplarSampler exemplarSampler = ExemplarConfig.getCounterExemplarSampler();
                if (exemplarSampler != null) {
                    return exemplarSampler.sample(amt, prev);
                } else {
                    return null;
                }
            }
        }

        private CounterSnapshot.CounterData snapshot(Labels labels) {
            // Read the exemplar first. Otherwise, there is a race condition where you might
            // see an Exemplar for a value that's not represented in getValue() yet.
            Exemplar ex = exemplar.get();
            return new CounterSnapshot.CounterData(value.sum(), labels, ex, createdTimeMillis);
        }

        @Override
        public DiscreteEventObserver toObserver() {
            return this;
        }
    }

    public static class Builder extends ObservingMetric.Builder<Builder, Counter> {

        private Boolean exemplarsEnabled;
        private CounterExemplarSampler exemplarSampler;

        private Builder() {
        }

        @Override
        protected MetricType getType() {
            return MetricType.COUNTER;
        }

        @Override
        public Counter build() {
            return new Counter(this);
        }

        public Builder withExemplars() {
            this.exemplarsEnabled = TRUE;
            return this;
        }

        public Builder withoutExemplars() {
            this.exemplarsEnabled = FALSE;
            return this;
        }

        /**
         * Enable exemplars and provide a custom {@link CounterExemplarSampler}.
         */
        public Builder withExemplarSampler(CounterExemplarSampler exemplarSampler) {
            this.exemplarSampler = exemplarSampler;
            return withExemplars();
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    public static class FromCallback extends Metric {

        private final DoubleSupplier callback;
        private final long createdTimeMillis = System.currentTimeMillis();

        private FromCallback(io.prometheus.metrics.core.Counter.FromCallback.Builder builder) {
            super(builder);
            this.callback = builder.callback;
        }

        @Override
        public CounterSnapshot collect() {
            return new CounterSnapshot(getMetadata(), Collections.singletonList(new CounterSnapshot.CounterData(
                    callback.getAsDouble(),
                    constLabels,
                    null,
                    createdTimeMillis
            )));
        }

        public static class Builder extends Metric.Builder<io.prometheus.metrics.core.Counter.FromCallback.Builder, io.prometheus.metrics.core.Counter.FromCallback> {

            private DoubleSupplier callback;

            private Builder() {
            }

            @Override
            protected MetricType getType() {
                return MetricType.COUNTER;
            }

            public io.prometheus.metrics.core.Counter.FromCallback.Builder withCallback(DoubleSupplier callback) {
                this.callback = callback;
                return this;
            }

            @Override
            public io.prometheus.metrics.core.Counter.FromCallback build() {
                return new io.prometheus.metrics.core.Counter.FromCallback(this);
            }

            @Override
            protected io.prometheus.metrics.core.Counter.FromCallback.Builder self() {
                return this;
            }
        }
    }
}
