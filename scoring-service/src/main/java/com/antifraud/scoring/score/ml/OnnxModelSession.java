package com.antifraud.scoring.score.ml;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Set;

final class OnnxModelSession implements AutoCloseable {
    private static final String INPUT = "features";
    private static final String OUTPUT = "probabilities";

    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions options;
    private final OrtSession session;
    private final ModelInput input;
    private final String version;

    static OnnxModelSession load(boolean full) throws IOException, OrtException {
        var model = full ? "/models/full.onnx" : "/models/light.onnx";
        try (var resource = OnnxModelSession.class.getResourceAsStream(model)) {
            if (resource == null) {
                throw new IOException("Не найдена модель " + model);
            }
            return new OnnxModelSession(resource.readAllBytes(), full);
        }
    }

    private OnnxModelSession(byte[] model, boolean full) throws OrtException {
        environment = OrtEnvironment.getEnvironment();
        options = new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
            try {
                var metadata = session.getMetadata();
                version = metadata.getCustomMetadataValue("model_version").filter(value -> !value.isBlank()).orElseThrow();
                input = ModelInput.parse(metadata.getCustomMetadataValue("feature_columns").orElseThrow(), full);
                var inputs = session.getInputInfo();
                if (inputs.size() != 1 || !inputs.containsKey(INPUT)
                        || !(inputs.get(INPUT).getInfo() instanceof TensorInfo tensor)
                        || tensor.type != OnnxJavaType.FLOAT || tensor.getShape().length != 2
                        || tensor.getShape()[1] != input.size()) {
                    throw new IllegalArgumentException("Неожиданный размер или тип входа модели " + version);
                }
                predict(new float[input.size()]);
            } catch (OrtException | RuntimeException error) {
                try {
                    session.close();
                } catch (OrtException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
                throw error;
            }
        } catch (OrtException | RuntimeException error) {
            options.close();
            throw error;
        }
    }

    String version() {
        return version;
    }

    double predict(RequestFeatures request, RedisFeatureSnapshot history) throws OrtException {
        return predict(input.encode(request, history));
    }

    private double predict(float[] features) throws OrtException {
        try (var tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), new long[]{1, input.size()});
             var result = session.run(Map.of(INPUT, tensor), Set.of(OUTPUT))) {
            var probabilities = (OnnxTensor) result.get(OUTPUT).orElseThrow();
            var values = probabilities.getFloatBuffer();
            if (values.remaining() != 2) {
                throw new IllegalStateException("Модель должна вернуть вероятности классов 0 и 1");
            }
            var score = values.get(1);
            if (!(score >= 0 && score <= 1)) {
                throw new IllegalStateException("Некорректная вероятность фрода");
            }
            return score;
        }
    }

    @Override
    public void close() throws OrtException {
        try {
            session.close();
        } finally {
            options.close();
        }
    }
}
