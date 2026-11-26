package com.antifraud.scoring.score.ml;

import ai.onnxruntime.OrtException;
import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.application.FraudModel;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
final class OnnxRiskModel implements FraudModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(OnnxRiskModel.class);

    private final OnnxModelSession full;
    private final OnnxModelSession light;
    private final ThreadPoolExecutor executor;
    private final Timer duration;

    OnnxRiskModel(InferenceProperties config, MeterRegistry meters) throws IOException, OrtException {
        duration = meters.timer("scoring.step.duration", "step", "ml");
        full = OnnxModelSession.load(true);
        try {
            light = OnnxModelSession.load(false);
        } catch (IOException | OrtException | RuntimeException error) {
            try {
                full.close();
            } catch (OrtException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        executor = new ThreadPoolExecutor(config.threads(), config.threads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), Thread.ofPlatform().name("scoring-ml-", 0).factory());
        executor.prestartAllCoreThreads();
    }

    @Override
    public String version(boolean fullMode) {
        return select(fullMode).version();
    }

    @Override
    public CompletionStage<Double> predict(RequestFeatures request, Optional<RedisFeatureSnapshot> history) {
        var model = select(history.isPresent());
        try {
            return CompletableFuture.supplyAsync(
                    () -> duration.record(() -> predict(model, request, history.orElse(null))), executor);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private double predict(OnnxModelSession model, RequestFeatures request, RedisFeatureSnapshot history) {
        try {
            return model.predict(request, history);
        } catch (OrtException | IllegalStateException error) {
            LOGGER.warn("Ошибка инференса {}", model.version(), error);
            throw new InferenceFailure(error);
        }
    }

    private OnnxModelSession select(boolean fullMode) {
        return fullMode ? full : light;
    }

    @PreDestroy
    void close() throws OrtException {
        executor.close();
        try (full; light) {
            // Сессии закрываются после завершения принятых задач.
        }
    }
}
