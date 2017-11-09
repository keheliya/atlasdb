/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.logging;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

public class KvsProfilingLogger {

    public static final String SLOW_LOGGER_NAME = "kvs-slow-log";

    public static final Logger slowlogger = LoggerFactory.getLogger(SLOW_LOGGER_NAME);
    private static final Logger log = LoggerFactory.getLogger(KvsProfilingLogger.class);

    public static final int DEFAULT_THRESHOLD_MILLIS = 1000;
    private static volatile Predicate<Stopwatch> slowLogPredicate = createLogPredicateForThresholdMillis(
            DEFAULT_THRESHOLD_MILLIS);

    @FunctionalInterface
    public interface LoggingFunction {
        void log(String fmt, Object... args);
    }

    interface CloseableLoggingFunction extends LoggingFunction, AutoCloseable {
        @Override
        void close();
    }

    // Accumulates logs in a single string.
    // Warning to users of this class: We do not guarantee that SLF4J special characters work properly across log lines,
    // nor do we guarantee behaviour when the number of arguments does not match the number of placeholders.
    @VisibleForTesting
    static class LogAccumulator implements CloseableLoggingFunction {
        private static final String DELIMITER = "\n";

        private final List<String> formatElements = Lists.newArrayList();
        private final List<Object> argList = Lists.newArrayList();
        private final LoggingFunction sink;

        private boolean isClosed = false;

        LogAccumulator(LoggingFunction sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void log(String fmt, Object... args) {
            formatElements.add(fmt);
            Collections.addAll(argList, args);
        }

        @Override
        public synchronized void close() {
            if (!isClosed) {
                sink.log(String.join(DELIMITER, formatElements), argList.toArray(new Object[argList.size()]));
            }
            isClosed = true;
        }
    }

    /**
     * Sets the minimum duration in millis that a query must take in order to be logged. Defaults to 1000ms.
     */
    public static void setSlowLogThresholdMillis(long thresholdMillis) {
        slowLogPredicate = createLogPredicateForThresholdMillis(thresholdMillis);
    }

    public static <T> T maybeLog(Supplier<T> action, BiConsumer<LoggingFunction, Stopwatch> logger) {
        return maybeLog(action, logger, (loggingFunction, result) -> { });
    }

    public static void maybeLog(Runnable runnable, BiConsumer<LoggingFunction, Stopwatch> logger) {
        maybeLog(() -> {
            runnable.run();
            return null;
        }, logger);
    }

    public static  <T> T maybeLog(Supplier<T> action, BiConsumer<LoggingFunction, Stopwatch> primaryLogger,
            BiConsumer<LoggingFunction, T> additonalLoggerWithAccessToResult) {
        if (log.isTraceEnabled() || slowlogger.isWarnEnabled()) {
            Monitor<T> monitor = Monitor.createMonitor(
                    primaryLogger,
                    additonalLoggerWithAccessToResult,
                    slowLogPredicate);
            try {
                T res = action.get();
                monitor.registerResult(res);
                return res;
            } catch (Exception ex) {
                monitor.registerException(ex);
                throw ex;
            } finally {
                monitor.log();
            }
        } else {
            return action.get();
        }
    }

    private static class Monitor<R> {
        private final Stopwatch stopwatch;
        private final BiConsumer<LoggingFunction, Stopwatch> primaryLogger;
        private final BiConsumer<LoggingFunction, R> additionalLoggerWithAccessToResult;
        private final Predicate<Stopwatch> slowLogPredicate;

        private R result;
        private Exception exception;

        private Monitor(Stopwatch stopwatch,
                BiConsumer<LoggingFunction, Stopwatch> primaryLogger,
                BiConsumer<LoggingFunction, R> additionalLoggerWithAccessToResult,
                Predicate<Stopwatch> slowLogPredicate) {
            this.stopwatch = stopwatch;
            this.primaryLogger = primaryLogger;
            this.additionalLoggerWithAccessToResult = additionalLoggerWithAccessToResult;
            this.slowLogPredicate = slowLogPredicate;
        }

        static <V> Monitor<V> createMonitor(BiConsumer<LoggingFunction,
                Stopwatch> primaryLogger,
                BiConsumer<LoggingFunction, V> additionalLoggerWithAccessToResult,
                Predicate<Stopwatch> slowLogPredicate) {
            return new Monitor<>(Stopwatch.createStarted(),
                    primaryLogger,
                    additionalLoggerWithAccessToResult,
                    slowLogPredicate);
        }

        void registerResult(R res) {
            this.result = res;
        }

        void registerException(Exception ex) {
            this.exception = ex;
        }

        void log() {
            stopwatch.stop();
            Consumer<LoggingFunction> logger = (loggingMethod) -> {
                try (CloseableLoggingFunction wrappingLogger = new LogAccumulator(loggingMethod)) {
                    primaryLogger.accept(wrappingLogger, stopwatch);
                    if (result != null) {
                        additionalLoggerWithAccessToResult.accept(wrappingLogger, result);
                    } else if (exception != null) {
                        wrappingLogger.log("This operation has thrown an exception {}", exception);
                    }
                }
            };

            if (log.isTraceEnabled()) {
                logger.accept(log::trace);
            }
            if (slowlogger.isWarnEnabled() && slowLogPredicate.test(stopwatch)) {
                logger.accept(slowlogger::warn);
            }
        }
    }

    private static Predicate<Stopwatch> createLogPredicateForThresholdMillis(long thresholdMillis) {
        return stopwatch -> stopwatch.elapsed(TimeUnit.MILLISECONDS) > thresholdMillis;
    }

}
